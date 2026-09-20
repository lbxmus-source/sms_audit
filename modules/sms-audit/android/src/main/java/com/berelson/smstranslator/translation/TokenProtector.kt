package com.berelson.smstranslator.translation

/** Protects operational data while keeping the surrounding sentence intact. */
object TokenProtector {
    enum class Kind {
        LINK, EMAIL, PHONE, CODE, PERCENT, AMOUNT, DATE, TIME, UNIT,
        ADDRESS, EMOJI, FORMATTING, TECHNICAL, USER_TERM,
    }

    data class ProtectedToken(
        val original: String,
        val kind: Kind,
        val start: Int,
        val endExclusive: Int,
        val markerIndex: Int,
    ) {
        val marker: String get() = TokenProtector.markerFor(markerIndex)
    }

    data class ProtectionPlan(
        val original: String,
        val encodedText: String,
        val tokens: List<ProtectedToken>,
    )

    data class Segment(val text: String, val translatable: Boolean)

    private val fixedPatterns = listOf(
        pattern("\\r\\n|\\n|\\r", Kind.FORMATTING),
        pattern("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Kind.EMAIL, true),
        pattern(
            "(?<![\\p{L}\\p{N}])@[A-Z0-9.-]+\\.[A-Z]{2,}(?:/[^\\s<>()\\[\\]{}]+(?<![.,;:!?]))?",
            Kind.LINK,
            true,
        ),
        pattern(
            "https?://[^\\s<>()\\[\\]{}]+(?<![.,;:!?])|www\\.[^\\s<>()\\[\\]{}]+(?<![.,;:!?])",
            Kind.LINK,
            true,
        ),
        pattern(
            "(?<![\\p{L}\\p{N}@])(?:[A-Z0-9-]+\\.)+[A-Z]{2,}(?:/[^\\s<>()\\[\\]{}]+(?<![.,;:!?]))?",
            Kind.LINK,
            true,
        ),
        pattern(
            "(?<![\\p{L}\\p{N}])(?:\\+?\\d[\\d ()-]{6,}\\d)(?![\\p{L}\\p{N}])",
            Kind.PHONE,
        ),
        pattern(
            "(?<![\\p{L}\\p{N}])(?:\\d{4}[./-]\\d{1,2}[./-]\\d{1,2}|\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4})(?![\\p{L}\\p{N}])",
            Kind.DATE,
        ),
        pattern(
            "(?<![A-Za-z0-9/])(?:0?[1-9]|[12][0-9]|3[01])/(?:0?[1-9]|1[0-2])(?![A-Za-z0-9/])",
            Kind.DATE,
        ),
        pattern(
            "(?<![\\p{L}\\p{N}])\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s?(?:AM|PM))?(?![\\p{L}\\p{N}])",
            Kind.TIME,
            true,
        ),
        pattern(
            "(?<![\\p{L}\\p{N}])(?:[%٪‰]\\s?[-+]?\\d+(?:[.,]\\d+)?|[-+]?\\d+(?:[.,]\\d+)?\\s?[%٪‰])(?![\\p{L}\\p{N}])",
            Kind.PERCENT,
        ),
        pattern(
            "(?<![A-Za-z0-9_])(?:[$€£₪¥₽₹]\\s?\\d(?:[\\d,.]*\\d)?|(?:USD|EUR|ILS|NIS|RUB|GBP|CAD|AUD)\\s?\\d(?:[\\d,.]*\\d)?|\\d(?:[\\d,.]*\\d)?\\s?(?:[$€£₪¥₽₹]|USD|EUR|ILS|NIS|RUB|GBP|CAD|AUD))(?![A-Za-z0-9_])",
            Kind.AMOUNT,
            true,
        ),
        pattern(
            "(?<![\\p{L}\\p{N}])[-+]?\\d+(?:[.,]\\d+)?\\s?(?:KB|MB|GB|TB|G|KG|MG|ML|CM|MM|KM|M|°C|°F|V|W|KW|KWH|HZ|MHZ|GHZ)(?![\\p{L}\\p{N}])",
            Kind.UNIT,
            true,
        ),
        pattern(
            "(?<![\\p{L}\\p{N}])(?:address|delivery\\s+address|street|адрес|улица|доставка\\s+по\\s+адресу|כתובת|רחוב|מען|عنوان|شارع)\\s*[:：-]?\\s+[\\p{L}\\p{M}'’״׳.-]{2,30}(?:\\s+[\\p{L}\\p{M}'’״׳.-]{2,30}){0,2}\\s+\\d{1,4}[A-Za-zא-ת]?(?:\\s*[,،]\\s*|\\s+)(?:חיפה|ירושלים|תל\\s+אביב(?:-יפו)?|באר\\s+שבע|אשדוד|אשקלון|נתניה|הרצליה|רמת\\s+גן|פתח\\s+תקווה|ראשון\\s+לציון|Haifa|Jerusalem|Tel\\s+Aviv|Beer\\s+Sheva)(?![\\p{L}\\p{N}])",
            Kind.ADDRESS,
            true,
        ),
        pattern(
            "(?<![\\p{L}\\p{N}])(?:address|delivery\\s+address|street|адрес|улица|доставка\\s+по\\s+адресу|כתובת|רחוב|מען|عنوان|شارع)\\s*[:：-]?\\s+[\\p{L}\\p{M}'’״׳.-]{2,30}(?:\\s+[\\p{L}\\p{M}'’״׳.-]{2,30}){0,1}\\s+\\d{1,4}[A-Za-zא-ת]?(?![\\p{L}\\p{N}])",
            Kind.ADDRESS,
            true,
        ),
        pattern(
            "(?<![\\p{L}\\p{N}])(?:רח(?:וב)?|שד(?:רות)?|דרך|ул(?:ица)?\\.?|проспект|street|st\\.?|road|rd\\.?|avenue|ave\\.?|boulevard|blvd\\.?)\\s+[\\p{L}\\p{M}'’״׳.-]{2,30}(?:\\s+[\\p{L}\\p{M}'’״׳.-]{2,30}){0,3}\\s+\\d{1,4}[A-Za-zא-ת]?(?![\\p{L}\\p{N}])",
            Kind.ADDRESS,
            true,
        ),
        pattern(
            "(?<![\\p{L}\\p{N}])[\\p{L}\\p{M}'’״׳.-]{2,30}\\s+\\d{1,4}[A-Za-zא-ת]?\\s+(?:חיפה|ירושלים|תל\\s+אביב(?:-יפו)?|באר\\s+שבע|אשדוד|אשקלון|נתניה|הרצליה|רמת\\s+גן|פתח\\s+תקווה|ראשון\\s+לציון|Haifa|Jerusalem|Tel\\s+Aviv|Beer\\s+Sheva)(?![\\p{L}\\p{N}])",
            Kind.ADDRESS,
            true,
        ),
        pattern(
            "(?<![\\p{L}\\p{N}])[#№]\\s?[\\p{L}\\p{N}][\\p{L}\\p{N}._/-]*(?![\\p{L}\\p{N}])",
            Kind.CODE,
        ),
        pattern(
            "(?<![\\p{L}\\p{N}])\\d{2,}(?:[-_/]\\d{2,})+(?![\\p{L}\\p{N}])",
            Kind.CODE,
        ),
        pattern(
            "(?<![\\p{L}\\p{N}])[-+]?\\d+[.,]\\d+(?![\\p{L}\\p{N}])",
            Kind.CODE,
        ),
        pattern(
            "(?<![\\p{L}\\p{N}])\\d{3,12}(?![\\p{L}\\p{N}])",
            Kind.CODE,
        ),
        pattern(
            "(?<![\\p{L}\\p{N}])(?=[\\p{L}\\p{N}_/-]{4,})(?=[\\p{L}\\p{N}_/-]*[\\p{L}])(?=[\\p{L}\\p{N}_/-]*\\d)[\\p{L}\\p{N}_/-]+(?![\\p{L}\\p{N}])",
            Kind.CODE,
        ),
        // Short values (3 business days, reply 1) matter just as much as long OTPs.
        pattern("(?<!\\d)\\d+(?!\\d)", Kind.CODE),
        pattern("[©®™✓✔✕✖★☆●◆◇■□▲△▼▽→←↔↑↓∞±×÷]+", Kind.TECHNICAL),
        pattern("(?<!\\S)[@&*~^|=+](?!\\S)", Kind.TECHNICAL),
    )

    fun protect(input: String, protectedTerms: List<String> = emptyList()): ProtectionPlan {
        if (input.isEmpty()) return ProtectionPlan(input, input, emptyList())
        val matches = allMatches(input, protectedTerms)
        if (matches.isEmpty()) return ProtectionPlan(input, input, emptyList())

        val output = StringBuilder(input.length)
        val tokens = mutableListOf<ProtectedToken>()
        var cursor = 0
        var markerIndex = 0
        matches.forEach { match ->
            if (cursor < match.start) output.append(input, cursor, match.start)
            while (input.contains(markerFor(markerIndex))) markerIndex++
            val token = ProtectedToken(
                match.value,
                match.kind,
                match.start,
                match.endExclusive,
                markerIndex,
            )
            tokens += token
            output.append(token.marker)
            markerIndex++
            cursor = match.endExclusive
        }
        if (cursor < input.length) output.append(input, cursor, input.length)
        return ProtectionPlan(input, output.toString(), tokens)
    }

    /** Returns null if ML Kit removed, duplicated, or exposed a marker. */
    fun restore(plan: ProtectionPlan, translatedWithMarkers: String): String? {
        val found = markerPattern.findAll(translatedWithMarkers).toList()
        val indices = found.map { it.groupValues[1].toIntOrNull() ?: return null }
        if (indices.size != plan.tokens.size || indices.toSet() != plan.tokens.map { it.markerIndex }.toSet()) return null
        val originals = plan.tokens.associateBy { it.markerIndex }
        val restored = buildString {
            var cursor = 0
            var previous: ProtectedToken? = null
            fun separatedInSource(token: ProtectedToken, before: Boolean): Boolean {
                val neighbour = plan.original.getOrNull(if (before) token.start - 1 else token.endExclusive)
                val edge = if (before) token.original.firstOrNull() else token.original.lastOrNull()
                // FreeDayפה is a Latin brand followed by Hebrew prose, not one word.
                val scriptBoundary = token.kind == Kind.USER_TERM && edge != null &&
                    (edge in 'A'..'Z' || edge in 'a'..'z') && neighbour != null && neighbour in 'א'..'ת'
                return scriptBoundary || (neighbour != null && !neighbour.isLetterOrDigit() && neighbour != '_')
            }
            fun appendPlain(text: String) {
                if (previous?.let { separatedInSource(it, false) } == true &&
                    lastOrNull()?.isLetterOrDigit() == true && text.firstOrNull()?.isLetterOrDigit() == true
                ) append(' ')
                append(text)
            }
            for (match in found) {
                appendPlain(translatedWithMarkers.substring(cursor, match.range.first))
                val token = originals.getValue(match.groupValues[1].toInt())
                if (separatedInSource(token, true) && lastOrNull()?.isLetterOrDigit() == true &&
                    token.original.firstOrNull()?.isLetterOrDigit() == true
                ) append(' ')
                append(token.original)
                previous = token
                cursor = match.range.last + 1
            }
            appendPlain(translatedWithMarkers.substring(cursor))
        }
        return restored.takeUnless(::containsInternalMarker)
    }

    /** Ranges styled as technical data; links and e-mail remain link-styled. */
    fun technicalRanges(input: String): List<IntRange> = fixedMatches(input)
        .filter { it.kind in highlightedKinds }
        .flatMap { match ->
            if (match.kind == Kind.CODE) digitRanges(input, match.start, match.endExclusive)
            else listOf(match.start until match.endExclusive)
        }

    /** Used only by the safe fallback after marker translation fails validation. */
    fun segments(input: String, protectedTerms: List<String> = emptyList()): List<Segment> {
        if (input.isEmpty()) return emptyList()
        val matches = allMatches(input, protectedTerms)
        if (matches.isEmpty()) return listOf(Segment(input, true))
        val result = mutableListOf<Segment>()
        var cursor = 0
        matches.forEach { match ->
            if (cursor < match.start) addSegment(result, input.substring(cursor, match.start), true)
            addSegment(result, match.value, false)
            cursor = match.endExclusive
        }
        if (cursor < input.length) addSegment(result, input.substring(cursor), true)
        return result
    }

    fun extractedTokens(
        input: String,
        protectedTerms: List<String> = emptyList(),
    ): List<Pair<Kind, String>> = allMatches(input, protectedTerms).map { it.kind to it.value }

    fun containsInternalMarker(input: String): Boolean = input.any { it == '⟦' || it == '⟧' } ||
        Regex("__PROTECTED_[A-Z_]*[0-9]+__", RegexOption.IGNORE_CASE).containsMatchIn(input)

    private fun allMatches(input: String, protectedTerms: List<String>): List<ProtectedMatch> {
        val termPatterns = protectedTerms
            .map(String::trim)
            .filter(String::isNotBlank)
            .sortedByDescending(String::length)
            .map { ProtectedPattern(termPattern(it), Kind.USER_TERM) }
        return acceptedMatches(input, termPatterns + fixedPatterns, includeEmoji = true)
    }

    private fun termPattern(term: String): Regex {
        val beginsWithWord = term.firstOrNull()?.isLetterOrDigit() == true
        val endsWithWord = term.lastOrNull()?.isLetterOrDigit() == true
        fun boundary(c: Char) = if (c in 'A'..'Z' || c in 'a'..'z') "[A-Za-z0-9_]" else "[\\p{L}\\p{N}_]"
        val prefix = if (beginsWithWord) "(?<!${boundary(term.first())})" else ""
        val suffix = if (endsWithWord) "(?!${boundary(term.last())})" else ""
        return Regex(prefix + Regex.escape(term) + suffix, RegexOption.IGNORE_CASE)
    }

    private fun fixedMatches(input: String): List<ProtectedMatch> =
        acceptedMatches(input, fixedPatterns, includeEmoji = true)

    private fun acceptedMatches(
        input: String,
        patterns: List<ProtectedPattern>,
        includeEmoji: Boolean,
    ): List<ProtectedMatch> {
        val patternMatches = patterns.flatMap { protectedPattern ->
            protectedPattern.regex.findAll(input).filterNot { match ->
                // Hebrew ל- before a telephone is prose, not part of an alphanumeric code.
                protectedPattern.kind == Kind.CODE && hebrewNumericPrefix.matches(match.value)
            }.map { match ->
                // The label is prose; only the address itself must remain unchanged.
                val labelLength = if (protectedPattern.kind == Kind.ADDRESS)
                    addressLabel.find(match.value)?.value?.length ?: 0 else 0
                ProtectedMatch(
                    match.range.first + labelLength,
                    match.range.last + 1,
                    match.value.drop(labelLength),
                    protectedPattern.kind,
                )
            }
        }
        val candidates = patternMatches + pickupAddresses(input) +
            if (includeEmoji) emojiMatches(input) else emptyList()
        return candidates.sortedWith(
            compareBy<ProtectedMatch> { it.start }.thenByDescending { it.endExclusive - it.start }
        ).fold(mutableListOf()) { accepted, candidate ->
            if (accepted.none { rangesOverlap(it, candidate) }) accepted += candidate
            accepted
        }
    }

    private fun pickupAddresses(input: String): List<ProtectedMatch> = pickupAddress.findAll(input)
        .map { match ->
            val address = match.groups[1]!!
            ProtectedMatch(address.range.first, address.range.last + 1, address.value, Kind.ADDRESS)
        }.toList()

    // Explicit pickup instruction + place, street/house/city, not arbitrary word+number.
    private val pickupAddress = Regex(
        "(?:ממתין|ממתינה)\\s+לאיסוף\\s+ב\\s*([^\\r\\n.:]{2,100}?[,،]\\s*[^\\r\\n.,:]{2,60}?\\s+\\d{1,4}\\s+[^\\r\\n.:]{2,40}?)(?=\\.|\\s+לשעות)",
    )
    private val addressLabel = Regex(
        "^(?:כתובת|מען|address|delivery\\s+address|адрес|доставка\\s+по\\s+адресу|عنوان)\\s*[:：-]?\\s+",
        RegexOption.IGNORE_CASE,
    )
    private val hebrewNumericPrefix = Regex("[בלכמ](?:-)?\\d[\\d./-]*")

    private fun emojiMatches(input: String): List<ProtectedMatch> {
        val result = mutableListOf<ProtectedMatch>()
        var index = 0
        while (index < input.length) {
            val codePoint = Character.codePointAt(input, index)
            if (!isEmoji(codePoint)) {
                index += Character.charCount(codePoint)
                continue
            }
            val start = index
            index += Character.charCount(codePoint)
            while (index < input.length) {
                val next = Character.codePointAt(input, index)
                when {
                    next == VARIATION_SELECTOR || next in SKIN_TONE_RANGE ->
                        index += Character.charCount(next)
                    next == ZERO_WIDTH_JOINER -> {
                        val joinedIndex = index + Character.charCount(next)
                        if (joinedIndex >= input.length) break
                        val joined = Character.codePointAt(input, joinedIndex)
                        if (!isEmoji(joined)) break
                        index = joinedIndex + Character.charCount(joined)
                    }
                    isEmoji(next) -> index += Character.charCount(next)
                    else -> break
                }
            }
            result += ProtectedMatch(start, index, input.substring(start, index), Kind.EMOJI)
        }
        return result
    }

    private fun isEmoji(codePoint: Int): Boolean =
        codePoint in EMOJI_RANGE || codePoint in DINGBAT_RANGE

    private fun addSegment(target: MutableList<Segment>, text: String, translatable: Boolean) {
        if (text.isEmpty()) return
        val previous = target.lastOrNull()
        if (previous != null && previous.translatable == translatable) {
            target[target.lastIndex] = previous.copy(text = previous.text + text)
        } else {
            target += Segment(text, translatable)
        }
    }

    private fun digitRanges(input: String, start: Int, endExclusive: Int): List<IntRange> {
        val result = mutableListOf<IntRange>()
        var cursor = start
        while (cursor < endExclusive) {
            while (cursor < endExclusive && !input[cursor].isDigit()) cursor++
            val digitStart = cursor
            while (cursor < endExclusive && input[cursor].isDigit()) cursor++
            if (digitStart < cursor) result += digitStart until cursor
        }
        return result
    }

    private fun rangesOverlap(left: ProtectedMatch, right: ProtectedMatch): Boolean =
        left.start < right.endExclusive && right.start < left.endExclusive

    private fun pattern(source: String, kind: Kind, ignoreCase: Boolean = false) =
        ProtectedPattern(
            Regex(source, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()),
            kind,
        )

    private data class ProtectedPattern(val regex: Regex, val kind: Kind)
    private data class ProtectedMatch(
        val start: Int,
        val endExclusive: Int,
        val value: String,
        val kind: Kind,
    )

    private val highlightedKinds = setOf(
        Kind.PHONE, Kind.CODE, Kind.PERCENT, Kind.AMOUNT,
        Kind.DATE, Kind.TIME, Kind.UNIT, Kind.TECHNICAL,
    )
    private val markerPattern = Regex("⟦\\s*(\\d+)\\s*⟧")
    private fun markerFor(index: Int): String = "⟦$index⟧"
    private val EMOJI_RANGE = 0x1F000..0x1FAFF
    private val DINGBAT_RANGE = 0x2600..0x27BF
    private val SKIN_TONE_RANGE = 0x1F3FB..0x1F3FF
    private const val VARIATION_SELECTOR = 0xFE0F
    private const val ZERO_WIDTH_JOINER = 0x200D
}
