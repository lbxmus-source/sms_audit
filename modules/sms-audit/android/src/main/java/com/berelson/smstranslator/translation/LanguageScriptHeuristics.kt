package com.berelson.smstranslator.translation

/** Conservative script hints used before ML Kit for clearly identifiable messages. */
internal object LanguageScriptHeuristics {
    fun strongLanguageHint(input: String): String? {
        if (MixedLanguageText.hasBothRussianAndHebrewProse(input)) return HEBREW
        val text = naturalText(input)
        val counts = scriptCounts(text)
        if (counts.totalLetters < MIN_LETTERS) return null

        if (isDominant(counts.cyrillic, counts.totalLetters)) {
            val lowercase = text.lowercase()
            if (lowercase.any { it in UKRAINIAN_DISTINCTIVE }) return UKRAINIAN
        }
        if (isDominant(counts.hebrew, counts.totalLetters)) return HEBREW
        return null
    }

    fun correctedIdentifiedLanguage(input: String, identifiedLanguage: String): String {
        if (identifiedLanguage != HEBREW) return identifiedLanguage
        val text = naturalText(input)
        val counts = scriptCounts(text)
        if (counts.totalLetters < MIN_LETTERS) return identifiedLanguage
        if (!isDominant(counts.cyrillic, counts.totalLetters)) return identifiedLanguage

        val lowercase = text.lowercase()
        return when {
            lowercase.any { it in UKRAINIAN_DISTINCTIVE } -> UKRAINIAN
            lowercase.any { it in RUSSIAN_DISTINCTIVE } -> RUSSIAN
            else -> identifiedLanguage
        }
    }

    fun fallbackLanguage(input: String, targetLanguage: String): String {
        strongLanguageHint(input)?.let { return it }
        val text = naturalText(input)
        val counts = scriptCounts(text)
        val lowercase = text.lowercase()
        return when {
            counts.hebrew >= MIN_LETTERS && counts.hebrew >= counts.cyrillic &&
                counts.hebrew >= counts.latin -> HEBREW
            counts.cyrillic >= MIN_LETTERS && counts.cyrillic >= counts.latin ->
                if (lowercase.any { it in UKRAINIAN_DISTINCTIVE }) UKRAINIAN else RUSSIAN
            counts.latin >= MIN_LETTERS -> ENGLISH
            else -> targetLanguage
        }
    }

    private fun naturalText(input: String): String = TokenProtector.segments(input)
        .asSequence()
        .filter { it.translatable }
        .joinToString(separator = "") { it.text }

    private fun scriptCounts(text: String): ScriptCounts {
        var hebrew = 0
        var cyrillic = 0
        var latin = 0
        var totalLetters = 0
        text.forEach { character ->
            if (!character.isLetter()) return@forEach
            totalLetters++
            when (character) {
                in '\u0590'..'\u05FF' -> hebrew++
                in '\u0400'..'\u052F' -> cyrillic++
                in 'A'..'Z', in 'a'..'z' -> latin++
            }
        }
        return ScriptCounts(hebrew, cyrillic, latin, totalLetters)
    }

    private fun isDominant(count: Int, total: Int): Boolean =
        count * DOMINANCE_DENOMINATOR >= total * DOMINANCE_NUMERATOR

    private data class ScriptCounts(
        val hebrew: Int,
        val cyrillic: Int,
        val latin: Int,
        val totalLetters: Int,
    )

    private const val MIN_LETTERS = 3
    private const val DOMINANCE_NUMERATOR = 3
    private const val DOMINANCE_DENOMINATOR = 4
    private const val HEBREW = "he"
    private const val RUSSIAN = "ru"
    private const val UKRAINIAN = "uk"
    private const val ENGLISH = "en"
    private const val RUSSIAN_DISTINCTIVE = "ыэё"
    private const val UKRAINIAN_DISTINCTIVE = "іїєґ"
}
