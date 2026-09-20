package com.berelson.smsaudit

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/** Online reference translator used only by the audit app.
 * SMS text is masked before transmission; placeholders are restored afterwards.
 * The endpoint is best-effort and may be rate-limited, so offline output is always retained.
 */
class OnlineTranslator {
    suspend fun translate(maskedText: String): String {
        val q = URLEncoder.encode(maskedText, "UTF-8")
        val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=he&tl=ru&dt=t&q=$q")
        val c = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 20000
            requestMethod = "GET"; setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SMS-Audit/1.1")
        }
        try {
            if (c.responseCode !in 200..299) error("HTTP_${c.responseCode}")
            val root = JSONArray(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val parts = root.getJSONArray(0)
            return buildString {
                for (i in 0 until parts.length()) append(parts.getJSONArray(i).optString(0))
            }.trim()
        } finally { c.disconnect() }
    }
}
