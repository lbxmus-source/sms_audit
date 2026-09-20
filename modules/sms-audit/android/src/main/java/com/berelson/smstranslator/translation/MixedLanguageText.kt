package com.berelson.smstranslator.translation

/** Preserve already Russian lines in bilingual notifications, including embedded merchant data. */
object MixedLanguageText {
    fun russianLines(text: String): List<String> = text.lineSequence().filter { line ->
        val russian = line.count { it in '\u0400'..'\u052F' && it.isLetter() }
        val hebrew = line.count { it in 'א'..'ת' }
        russian >= 3 && russian > hebrew * 3
    }.map(String::trim).filter(String::isNotEmpty).toList()

    fun hasBothRussianAndHebrewProse(text: String): Boolean {
        val russian = russianLines(text)
        if (russian.isEmpty()) return false
        val remaining = TokenProtector.segments(text, russian).filter { it.translatable }.joinToString(" ") { it.text }
        return Regex("[א-ת]{2,}").findAll(remaining).count() >= 3 && remaining.count { it in 'א'..'ת' } >= 12
    }
}
