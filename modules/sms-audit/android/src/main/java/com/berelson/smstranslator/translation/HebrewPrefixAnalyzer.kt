package com.berelson.smstranslator.translation

/** Conservative candidates, not an unrestricted Hebrew stemmer. Never modifies stored SMS. */
class HebrewPrefixAnalyzer(
    private val forceSplit: Map<String, HebrewPrefixRules.Split> = HebrewPrefixRules.forceSplit,
    private val neverSplit: Set<String> = HebrewPrefixRules.neverSplit,
    private val knownWords: Set<String> = HebrewPrefixRules.knownWords,
    private val entities: List<HebrewPrefixRules.Entity> = HebrewPrefixRules.entities,
    private val confirmedSplits: Set<String> = HebrewPrefixRules.confirmedSplits,
    private val contextRules: List<HebrewPrefixRules.ContextRule> = HebrewPrefixRules.contextRules,
) {
    data class Analysis(val prefixes: String, val stem: String, val entity: HebrewPrefixRules.Entity?)
    data class Prepared(val text: String, val protectedTerms: List<String>)

    fun analyze(word: String, context: String = "", observed: Set<String> = emptySet()): Analysis? {
        forceSplit[word]?.let { split ->
            // Even a hand-edited database may not silently delete letters.
            if (split.prefixes + split.stem == word && split.stem.isNotEmpty() &&
                split.prefixes.isNotEmpty() && split.prefixes.length <= 4 &&
                split.prefixes.all { it in PREFIXES }
            ) return Analysis(split.prefixes, split.stem, entities.firstOrNull { it.hebrew == split.stem })
        }
        if (word in neverSplit) return null
        entities.firstOrNull { it.hebrew == word }?.let { return Analysis("", word, it) }
        if (word in knownWords) return null
        val contextual = contextRules.any { it.word == word && it.pattern.containsMatchIn(context) }
        val candidates = mutableListOf<Analysis>()
        for (depth in 1..minOf(4, word.length - 2)) {
            val prefixes = word.take(depth)
            if (!prefixes.all { it in PREFIXES }) break
            if (!validChain(prefixes)) continue
            val stem = word.drop(depth)
            val entity = entities.firstOrNull { it.hebrew == stem }
            val known = stem in knownWords
            val confirmed = word in confirmedSplits
            // ו/ה/ש need a proper entity, an explicit combination, or reviewed context.
            val strict = prefixes.any { it == 'ו' || it == 'ה' || it == 'ש' }
            val evidence = entity != null || known || (stem in observed && (confirmed || contextual))
            if (evidence && (!strict || entity != null || confirmed || contextual)) {
                candidates += Analysis(prefixes, stem, entity)
            }
            // A known complete remainder is a stopping point, not more material to strip.
            if (entity != null || known || stem in neverSplit) break
        }
        return candidates.sortedWith(
            compareByDescending<Analysis> { it.entity != null }
                .thenByDescending { it.stem in knownWords }
                .thenBy { it.prefixes.length }
                .thenByDescending { it.stem.length },
        ).firstOrNull()
    }

    fun prepare(
        original: String,
        protectedTerms: List<String> = emptyList(),
        target: String = "ru",
        observed: Set<String> = emptySet(),
    ): Prepared {
        val fixed = mutableListOf<String>()
        // Treat reviewed/user Hebrew terms (including multiword brands) as entities when prefixed.
        val custom = protectedTerms.filter { term ->
            term.any { it in 'א'..'ת' } && term.all { it in 'א'..'ת' || it in " .-'׳’\"״" }
        }.map {
            HebrewPrefixRules.Entity(it, null, HebrewPrefixRules.EntityType.CUSTOM_PROTECTED)
        }
        val analyzer = if (custom.isEmpty()) this else HebrewPrefixAnalyzer(
            forceSplit, neverSplit, knownWords, custom + entities, confirmedSplits, contextRules,
        )
        val compoundEntities = (custom + entities).filterNot { HEBREW_WORD.matches(it.hebrew) }
            .sortedByDescending { it.hebrew.length }
        val pattern = if (compoundEntities.isEmpty()) HEBREW_WORD else Regex(
            "(?<![\\p{L}\\p{N}\\p{M}_])(?:" +
                compoundEntities.joinToString("|") { "[בלכמוהש]{0,4}" + Regex.escape(it.hebrew) } +
                "|[א-ת]+(?:['׳][א-ת]+)*['׳]?)(?![\\p{L}\\p{N}\\p{M}_])",
        )
        val text = TokenProtector.segments(original, protectedTerms).joinToString("") { segment ->
            if (!segment.translatable) segment.text else pattern.replace(segment.text) { match ->
                val result = analyzer.analyze(match.value, original, observed)
                if (result == null) match.value else {
                    val entity = result.entity
                    // Ordinary grammatical words already carry useful inflection/context for ML
                    // Kit. Candidate analysis alone is not a reason to rewrite natural prose.
                    if (entity == null && match.value !in forceSplit && match.value !in confirmedSplits &&
                        contextRules.none { it.word == match.value && it.pattern.containsMatchIn(original) }
                    ) return@replace match.value
                    val stem = if (target == "ru") entity?.russian ?: result.stem else result.stem
                    if (entity != null) fixed += stem
                    if (result.prefixes.isEmpty()) stem else
                        result.prefixes.map(Char::toString).joinToString(" ") + " " + stem
                }
            }
        }
        return Prepared(text, fixed.distinct())
    }

    /** Observed tokens are weak evidence, never automatic protected entities. */
    fun observedTokens(text: String): Set<String> = TokenProtector.segments(text)
        .filter { it.translatable }.flatMap { HEBREW_WORD.findAll(it.text).map { match -> match.value }.toList() }
        .filter { it.length in 2..40 }.toSet()

    private fun validChain(prefixes: String): Boolean {
        // Optional conjunction, relative particle, preposition, article; no repeats/reordering.
        return CHAIN.matches(prefixes)
    }

    companion object {
        private const val PREFIXES = "בלכמוהש"
        private val CHAIN = Regex("ו?ש?[בלכמ]?ה?")
        // Do not partially parse pointed words or Hebrew inside mixed alphanumeric identifiers.
        private val HEBREW_WORD = Regex("(?<![\\p{L}\\p{N}\\p{M}_])[א-ת]+(?:['׳][א-ת]+)*['׳]?(?![\\p{L}\\p{N}\\p{M}_])")
    }
}
