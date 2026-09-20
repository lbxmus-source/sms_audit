package com.berelson.smstranslator.translation

object TranslationValidator {
    data class Result(val valid: Boolean, val reason: Reason? = null)

    enum class Reason {
        EMPTY,
        INTERNAL_MARKER,
        LEGACY_MARKER,
        REPEATED_GARBAGE,
        PROTECTED_VALUE_CHANGED,
        TECHNICAL_COUNT_CHANGED,
        TECHNICAL_GARBAGE,
        UNTRANSLATED_TEXT,
    }

    fun validate(
        original: String,
        translated: String,
        protectedTerms: List<String> = emptyList(),
    ): Result {
        val safety = validateTextSafety(original, translated)
        if (!safety.valid) return safety

        val expected = TokenProtector.extractedTokens(original, protectedTerms)
            .filter { it.first in trackedKinds }
        // Source-owned values, not a second classification under different Russian neighbours.
        // Consume longest first: #695147 and 695147 are separate source occurrences.
        val consumed = BooleanArray(translated.length)
        for ((kind, value) in expected.sortedByDescending { it.second.length }) {
            // Pinned source values are also supplied as USER_TERM during preparation.
            // That must not hide a URL/code's technical boundary checks.
            val boundaryKind = if (kind == TokenProtector.Kind.USER_TERM) {
                TokenProtector.extractedTokens(value).singleOrNull()
                    ?.takeIf { it.second == value }?.first ?: kind
            } else kind
            var from = 0
            var accepted = false
            while (from <= translated.length - value.length) {
                val start = translated.indexOf(value, from)
                if (start < 0) break
                val end = start + value.length
                if ((start until end).none { consumed[it] } &&
                    intactBoundary(translated, start, end, boundaryKind, value)
                ) {
                    for (i in start until end) consumed[i] = true
                    accepted = true
                    break
                }
                from = start + 1
            }
            if (!accepted) return invalid(Reason.PROTECTED_VALUE_CHANGED)
        }
        // Spaces would invent token boundaries: *<protected phrase>* becomes two standalone
        // technical stars and an innocent translation is rejected. This non-whitespace fence
        // also prevents unrelated digits on opposite sides of a consumed value becoming a phone.
        val remainder = translated.mapIndexed { i, c -> if (consumed[i]) '\uE000' else c }.joinToString("")
        val sourceTerms = expected.filter { it.first == TokenProtector.Kind.USER_TERM }.map { it.second }
        val extras = TokenProtector.extractedTokens(remainder, sourceTerms)
            .filter { it.first in trackedKinds }
        if (extras.isNotEmpty()) return invalid(Reason.TECHNICAL_COUNT_CHANGED)
        for ((_, value) in expected) {
            if (value.length >= 2 && Regex("(?<![\\p{L}\\p{N}_])" + Regex.escape(value) + "(?![\\p{L}\\p{N}_])")
                    .containsMatchIn(remainder)) return invalid(Reason.TECHNICAL_COUNT_CHANGED)
        }
        return Result(valid = true)
    }

    private fun intactBoundary(
        text: String, start: Int, end: Int, kind: TokenProtector.Kind, value: String,
    ): Boolean {
        val before = text.getOrNull(start - 1)
        val after = text.getOrNull(end)
        if (kind == TokenProtector.Kind.USER_TERM) {
            fun wordNeighbour(edge: Char, neighbour: Char?): Boolean {
                if (neighbour == null) return false
                return if (edge in 'A'..'Z' || edge in 'a'..'z') {
                    neighbour in 'A'..'Z' || neighbour in 'a'..'z' || neighbour.isDigit() || neighbour == '_'
                } else neighbour.isLetterOrDigit() || neighbour == '_'
            }
            if (value.firstOrNull()?.isLetterOrDigit() == true &&
                wordNeighbour(value.first(), before)) return false
            if (value.lastOrNull()?.isLetterOrDigit() == true &&
                wordNeighbour(value.last(), after)) return false
        }
        if (kind == TokenProtector.Kind.LINK || kind == TokenProtector.Kind.EMAIL) {
            if (after != null && !after.isWhitespace() && after !in ",;:!?)]}»\"'" &&
                !(after == '.' && (end + 1 == text.length || text[end + 1].isWhitespace()))
            ) return false
        }
        if (kind in setOf(TokenProtector.Kind.CODE, TokenProtector.Kind.PHONE,
                TokenProtector.Kind.AMOUNT, TokenProtector.Kind.PERCENT,
                TokenProtector.Kind.DATE, TokenProtector.Kind.TIME, TokenProtector.Kind.UNIT)) {
            if (value.firstOrNull()?.isDigit() == true && before?.isDigit() == true) return false
            if (value.lastOrNull()?.isDigit() == true && after?.isDigit() == true) return false
            if (kind == TokenProtector.Kind.CODE && value.any { it in 'A'..'Z' || it in 'a'..'z' }) {
                if (before != null && (before in 'A'..'Z' || before in 'a'..'z' || before.isDigit())) return false
                if (after != null && (after in 'A'..'Z' || after in 'a'..'z' || after.isDigit())) return false
            }
        }
        return true
    }

