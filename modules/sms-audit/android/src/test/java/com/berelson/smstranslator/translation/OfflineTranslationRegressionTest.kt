package com.berelson.smstranslator.translation

import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.*
import java.util.concurrent.CancellationException

class OfflineTranslationRegressionTest {
    private val cases = listOf(
        Triple("CN100997761", "החמניה,חורב 15 חיפה", "https://u.cheetahint.com/s13f2uw"),
        Triple("CN100997592", "מיני מרקט יוני,סנש חנה 43 חיפה", "https://u.cheetahint.com/qvhww2d"),
        Triple("AE041391454", "מינימרקט תנובה,הכלניות 2 טירת כרמל", "https://u.cheetahint.com/695besm"),
    )

    private fun sms(c: Triple<String, String, String>) =
        "שלום Alex Example, איזה כיף :), משלוח ${c.first} ממתין לאיסוף ב ${c.second}. לשעות פתיחה, פרטים ואישור איסוף לחץ ${c.third} מומלץ לאסוף תוך 3 ימי עסקים. תודה צ'יטה שליחויות *אין להשיב*"

    @Test fun allThreeDeliveryAddressesAreProtectedAsWholePickupLocations() {
        for (c in cases) {
            val original = sms(c)
            val tokens = TokenProtector.extractedTokens(original)
            assertTrue(tokens.toString(), (TokenProtector.Kind.ADDRESS to c.second) in tokens)
            assertEquals(DomainRuleEngine.Domain.DELIVERY, DomainRuleEngine.detect(original, "CHEETAH"))
            val prepared = OfflineTranslationPreparation.prepare(original, "he", "ru", DomainRuleEngine.Domain.DELIVERY)
            assertTrue(prepared.text.contains("ожидает получения"))
            assertTrue(prepared.text.contains(c.second))
            val output = "Здравствуйте. Посылка${c.first} ожидает получения: ${c.second}. Подробнее: ${c.third} Заберите в течение 3 рабочих дней."
            assertTrue(TranslationValidator.validate(original, output).toString(), TranslationValidator.validate(original, output).valid)
            assertFalse(TranslationValidator.validate(original, output.replace(c.first, c.first + "9")).valid)
            assertFalse(TranslationValidator.validate(original, output.replace(c.third, c.third + "evil")).valid)
            assertFalse(TranslationValidator.validate(original, output.replace(c.second, "Другой адрес")).valid)
        }
    }

    @Test fun normalAndRetryUseSamePreparationAndBothPreserveSourceValues() = sync {
        for (c in cases) for (retry in listOf(false, true)) {
            val source = sms(c)
            val p = OfflineTranslationPreparation.prepare(source, "he", "ru", DomainRuleEngine.Domain.DELIVERY)
            val result = ValidatedOfflineTranslator.translate(source, p.text, p.protectedTerms, p.fixedTerms, retry) { input ->
                // Deterministic fake of ML Kit; unit tests never access a network or device model.
                input.replace("שלום", "Здравствуйте").replace("משלוח", "Посылка")
            }
            assertTrue(result.contains(c.first))
            assertTrue(result.contains(c.second))
            assertTrue(result.contains(c.third))
            assertTrue(result.contains("ожидает получения"))
            assertTrue(TranslationValidator.validate(source, result).valid) // cache read uses this too
        }
    }

    @Test fun brokenMarkersTriggerExactlyOneSafeFallback() = sync {
        var primary = 0
        val result = ValidatedOfflineTranslator.translate("שלום CN100997761 חורב 15 חיפה") { input ->
            if (input.contains('⟦')) { primary++; "ZZZZZZ" } else "Здравствуйте"
        }
        assertEquals(1, primary)
        assertTrue(result.contains("CN100997761"))
        assertTrue(result.contains("חורב 15 חיפה"))
        assertFalse(result.contains("ZZZ"))
    }

    @Test fun badPrimaryAndBadFallbackNeverReturnCacheableOutput() = sync {
        var calls = 0
        var cacheWrites = 0
        try {
            ValidatedOfflineTranslator.translate("שלום 351322") { calls++; "QXQXQX" }
            cacheWrites++
            fail("Must reject both results")
        } catch (_: ValidatedOfflineTranslator.Failure) { }
        assertEquals(2, calls)
        assertEquals(0, cacheWrites)
    }

    @Test fun cancellationIsNeverSwallowedByFallback() = sync {
        var calls = 0
        try {
            ValidatedOfflineTranslator.translate("שלום") { calls++; throw CancellationException() }
            fail("Must cancel")
        } catch (_: CancellationException) { }
        assertEquals(1, calls)
    }

    @Test fun groceryFixAndKnownPhrasesRemainIntact() = sync {
        val source = "🍎 29.90 ₪ לק\"ג- לשון קפואה\n🍎 49.90 ₪ לק\"ג- שוק אווז קפוא\n🍎 2.90 ₪ למארז- עגבניות שרי אדומות"
        val p = OfflineTranslationPreparation.prepare(source, "he", "ru", DomainRuleEngine.Domain.GROCERY)
        val result = ValidatedOfflineTranslator.translate(source, p.text, p.protectedTerms, p.fixedTerms) { it }
        assertTrue(result.contains("замороженный говяжий язык"))
        assertTrue(result.contains("замороженная гусиная ножка"))
        assertTrue(result.contains("красные помидоры черри"))
        assertEquals(2, result.count { it == '\n' })
    }

