package com.berelson.smstranslator.translation

/** Sender aliases are evidence for names, never a blanket ban on translating ordinary words.
 * All returned values are surface spellings from this SMS, not guessed transliterations.
 */
object BrandProtector {
    data class Brand(val senders: List<String>, val names: List<String>)

    val brands = listOf(
        Brand(listOf("YDeli", "Yango Deli"), listOf("יאנגו דלי", "Yango Deli", "YDeli")),
        Brand(listOf("BIGELECTRIC", "Big Electric"), listOf("ביג אלקטריק", "ביגאלקטריק", "Big Electric", "BIGELECTRIC")),
        Brand(listOf("ALM"), listOf("א.ל.מ.", "א.ל.מ", "ALM")),
        Brand(listOf("Golan T", "Golan Telecom"), listOf("גולן טלקום", "Golan Telecom")),
        Brand(listOf("Mishloha"), listOf("משלוחה", "Mishloha", "FreeDay")),
        Brand(listOf("Cal"), listOf("כאל", "Cal")),
    )

    private val unambiguousNames = listOf(
        "FreeDay", "Yango Deli", "יאנגו דלי", "ביג אלקטריק", "ביגאלקטריק", "גולן טלקום",
        "iHerb", "FOX HOME", "GALANZ", "BRIZA", "פיליפס", "תדיראן", "יד מרדכי",
    )
    private val genericSenders = setOf("message", "messages", "service", "support", "notice", "verify", "verification", "security", "sms", "info", "code", "bank")

    fun terms(text: String, sender: String): List<String> {
        val normalized = normalize(sender)
        val known = brands.filter { brand -> brand.senders.any { normalize(it) == normalized } }
        val candidates = (unambiguousNames + known.flatMap { it.names } +
            listOfNotNull(sender.trim().takeIf {
                it.length in 5..40 && it.any(Char::isLetter) && it.all { c -> c.isLetter() || c.isWhitespace() } &&
                    normalized !in genericSenders
            })).distinct()
        return candidates.flatMap { name ->
            // A Hebrew preposition can be attached to a reviewed brand. Morphology handles that
            // candidate later. Do not match names as arbitrary substrings of a different word.
            val hebrew = name.firstOrNull()?.let { it in 'א'..'ת' } == true
            val boundary = if (hebrew) "[\\p{L}\\p{N}_]" else "[A-Za-z0-9_]"
            val prefix = if (hebrew) "(?<!$boundary)(?:ו?ש?[בלכמ]?ה?)" else "(?<!$boundary)"
            Regex(prefix + "(" + Regex.escape(name) + ")(?!$boundary)", RegexOption.IGNORE_CASE)
                .findAll(text).map { it.groupValues[1] }.toList()
        }.distinct()
    }

    private fun normalize(value: String) = value.lowercase(java.util.Locale.ROOT).filter(Char::isLetterOrDigit)
}