    /** Both paths enforce the same source-owned values; fallback is not a safety bypass. */
    fun validateSegmentedFallback(original: String, translated: String): Result =
        validate(original, translated)

    /** A retained address/name is allowed; an untranslated Hebrew paragraph is not a success. */
    fun validateLanguageCoverage(
        working: String, translated: String, protectedTerms: List<String>,
        source: String?, target: String?,
    ): Result {
        if (source != "he" || target != "ru") return Result(true)
        fun naturalHebrew(text: String): Int = TokenProtector.segments(text, protectedTerms)
            .filter { it.translatable }.sumOf { segment -> segment.text.count { it in 'א'..'ת' } }
        val before = naturalHebrew(working)
        val after = naturalHebrew(translated)
        // Do not reject two unfamiliar proper names merely because they remain Hebrew.
        val paragraphRemains = TokenProtector.segments(translated, protectedTerms)
            .filter { it.translatable }
            .any { Regex("(?:[א-ת]+[ \\t,]+){3,}[א-ת]+").containsMatchIn(it.text) }
        if (before >= 24 && after >= 24 && after * 2 >= before && paragraphRemains) {
            return invalid(Reason.UNTRANSLATED_TEXT)
        }
        return Result(true)
    }

    private fun validateTextSafety(original: String, translated: String): Result {
        if (translated.isBlank() && original.isNotBlank()) return invalid(Reason.EMPTY)
        if (TokenProtector.containsInternalMarker(translated)) return invalid(Reason.INTERNAL_MARKER)
        if (containsNewMatch(legacyMarker, original, translated)) {
            return invalid(Reason.LEGACY_MARKER)
        }
        if (containsNewMatch(repeatedLetter, original, translated) ||
            containsNewMatch(repeatedShortGroup, original, translated)
        ) return invalid(Reason.REPEATED_GARBAGE)

        val originalLetters = original.count(Char::isLetter)
        val originalNonWhitespace = original.count { !it.isWhitespace() }
        val outputNonWhitespace = translated.count { !it.isWhitespace() }
        val outputLetters = translated.count(Char::isLetter)
        if (originalLetters == 0 && outputLetters > 0) {
            return invalid(Reason.TECHNICAL_GARBAGE)
        }
        if (originalLetters > 0 && outputNonWhitespace > 8 &&
            (outputLetters == 0 || outputLetters * 5 < outputNonWhitespace)
        ) return invalid(Reason.TECHNICAL_GARBAGE)
        if (outputNonWhitespace > maxOf(64, originalNonWhitespace * 4)) {
            return invalid(Reason.TECHNICAL_GARBAGE)
        }

        return Result(valid = true)
    }

    private fun invalid(reason: Reason) = Result(valid = false, reason = reason)

    private fun containsNewMatch(pattern: Regex, original: String, translated: String): Boolean {
        val allowed = pattern.findAll(original).map { it.value }.groupingBy { it }.eachCount()
        val found = pattern.findAll(translated).map { it.value }.groupingBy { it }.eachCount()
        return found.any { (value, count) -> count > (allowed[value] ?: 0) }
    }

    private val trackedKinds = setOf(
        TokenProtector.Kind.LINK,
        TokenProtector.Kind.EMAIL,
        TokenProtector.Kind.PHONE,
        TokenProtector.Kind.CODE,
        TokenProtector.Kind.PERCENT,
        TokenProtector.Kind.AMOUNT,
        TokenProtector.Kind.DATE,
        TokenProtector.Kind.TIME,
        TokenProtector.Kind.UNIT,
        TokenProtector.Kind.ADDRESS,
        TokenProtector.Kind.EMOJI,
        TokenProtector.Kind.FORMATTING,
        TokenProtector.Kind.TECHNICAL,
        TokenProtector.Kind.USER_TERM,
    )
    private val legacyMarker = Regex(
        "(?:QX|XQ|ZZZ+|\\bREMOVE\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val repeatedLetter = Regex("([\\p{L}])\\1{5,}")
    private val repeatedShortGroup = Regex("([\\p{L}\\p{N}]{1,3})\\1{4,}")
}
