package com.berelson.smsaudit

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.Writer
import java.security.MessageDigest

class AuditStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "audit.db", null, 1), AutoCloseable {
    data class Message(val key: String, val original: String, val sender: String, val date: Long, val type: Int)
    data class Summary(val total: Int, val done: Int, val flagged: Int, val failed: Int)
    data class Preview(val key: String, val title: String, val detail: String)

    override fun close() { super.close() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE audit (message_key TEXT PRIMARY KEY, original TEXT NOT NULL,
            sender TEXT NOT NULL, date INTEGER NOT NULL, type INTEGER NOT NULL,
            result TEXT, flagged INTEGER NOT NULL DEFAULT 0, failed INTEGER NOT NULL DEFAULT 0,
            excluded INTEGER NOT NULL DEFAULT 0, scan_tag TEXT NOT NULL DEFAULT '')""")
        db.execSQL("CREATE INDEX audit_pending ON audit(date DESC, message_key) WHERE result IS NULL AND excluded=0")
        db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun put(message: Message): Boolean {
        val db = writableDatabase
        db.query("audit", arrayOf("original", "sender", "date", "type"), "message_key=?",
            arrayOf(message.key), null, null, null).use { c ->
            if (c.moveToFirst() && c.getString(0) == message.original && c.getString(1) == message.sender &&
                c.getLong(2) == message.date && c.getInt(3) == message.type) return false
        }
        db.insertWithOnConflict("audit", null, ContentValues().apply {
            put("message_key", message.key); put("original", message.original)
            put("sender", message.sender); put("date", message.date); put("type", message.type)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        return true
    }

    fun markSeen(key: String, tag: String) {
        writableDatabase.update("audit", ContentValues().apply { put("scan_tag", tag) }, "message_key=?", arrayOf(key))
    }
    fun finishScan(tag: String) {
        writableDatabase.delete("audit", "message_key LIKE 'sms:%' AND scan_tag != ?", arrayOf(tag))
        metadata("device_scan_state", "complete")
    }

    fun metadata(name: String, value: String) {
        check(writableDatabase.insertWithOnConflict("metadata", null, ContentValues().apply {
            put("name", name); put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
    }

    fun next(): Message? = readableDatabase.query("audit", arrayOf("message_key", "original", "sender", "date", "type"),
        "result IS NULL AND excluded=0", null, null, null, "date DESC, message_key ASC", "1").use {
        if (!it.moveToFirst()) null else Message(it.getString(0), it.getString(1), it.getString(2), it.getLong(3), it.getInt(4))
    }

    fun complete(key: String, result: AuditPipeline.Result, onlineTranslation: String? = null, onlineFailure: String? = null) {
        val json = JSONObject().put("engine", AuditPipeline.VERSION)
            .put("raw_mlkit_translation", result.rawTranslation ?: JSONObject.NULL)
            .put("rule_translation", result.translation ?: JSONObject.NULL)
            .put("online_translation", onlineTranslation ?: JSONObject.NULL)
            .put("preferred_reference", if (onlineTranslation != null) "online" else "offline")
            .put("online_failure", onlineFailure ?: JSONObject.NULL)
            .put("prepared_text", result.preparedText)
            .put("protected_terms", JSONArray(result.protectedTerms))
            .put("rule_terms", JSONArray(result.fixedTerms))
            .put("domain", result.domain)
            .put("raw_flags", JSONArray(result.rawFlags)).put("flags", JSONArray(result.flags))
            .put("failure", result.failure ?: JSONObject.NULL)
            .put("semantic_review", if (onlineTranslation != null) "ONLINE_REFERENCE_AVAILABLE" else "OFFLINE_ONLY").put("checked_at", System.currentTimeMillis())
        check(writableDatabase.update("audit", ContentValues().apply {
            put("result", json.toString()); put("flagged", if (result.flags.isEmpty()) 0 else 1)
            put("failed", if (result.translation == null) 1 else 0)
        }, "message_key=?", arrayOf(key)) == 1)
    }

    fun fail(key: String, code: String) {
        complete(key, AuditPipeline.Result(null, null, "", emptyList(), emptyList(), "unknown",
            listOf("NO_TRANSLATION"), listOf(code), code))
    }

    fun retryFailures() { writableDatabase.execSQL("UPDATE audit SET result=NULL,flagged=0,failed=0 WHERE failed=1 AND excluded=0") }
    fun exclude(key: String) { writableDatabase.update("audit", ContentValues().apply { put("excluded", 1) }, "message_key=?", arrayOf(key)) }
    fun clear() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("audit", null, null); writableDatabase.delete("metadata", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    fun summary(): Summary = readableDatabase.rawQuery("""SELECT COUNT(*),
        COALESCE(SUM(CASE WHEN result IS NOT NULL THEN 1 ELSE 0 END),0),
        COALESCE(SUM(flagged),0), COALESCE(SUM(failed),0) FROM audit WHERE excluded=0""", null).use {
        it.moveToFirst(); Summary(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3))
    }

    fun previews(offset: Int = 0): List<Preview> = buildList {
        readableDatabase.query("audit", arrayOf("message_key", "original", "result", "sender"),
            "excluded=0", null, null, null, "date DESC, message_key ASC", "$offset,50").use { c ->
            while (c.moveToNext()) {
                val r = c.getString(2)?.let(::JSONObject)
                val translated = r?.optString("rule_translation")?.takeUnless { it == "null" }
                val flags = r?.optJSONArray("flags")
                val state = when { r == null -> "Ожидает"; translated == null -> "Ошибка";
                    flags != null && flags.length() > 0 -> "Проверить"; else -> "Нужна проверка смысла" }
                add(Preview(c.getString(0), "$state · ${c.getString(1).take(65)}",
                    "Оригинал:\n${c.getString(1)}\n\nС правилами:\n${translated ?: "Нет перевода"}" +
                    "\n\nОбычный ML Kit:\n${r?.optString("raw_mlkit_translation") ?: "Нет перевода"}" +
                    "\n\nЗамечания: ${flags ?: "—"}\n\nСмысл автоматически не проверен."))
            }
        }
    }

    /** Writes records one by one; memory usage does not grow with the SMS collection. */
    fun export(out: Writer, includeSenders: Boolean, ensureActive: () -> Unit = {}) {
        val meta = JSONObject()
        readableDatabase.query("metadata", arrayOf("name", "value"), null, null, null, null, "name").use { c ->
            while (c.moveToNext()) meta.put(c.getString(0), c.getString(1))
        }
        val summary = summary()
        val header = JSONObject().put("schema", "sms-offline-audit/v1")
            .put("engine", AuditPipeline.VERSION).put("created_at", System.currentTimeMillis())
            .put("source_language", "he").put("target_language", "ru")
            .put("semantic_review", "OFFLINE_AND_ONLINE_COMPARISON")
            .put("complete", summary.total == summary.done && meta.optString("device_scan_state") != "incomplete")
            .put("total", summary.total).put("processed", summary.done)
            .put("flagged", summary.flagged).put("failed", summary.failed)
            .put("metadata", meta).put("senders_included", includeSenders)
            .put("export_scope", "PROCESSED_ONLY")
            .put("notice", "Contains full SMS text and any codes/addresses within it. Generated translations are not the other app's stored translations. Flags are heuristics, not proof of correctness.")
        out.write(header.toString().dropLast(1)); out.write(",\"messages\":[\n")
        var first = true
        readableDatabase.query("audit", arrayOf("message_key", "original", "sender", "date", "type", "result"),
            "excluded=0 AND result IS NOT NULL", null, null, null, "flagged DESC, date DESC, message_key ASC").use { c ->
            while (c.moveToNext()) {
                ensureActive()
                val row = JSONObject().put("id", c.getString(0)).put("original", c.getString(1))
                    .put("sender_group", digest(c.getString(2)))
                    .put("date", c.getLong(3)).put("sms_type", c.getInt(4))
                    .put("analysis", c.getString(5)?.let(::JSONObject) ?: JSONObject.NULL)
                if (includeSenders) row.put("sender", c.getString(2))
                if (!first) out.write(",\n")
                out.write(row.toString()); first = false
            }
        }
        out.write("\n]}"); out.flush()
    }

    companion object {
        fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
