package com.berelson.smsaudit

import android.content.Context
import android.provider.Telephony
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Reader

object SmsReader {
    data class Counts(val scanned: Int, val hebrew: Int, val skipped: Int)
    suspend fun readDevice(context: Context, store: AuditStore, progress: suspend (Int) -> Unit): Counts =
        readDeviceInternal(context, store, progress, incremental = false)

    /** After the first successful device scan, reads only SMS newer than the saved checkpoint. */
    suspend fun readDeviceIncremental(context: Context, store: AuditStore, progress: suspend (Int) -> Unit): Counts =
        readDeviceInternal(context, store, progress, incremental = true)

    private suspend fun readDeviceInternal(context: Context, store: AuditStore, progress: suspend (Int) -> Unit, incremental: Boolean): Counts {
        var scanned = 0; var hebrew = 0
        val prefs = context.getSharedPreferences("sms_audit_scan", Context.MODE_PRIVATE)
        val checkpoint = if (incremental) prefs.getLong("last_sms_date", 0L) else 0L
        var newestDate = checkpoint
        val selection = if (checkpoint > 0L) "date > ?" else null
        val args = if (checkpoint > 0L) arrayOf(checkpoint.toString()) else null
        store.metadata("device_scan_state", "incomplete")
        val cursor = context.contentResolver.query(Telephony.Sms.CONTENT_URI,
            arrayOf("_id", "body", "address", "date", "type"), selection, args, "date ASC")
            ?: error("SMS_PROVIDER_UNAVAILABLE")
        cursor.use { c ->
            while (c.moveToNext()) {
                currentCoroutineContext().ensureActive(); scanned++
                val id = c.getLong(0); val body = c.getString(1).orEmpty(); val date = c.getLong(3)
                if (date > newestDate) newestDate = date
                if (AuditPipeline.hasHebrew(body)) {
                    store.put(AuditStore.Message("sms:$id", body, c.getString(2).orEmpty(), date, c.getInt(4))); hebrew++
                }
                if (scanned % 100 == 0) progress(scanned)
            }
        }
        // Save checkpoint only after the scan completed successfully. Existing rows/results are never reset.
        if (newestDate > checkpoint) prefs.edit().putLong("last_sms_date", newestDate).apply()
        store.metadata("device_scan_state", "complete")
        store.metadata("device_last_incremental_scanned", scanned.toString())
        store.metadata("device_last_incremental_hebrew", hebrew.toString())
        store.metadata("device_scan_completed_at", System.currentTimeMillis().toString())
        return Counts(scanned, hebrew, scanned - hebrew)
    }

    /** Simple backup interchange: array of {original/body, sender/address, date, type}. */
    suspend fun readJson(input: Reader, store: AuditStore): Counts {
        var scanned = 0; var hebrew = 0
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            JsonReader(input).use { reader ->
                reader.beginArray()
                while (reader.hasNext()) {
                    currentCoroutineContext().ensureActive()
                    var body = ""; var sender = ""; var date = 0L; var type = 1
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); continue }
                        when (name) {
                            "original", "body" -> body = reader.nextString()
                            "sender", "address" -> sender = reader.nextString()
                            "date" -> date = reader.nextLong()
                            "type" -> type = reader.nextInt()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject(); scanned++
                    require(body.length <= 100_000 && sender.length <= 1000) { "ENTRY_TOO_LARGE" }
                    if (AuditPipeline.hasHebrew(body)) {
                        val key = "import:" + AuditStore.digest("$scanned\u0000$date\u0000$type\u0000$sender\u0000$body")
                        store.put(AuditStore.Message(key, body, sender, date, type)); hebrew++
                    }
                }
                reader.endArray()
                require(reader.peek() == JsonToken.END_DOCUMENT) { "TRAILING_CONTENT" }
            }
            store.metadata("json_scanned", scanned.toString()); store.metadata("json_hebrew", hebrew.toString())
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return Counts(scanned, hebrew, scanned - hebrew)
    }
}
