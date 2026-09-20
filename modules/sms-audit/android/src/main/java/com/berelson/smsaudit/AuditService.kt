package com.berelson.smsaudit

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class AuditService : Service() {
    companion object {
        const val ACTION_START = "com.berelson.smsaudit.START_AUDIT"
        const val ACTION_STOP = "com.berelson.smsaudit.STOP_AUDIT"
        private const val CHANNEL = "sms_audit_background"
        private const val NOTIFICATION_ID = 4107
        fun start(context: Context) {
            val i = Intent(context, AuditService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
        fun stop(context: Context) = context.stopService(Intent(context, AuditService::class.java))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate(); createChannel()
        startForeground(NOTIFICATION_ID, notification("Подготовка фоновой проверки…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (job?.isActive == true) return START_STICKY
        job = scope.launch { runAudit() }
        return START_STICKY
    }

    private suspend fun runAudit() {
        try {
            AuditStore(this).use { store ->
                // Incremental scan: after the first full scan, only SMS newer than the saved checkpoint are read.
                val scan = SmsReader.readDeviceIncremental(this, store) { n -> update("Проверяю новые SMS: $n") }
                OfflineModel().use { model ->
                    if (!model.ready()) { update("Офлайн-модель не загружена. Откройте приложение и загрузите модель."); delay(2500); return }
                    while (true) {
                        ensureActive()
                        val message = store.next() ?: break
                        val result = try { withTimeout(120_000L) { AuditPipeline.run(message.original, message.sender, model::translate) } }
                        catch (_: TimeoutCancellationException) { store.fail(message.key, "TIMEOUT"); continue }
                        catch (_: Exception) { store.fail(message.key, "PROCESSING_ERROR"); continue }
                        var online: String? = null; var onlineFailure: String? = null
                        try {
                            val masked = PrivacyMasker.mask(message.original)
                            online = withTimeout(30_000L) { OnlineTranslator().translate(masked.text) }
                            online = masked.restore(online)
                        } catch (e: CancellationException) { throw e }
                          catch (e: Exception) { onlineFailure = e.message ?: "ONLINE_ERROR" }
                        store.complete(message.key, result, online, onlineFailure)
                        val x = store.summary(); update("Обработано ${x.done} из ${x.total} · замечаний ${x.flagged}")
                    }
                    val x = store.summary(); update("Готово: ${x.done}/${x.total}. Новых SMS: ${scan.hebrew}")
                    delay(1800)
                }
            }
        } catch (_: CancellationException) { }
          catch (_: SecurityException) { update("Нет разрешения на чтение SMS."); delay(2500) }
          catch (_: Exception) { update("Фоновая проверка прервана. При следующем запуске продолжится с сохранённого места."); delay(2500) }
        finally { stopSelf() }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Фоновая проверка SMS", NotificationManager.IMPORTANCE_LOW))
        }
    }
    private fun notification(text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Проверка SMS").setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi).build()
    }
    private fun update(text: String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) }
    override fun onDestroy() { job?.cancel(); scope.cancel(); super.onDestroy() }
}
