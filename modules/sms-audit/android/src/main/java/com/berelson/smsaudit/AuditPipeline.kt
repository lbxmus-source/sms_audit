package com.berelson.smsaudit

import com.berelson.smstranslator.translation.*
import java.util.concurrent.CancellationException

/** Findings are triage signals, never a semantic correctness certificate. */
object AuditPipeline {
    const val VERSION = "audit-1.0.0-rules-v4"
    data class Result(
        val rawTranslation: String?,
        val translation: String?,
        val preparedText: String,
        val protectedTerms: List<String>,
        val fixedTerms: List<String>,
        val domain: String,
        val rawFlags: List<String>,
        val flags: List<String>,
        val failure: String?,
    )

    fun hasHebrew(text: String) = text.any { it in 'א'..'ת' }

    fun flags(original: String, output: String?, protected: List<String> = emptyList()): List<String> {
        if (output == null) return listOf("NO_TRANSLATION")
        val flags = mutableListOf<String>()
        if (hasHebrew(original) && original.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' }) flags += "MIXED_HEBREW_RUSSIAN"
        val check = TranslationValidator.validate(original, output)
        if (!check.valid) flags += check.reason?.name ?: "VALIDATION_FAILED"
        val natural = TokenProtector.segments(output, protected).filter { it.translatable }
            .joinToString(" ") { it.text }
        if (hasHebrew(natural)) flags += "HEBREW_REMAINS_REVIEW_NAMES"
        if (hasHebrew(original) && output.none { it in 'А'..'я' || it == 'ё' || it == 'Ё' })
            flags += "NO_RUSSIAN_TEXT"
        if (output.trim() == original.trim() && hasHebrew(original)) flags += "UNCHANGED_TEXT"
        return flags.distinct()
    }

    suspend fun run(original: String, sender: String, translate: suspend (String) -> String): Result {
        val domain = DomainRuleEngine.resolve(original, null, sender)
        val prep = OfflineTranslationPreparation.prepare(original, "he", "ru", domain, sender = sender)
        val raw = try { translate(original) } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
        var failure: String? = null
        val output = try {
            HebrewDeliveryTemplates.translate(original, emptyList())
                ?: ValidatedOfflineTranslator.translate(
                    original, prep.text, prep.protectedTerms, prep.fixedTerms,
                    sourceLanguage = "he", targetLanguage = "ru", translateText = translate,
                )
        } catch (e: CancellationException) { throw e }
          catch (e: ValidatedOfflineTranslator.Failure) { failure = e.stage; null }
          catch (_: Exception) { failure = "MODEL_ERROR"; null }
        return Result(raw, output, prep.text, prep.protectedTerms, prep.fixedTerms,
            domain?.storageKey ?: "auto", flags(original, raw),
            flags(original, output, prep.protectedTerms + prep.fixedTerms), failure)
    }
}
