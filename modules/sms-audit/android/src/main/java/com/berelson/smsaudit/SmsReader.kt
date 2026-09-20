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
    suspend fun readDevice(context: Context, store: AuditStore, progress: suspend (Int) -> Unit): Counts {
        var scanned = 0; var hebrew = 0
        val scanTag = java.util.UUID.randomUUID().toString()
        store.metadata("device_scan_state", "incomplete")
        val cursor = context.contentResolver.query(Telephony.Sms.CONTENT_URI,
            arrayOf("_id", "body", "address", "date", "type"), null, null, "date DESC")
            ?: error("SMS_PROVIDER_UNAVAILABLE")
        cursor.use { c ->
            while (c.moveToNext()) {
                currentCoroutineContext().ensureActive(); scanned++
                val body = c.getString(1).orEmpty()
                if (AuditPipeline.hasHebrew(body)) {
                    store.put(AuditStore.Message("sms:${c.getLong(0)}", body, c.getString(2).orEmpty(), c.getLong(3), c.getInt(4)))
                    store.markSeen("sms:${c.getLong(0)}", scanTag)
                    hebrew++
                }
                if (scanned % 100 == 0) progress(scanned)
            }
        }
        store.finishScan(scanTag)
        store.metadata("device_scanned", scanned.toString())
        store.metadata("device_hebrew", hebrew.toString())
        store.metadata("device_skipped_without_hebrew", (scanned - hebrew).toString())
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
