package com.berelson.smsaudit

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var counts: TextView
    private lateinit var rows: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var stop: Button
    private lateinit var senders: CheckBox
    private val controls = mutableListOf<Button>()
    private var work: Job? = null
    private var page = 0
    private var lastStatus = "Готово к работе. Сначала загрузите модели, затем прочитайте SMS."
    private var exportSenders = false
    private var preparedExport: File? = null

    private val smsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanSms() else permissionHelp()
    }
    private val openJson = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runWork("Читаю файл…") {
            val c = withContext(Dispatchers.IO) {
                AuditStore(this@MainActivity).use { store ->
                    val stream = contentResolver.openInputStream(uri) ?: error("FILE_NOT_FOUND")
                    stream.bufferedReader(Charsets.UTF_8).use { SmsReader.readJson(it, store) }
                }
            }
            lastStatus = "Прочитано ${c.scanned}. С ивритом: ${c.hebrew}. Без иврита: ${c.skipped}."
        }
    }
    private val saveReport = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val prepared = preparedExport
        if (uri != null && prepared != null) runWork("Сохраняю отчёт…") {
            withContext(Dispatchers.IO) {
                val stream = contentResolver.openOutputStream(uri, "wt") ?: error("FILE_NOT_WRITABLE")
                stream.use { output -> prepared.inputStream().use { it.copyTo(output) } }
            }
            lastStatus = "Отчёт сохранён. Прикрепите этот JSON-файл к чату."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preparedExport = savedInstanceState?.getString("export_file")?.let(::File)?.takeIf { it.isFile }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.rgb(238, 244, 252))
        }
        scroll.addView(content); setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        content.addView(label("Проверка SMS", 26f))
        content.addView(label("Иврит → русский · офлайн + онлайн-аудит", 16f))
        content.addView(label("Каждая SMS переводится локально и, при наличии интернета, онлайн для сравнения. На экран тексты SMS не выводятся: полный результат сохраняется в отчёт.", 14f))
        status = label(lastStatus, 15f); content.addView(status)
        counts = label("", 15f); content.addView(counts)
        progress = ProgressBar(this).apply { visibility = View.GONE }; content.addView(progress)
        button(content, "1. Загрузить модели перевода") { downloadDialog() }
        button(content, "2. Прочитать все SMS") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) scanSms()
            else smsPermission.launch(Manifest.permission.READ_SMS)
        }
        button(content, "3. Перевести / продолжить") { translateAll() }
        stop = Button(this).apply { text = "Пауза"; isEnabled = false; setOnClickListener { work?.cancel() } }
        content.addView(stop)
        content.addView(label("Во время проверки экран остаётся включённым. При выходе — пауза; готовые результаты сохраняются. Новые SMS добавляются повторным чтением.", 13f))
        senders = CheckBox(this).apply { text = "Сохранять имя / заголовок отправителя в отчёте"; isChecked = true; isEnabled = false }; content.addView(senders)
        button(content, "4. Сохранить отчёт") { exportDialog(false) }
        button(content, "Поделиться отчётом") { exportDialog(true) }
        button(content, "Повторить неудавшиеся переводы") {
            runWork("Готовлю повторную проверку…") {
                withContext(Dispatchers.IO) { AuditStore(this@MainActivity).use { it.retryFailures() } }
                lastStatus = "Нажмите «Перевести / продолжить»."
            }
        }
        button(content, "Импортировать SMS из JSON") { openJson.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
        button(content, "Вставить SMS вручную") { pasteDialog() }
        button(content, "Удалить данные проверки") {
            AlertDialog.Builder(this).setTitle("Удалить данные проверки?")
                .setMessage("Удалятся только местные копии и отчёты анализатора. SMS на телефоне останутся. Сохранённые вами внешние файлы не удаляются.")
                .setNegativeButton("Отмена", null).setPositiveButton("Удалить") { _, _ ->
                    runWork("Удаляю данные проверки…") {
                        withContext(Dispatchers.IO) {
                            AuditStore(this@MainActivity).use { it.clear() }; File(cacheDir, "reports").deleteRecursively()
                        }
                        page = 0; preparedExport = null; lastStatus = "Данные проверки удалены."
                    }
                }.show()
        }
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }; content.addView(rows)
        refresh()
    }

    private fun button(parent: LinearLayout, title: String, action: () -> Unit) {
        val b = Button(this).apply { text = title; isAllCaps = false; setOnClickListener { action() } }
        controls += b; parent.addView(b)
    }
    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(25, 42, 64)); setPadding(0, dp(7), 0, dp(7))
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun runWork(message: String, action: suspend () -> Unit) {
        if (work?.isActive == true) return
        lastStatus = message; status.text = message; setBusy(true)
        work = lifecycleScope.launch {
            try { action() }
            catch (e: CancellationException) { lastStatus = "Пауза. Готовые результаты сохранены."; throw e }
            catch (_: SecurityException) { lastStatus = "Нет доступа к SMS или файлу. Разрешите чтение SMS либо используйте импорт / вставку." }
            catch (_: Exception) { lastStatus = "Операция не завершена. Проверьте разрешения, свободную память и формат файла. При загрузке моделей проверьте интернет." }
            finally { setBusy(false); status.text = lastStatus; refresh() }
        }
    }
    private fun setBusy(busy: Boolean) {
        controls.forEach { it.isEnabled = !busy }; senders.isEnabled = false; stop.isEnabled = busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun refresh() {
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) { AuditStore(this@MainActivity).use { it.summary() } }
            counts.text = "С ивритом: ${summary.total} · обработано: ${summary.done}\nС замечаниями: ${summary.flagged} · без перевода: ${summary.failed}"
        }
    }
    private fun downloadDialog() {
        AlertDialog.Builder(this).setTitle("Загрузка офлайн-моделей")
            .setMessage("Интернет нужен для первоначальной загрузки моделей. Сами SMS переводятся на телефоне. Объём загрузки — десятки мегабайт.")
            .setPositiveButton("Через Wi-Fi") { _, _ -> download(true) }
            .setNeutralButton("Любая сеть") { _, _ -> download(false) }
            .setNegativeButton("Отмена", null).show()
    }
    private fun download(wifi: Boolean) = runWork("Загружаю модели…") {
        withContext(Dispatchers.IO) { OfflineModel().use { it.download(wifi) } }
        lastStatus = "Модели готовы. Дальше можно отключить интернет."
    }
    private fun permissionHelp() {
        AlertDialog.Builder(this).setTitle("Нет доступа к SMS")
            .setMessage("Если Android разрешает, включите разрешение SMS в настройках приложения. Некоторые способы установки блокируют это разрешение. Можно импортировать SMS из JSON или вставить текст вручную. Анализатор не требует быть основным SMS-приложением.")
            .setPositiveButton("Понятно", null).show()
    }
    private fun scanSms() = runWork("Читаю SMS…") {
        val c = withContext(Dispatchers.IO) {
            AuditStore(this@MainActivity).use { store ->
                SmsReader.readDevice(this@MainActivity, store) { n -> withContext(Dispatchers.Main) { status.text = "Прочитано SMS: $n" } }
            }
        }
        lastStatus = "Прочитано ${c.scanned}. С ивритом: ${c.hebrew}. Пропущено без иврита: ${c.skipped}."
    }
    private fun translateAll() = runWork("Проверяю модели…") {
        withContext(Dispatchers.IO) {
            OfflineModel().use { model ->
                if (!model.ready()) { lastStatus = "Сначала нажмите «Загрузить модели перевода»."; return@withContext }
                AuditStore(this@MainActivity).use { store ->
                    var completed = store.summary().done
                    var summary = store.summary()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val message = store.next() ?: break
                        try {
                            val result = withTimeout(120_000L) { AuditPipeline.run(message.original, message.sender, model::translate) }
                            var online: String? = null
                            var onlineFailure: String? = null
                            try {
                                val masked = PrivacyMasker.mask(message.original)
                                online = withTimeout(30_000L) { OnlineTranslator().translate(masked.text) }
                                online = masked.restore(online!!)
                            } catch (e: CancellationException) { throw e }
                              catch (e: Exception) { onlineFailure = e.message ?: "ONLINE_ERROR" }
                            currentCoroutineContext().ensureActive(); store.complete(message.key, result, online, onlineFailure)
                        } catch (_: TimeoutCancellationException) { store.fail(message.key, "TIMEOUT") }
                          catch (e: CancellationException) { throw e }
                          catch (_: Exception) { store.fail(message.key, "PROCESSING_ERROR") }
                        completed++
                        if (completed % 20 == 0 || completed == summary.total) summary = store.summary()
                        withContext(Dispatchers.Main) {
                            status.text = "Обработано $completed из ${summary.total}. Замечания: ${summary.flagged}."
                            counts.text = "С ивритом: ${summary.total} · обработано: ${summary.done}\nС замечаниями: ${summary.flagged} · без перевода: ${summary.failed}"
                        }
                    }
                    lastStatus = if (store.summary().total == 0) "Сначала прочитайте SMS или импортируйте файл."
                        else "Проверка завершена. Сохраните отчёт и прикрепите его в чат для проверки смысла."
                }
            }
        }
    }
    private fun exportDialog(share: Boolean) {
        AlertDialog.Builder(this).setTitle("Выгрузить отчёт?")
            .setMessage("Отчёт содержит оригинал SMS, офлайн-перевод, онлайн-перевод и имя/заголовок отправителя. Перед онлайн-переводом ссылки, телефоны, e-mail и многие коды маскируются, а после ответа восстанавливаются. Передачу отчёта выполняете вы.")
            .setNegativeButton("Отмена", null).setPositiveButton("Продолжить") { _, _ ->
                exportSenders = true; prepareReport(share)
            }.show()
    }
    private fun prepareReport(share: Boolean) = runWork("Готовлю отчёт…") {
        val report = withContext(Dispatchers.IO) {
            val dir = File(cacheDir, "reports").apply { mkdirs() }
            val dest = File(dir, "sms-audit-${System.currentTimeMillis()}.json")
            val temp = File(dir, dest.name + ".tmp"); val coroutine = currentCoroutineContext()
            try {
                AuditStore(this@MainActivity).use { store ->
                    temp.bufferedWriter(Charsets.UTF_8).use { store.export(it, exportSenders) { coroutine.ensureActive() } }
                }
                check(temp.renameTo(dest)); dest
            } finally { temp.delete() }
        }
        preparedExport = report
        lastStatus = "Отчёт подготовлен. Выберите, куда его сохранить или передать."
        if (share) {
            val uri = FileProvider.getUriForFile(this, "$packageName.reports", report)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Отчёт SMS", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Передать отчёт"))
        } else saveReport.launch(report.name)
    }
    private fun pasteDialog() {
        val input = EditText(this).apply { hint = "Вставьте одну SMS на иврите"; minLines = 4; maxLines = 10 }
        AlertDialog.Builder(this).setTitle("Добавить SMS").setView(input)
            .setNegativeButton("Отмена", null).setPositiveButton("Добавить") { _, _ ->
                val text = input.text.toString()
                if (!AuditPipeline.hasHebrew(text)) { status.text = "В тексте не найден иврит."; return@setPositiveButton }
                runWork("Добавляю SMS…") {
                    withContext(Dispatchers.IO) {
                        AuditStore(this@MainActivity).use { it.put(AuditStore.Message("paste:" + AuditStore.digest(text), text, "", 0, 1)) }
                    }
                    lastStatus = "Текст добавлен. Нажмите «Перевести / продолжить»."
                }
            }.show()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("export_file", preparedExport?.absolutePath); super.onSaveInstanceState(outState)
    }
    override fun onStop() { work?.cancel(); super.onStop() }
}