    @Test fun surnameSurvivesBothTranslationPaths() = sync {
        val source = "למה אתם בוחרים בסמוטריץ?"
        val p = OfflineTranslationPreparation.prepare(source, "he", "ru", null)
        for (retry in listOf(false, true)) {
            val result = ValidatedOfflineTranslator.translate(source, p.text, p.protectedTerms, p.fixedTerms, retry) { it }
            assertTrue(result.contains("Смотрич"))
            assertFalse(result.contains('⟦'))
        }
    }

    @Test fun fullGroceryPromotionStillPassesTheNewPreparationAndValidation() = sync {
        val source = """
            יוניברס  ע - נ – ק ! 🍯 🍎 🍯

            🍎 29.90 ₪ לק"ג- לשון קפואה
            🍎 29.90 ₪ לק"ג- אסאדו קפוא עם עצם
            🍎 49.90 ₪ לק"ג- שוק אווז קפוא
            🍎 89.90 ₪ לק"ג- חזה אווז קפוא
            🍎 89.90 ₪ לק"ג- רבע טלה טרי

            🍎 2.90 ₪ למארז- עגבניות שרי אדומות
            🍎 2.90 ₪ לק"ג- בצל אדום/ כרוב לבן/ גזר ארוז/ בייבי בטטה/ דלורית
            🍎 2.90 ₪ למארז- סלק בוואקום/ שום יבש

            לפרטים ומבצעים נוספים > shufersal.club/46Wdzs8

            בתוקף עד 2.10.2026 או עד גמר המלאי, המוקדם שבהם. מבצע לשון מוגבל ל3 ק"ג.
            אסאדו מותנה בקנייה ב150 ₪ ומוגבלים ל12 ק"ג.
            מבצעי ירקות ופירות בתוקף עד 20.9.26. מבצעי שרי ובצל אדום מותנים בקנייה ב100 ₪ ומוגבלים ל3 ק"ג/ מארזים.
            מבצע שרי חל בסניפים נבחרים, ולא כולל לובלו. מחירם הקודם של המוצרים מוצג בסניף.
            כפוף לתנאי המבצע, למלאי ולמגוון בסניף.
            להסרה שלחו 'הסר' ל- 050-8085055
        """.trimIndent()
        val p = OfflineTranslationPreparation.prepare(source, "he", "ru", DomainRuleEngine.Domain.GROCERY)
        for (retry in listOf(false, true)) {
            val result = ValidatedOfflineTranslator.translate(source, p.text, p.protectedTerms, p.fixedTerms, retry) { it }
            assertTrue(result.contains("замороженный говяжий язык"))
            assertTrue(result.contains("красные помидоры черри"))
            assertTrue(result.contains("за кг"))
            assertTrue(result.contains("за упаковку"))
            assertTrue(result.contains("отправьте 'הסר' на"))
            assertTrue(TranslationValidator.validate(source, result).valid)
            assertEquals(source.count { it == '\n' }, result.count { it == '\n' })
        }
    }

    @Test fun rejectsDuplicatedCodesLinksAndAddressAndIncompleteMarkers() {
        val c = cases.first()
        for (value in listOf(c.first, c.second, c.third)) {
            assertFalse(TranslationValidator.validate(sms(c), sms(c) + " " + value).valid)
        }
        assertFalse(TranslationValidator.validate("שלום", "Привет ⟦").valid)
        val p = TokenProtector.protect("קוד 351322")
        assertNull(TokenProtector.restore(p, "⟦99999999999999999999999999⟧"))
    }

    @Test fun pinnedTechnicalTermsStillRejectExtendedUrlsAndCodes() {
        for (value in listOf("CN100997761", "https://example.com/parcel", "351322")) {
            val source = "שלום $value"
            val terms = OfflineTranslationPreparation.prepare(source, "he", "ru", null).protectedTerms
            assertTrue(TranslationValidator.validate(source, "Здравствуйте $value", terms).valid)
            assertFalse(TranslationValidator.validate(source, "Здравствуйте ${value}9", terms).valid)
        }
    }

    @Test fun failureDiagnosticsContainReasonsButNotMessageContents() = sync {
        try {
            ValidatedOfflineTranslator.translate("שלום Alex Example CN100997761") { "QXQXQX" }
            fail("Must reject unsafe output")
        } catch (failure: ValidatedOfflineTranslator.Failure) {
            assertTrue(failure.stage.contains("primary:marker-mismatch"))
            assertTrue(failure.stage.contains("fallback:prepared-LEGACY_MARKER"))
            assertFalse(failure.stage.contains("Alex"))
            assertFalse(failure.stage.contains("CN100997761"))
        }
    }

    private fun sync(block: suspend () -> Unit) {
        var outcome: Result<Unit>? = null
        block.startCoroutine(object : Continuation<Unit> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { outcome = result }
        })
        requireNotNull(outcome) { "Fake translator must not suspend on external work" }.getOrThrow()
    }
}
