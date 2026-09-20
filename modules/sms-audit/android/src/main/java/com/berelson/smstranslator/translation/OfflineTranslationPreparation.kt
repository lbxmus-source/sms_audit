package com.berelson.smstranslator.translation

/** One shared preparation path for normal translation, retry and JVM regression tests. */
object OfflineTranslationPreparation {
    data class Prepared(val text: String, val protectedTerms: List<String>, val fixedTerms: List<String>)

    fun prepare(
        original: String,
        source: String,
        target: String,
        domain: DomainRuleEngine.Domain?,
        userTerms: List<String> = emptyList(),
        observed: Set<String> = emptySet(),
        sender: String = "",
    ): Prepared {
        val contextualNames = if (source == "he") HebrewNotificationRules.entities(original) else emptyList()
        val names = BrandProtector.terms(original, sender) + contextualNames
        val nativeText = if (source == "he" && target == "ru") MixedLanguageText.russianLines(original) else emptyList()
        // Unfamiliar names captured in explicit source slots must not be rewritten by even a
        // reviewed short phrase (a driver's surname may look like an ordinary Hebrew word).
        val exclusions = userTerms + nativeText + contextualNames
        // Pin technical ranges recognised in the SOURCE. User terms always take precedence.
        val hardTerms = (exclusions + TokenProtector.protect(original, exclusions).tokens.map { it.original }).distinct()
        val useDomain = source == "he" && target == "ru"
        val slots = if (useDomain) HebrewSlotRules.prepare(original, exclusions + names)
            else HebrewSlotRules.Prepared(original, emptyList())
        // A reviewed phrase can supply case/agreement for a name. It runs before protecting
        // individual entities, so a short brand cannot disable a longer reviewed construction.
        val phrases = if (useDomain) DomainRuleEngine.prepareTranslation(
            slots.text, domain, hardTerms + slots.fixedTerms, original,
        ) else null
        val fixed = slots.fixedTerms + phrases?.fixedTranslations.orEmpty()
        val working = phrases?.text ?: slots.text
        val morphology = if (source == "he") HebrewPrefixAnalyzer().prepare(
            working, hardTerms + fixed + names, target, observed,
        ) else HebrewPrefixAnalyzer.Prepared(working, emptyList())
        return Prepared(morphology.text, hardTerms, (fixed + morphology.protectedTerms + names).distinct())
    }
}
