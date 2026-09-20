package com.berelson.smstranslator.translation

import java.util.concurrent.CancellationException

/** Android-independent orchestration: contextual paragraphs and one validated fallback. */
object ValidatedOfflineTranslator {
    class Failure(val stage: String) : IllegalStateException("Unsafe translation: $stage")

    suspend fun translate(
        original: String,
        working: String = original,
        protectedTerms: List<String> = emptyList(),
        fixedTerms: List<String> = emptyList(),
        segmentedFirst: Boolean = false,
        sourceLanguage: String? = null,
        targetLanguage: String? = null,
        translateText: suspend (String) -> String,
    ): String {
        val allTerms = (protectedTerms + fixedTerms).distinct()
        var rejection = "unknown"
        val failures = mutableListOf<String>()
        fun valid(output: String): Boolean {
            val preparedCheck = TranslationValidator.validate(working, output, allTerms)
            val originalCheck = TranslationValidator.validate(original, output, protectedTerms)
            val coverage = TranslationValidator.validateLanguageCoverage(
                working, output, allTerms, sourceLanguage, targetLanguage,
            )
            rejection = when {
                !preparedCheck.valid -> "prepared-${preparedCheck.reason}"
                !originalCheck.valid -> "source-${originalCheck.reason}"
                !coverage.valid -> "language-${coverage.reason}"
                else -> "none"
            }
            return preparedCheck.valid && originalCheck.valid && coverage.valid
        }

        suspend fun chunk(text: String): String {
            if (text.isBlank() || text.none(Char::isLetter)) return text
            val leading = text.takeWhile(Char::isWhitespace)
            val trailing = text.takeLastWhile(Char::isWhitespace)
            val result = translateText(text.substring(leading.length, text.length - trailing.length))
            if (result.isBlank()) throw Failure("empty-fragment")
            return leading + result + trailing
        }

        suspend fun primary(): String? {
            // Keep real paragraph separators out of the model. Each paragraph still goes as a
            // whole, with context; this avoids one enormous marker sequence for long catalogues.
            val paragraphs = Regex("\\r\\n(?:[ \\t]*\\r\\n)+|\\n(?:[ \\t]*\\n)+|\\r(?:[ \\t]*\\r)+")
            val protected = TokenProtector.protect(working, allTerms).tokens
            val breaks = paragraphs.findAll(working).filter { separator ->
                protected.none { it.kind != TokenProtector.Kind.FORMATTING &&
                    it.start < separator.range.last + 1 && separator.range.first < it.endExclusive }
            }.toList()
            val result = StringBuilder()
            var cursor = 0
            for (separator in breaks) {
                val paragraph = primaryParagraph(working.substring(cursor, separator.range.first), allTerms, ::chunk)
                    ?: run { rejection = "marker-mismatch"; return null }
                result.append(paragraph).append(separator.value)
                cursor = separator.range.last + 1
            }
            val tail = primaryParagraph(working.substring(cursor), allTerms, ::chunk)
                ?: run { rejection = "marker-mismatch"; return null }
            result.append(tail)
            return result.toString().takeIf(::valid)
        }

        suspend fun fallback(): String? {
            val result = buildString {
                for (segment in TokenProtector.segments(working, allTerms)) {
                    append(if (segment.translatable) chunk(segment.text) else segment.text)
                }
            }
            return result.takeIf(::valid)
        }

        suspend fun attempt(stage: String, action: suspend () -> String?): String? = try {
            action().also { if (it == null) failures += "$stage:$rejection" }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Failure) {
            failures += "$stage:${failure.stage}"
            null
        } catch (_: Exception) {
            failures += "$stage:translation-error"
            null
        }

        if (segmentedFirst) {
            attempt("fallback") { fallback() }?.let { return it }
            attempt("primary") { primary() }?.let { return it }
        } else {
            attempt("primary") { primary() }?.let { return it }
            attempt("fallback") { fallback() }?.let { return it }
        }
        throw Failure(failures.joinToString(";"))
    }

    private suspend fun primaryParagraph(
        working: String, allTerms: List<String>, chunk: suspend (String) -> String,
    ): String? {
        val plan = TokenProtector.protect(working, allTerms)
        // Marker-only messages have nothing for a language model to translate.
        val encoded = if (TokenProtector.segments(working, allTerms).none {
                it.translatable && it.text.any(Char::isLetter)
            }) plan.encodedText else chunk(plan.encodedText)
        return TokenProtector.restore(plan, encoded)
    }
}
