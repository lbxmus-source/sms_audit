package com.berelson.smsaudit

/** Masks common sensitive/technical values before an SMS is sent to the online reference translator. */
object PrivacyMasker {
    data class Masked(val text: String, val values: Map<String, String>) {
        fun restore(translated: String): String {
            var out = translated
            values.forEach { (token, value) -> out = out.replace(token, value, ignoreCase = true) }
            return out
        }
    }

    private val patterns = listOf(
        Regex("https?://\\S+", RegexOption.IGNORE_CASE),
        Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE),
        Regex("(?<!\\d)(?:\\+?972[- ]?)?0?5\\d[- ]?\\d{3}[- ]?\\d{4}(?!\\d)"),
        Regex("(?<!\\d)\\d{5,8}(?!\\d)"),
        Regex("#[A-Za-z0-9_-]{4,}"),
        Regex("\\b[A-Z]{2,}\\d{4,}[A-Z0-9]*\\b", RegexOption.IGNORE_CASE)
    )

    fun mask(text: String): Masked {
        var out = text
        val values = linkedMapOf<String, String>()
        var n = 0
        patterns.forEach { re ->
            out = re.replace(out) { m ->
                val token = "ZXPRIV${n++}ZX"
                values[token] = m.value
                token
            }
        }
        return Masked(out, values)
    }
}
