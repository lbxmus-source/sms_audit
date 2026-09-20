package com.berelson.smstranslator.translation

import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.*

class ReusableRulesTest {
    private fun prepare(text: String, sender: String = "") = OfflineTranslationPreparation.prepare(
        text, "he", "ru", DomainRuleEngine.detect(text), sender = sender,
    )

    @Test fun accountPhraseWorksAcrossSpellingsSendersAndCodes() {
        for (area in listOf("אזור", "איזור")) for (code in listOf("25875", "71942")) {
            val original = "קוד אימות להתחברות ל${area} האישי בגולן טלקום: $code"
            val p = prepare(original, "Golan T")
            assertTrue(p.text, p.text.contains("Код подтверждения для входа в личный кабинет"))
            assertTrue(p.text, p.text.contains("גולן טלקום"))
            assertTrue(p.text, TranslationValidator.validate(original, p.text, p.protectedTerms).valid)
            assertTrue(p.text.endsWith(code))
        }
        assertEquals("в личном кабинете", prepare("באיזור האישי").text)
        assertEquals("вход в личный кабинет", prepare("כניסה לאזור אישי").text)
        assertEquals("האזור התעשייתי", prepare("האזור התעשייתי").text)
    }

    @Test fun brandsAreNotGuessedFromOrdinaryShortSenderWords() {
        assertTrue(BrandProtector.terms("call Cal now", "Other").isEmpty())
        assertTrue(BrandProtector.terms("enter code now", "code").isEmpty())
        assertEquals(listOf("New Shop"), BrandProtector.terms("New Shop offers", "New Shop"))
        assertTrue(BrandProtector.terms("New Shopper", "New Shop").isEmpty())
        assertTrue(BrandProtector.terms("AFreeDayB", "Mishloha").isEmpty())
        for (sender in listOf("YDeli", "ydeli", "Yango Deli", "Unknown")) {
            val p = prepare("יאנגו דלי עם מבצע מתוק. קוד קופון: NEXT22", sender)
            assertTrue(p.text.contains("יאנגו דלי"))
            assertTrue((p.protectedTerms + p.fixedTerms).contains("יאנגו דלי"))
        }
        val p = prepare("בביג אלקטריק וביג אלקטריק יש מבצע", "BIGELECTRIC")
        assertTrue(p.text, p.text.contains("ביג אלקטריק"))
        assertTrue(p.text, TranslationValidator.validate("בביג אלקטריק וביג אלקטריק יש מבצע", p.text, p.protectedTerms).valid)
    }

    @Test fun latinPromotionIsPreservedEvenWithoutHebrewSpace() {
        for (name in listOf("FreeDay", "freeDay", "FREEDAY")) {
            val source = "חמישי ${name}פה, אתם יודעים מה זה אומר. מזמינים היום ודמי המשלוח חוזרים לארנק. תקף במסעדות המשתתפות"
            val p = prepare(source, "Mishloha")
            val encoded = TokenProtector.protect(p.text, p.protectedTerms + p.fixedTerms).encodedText
            assertFalse(encoded.contains(name))
            assertTrue(p.text.contains("стоимость доставки возвращается в кошелёк"))
            assertTrue(TranslationValidator.validate(source, p.text, p.protectedTerms).valid)
        }
    }

    @Test fun variableAmountsAndDiscountsAreTypedNotHardcoded() {
        for (amount in listOf("1₪", "45₪", "79.90 ₪", "₪99")) {
            for (prefix in listOf("ב", "ב-", "ב־")) {
                val source = "משלוח $prefix$amount"
                val p = prepare(source)
                assertEquals(source, "доставка за $amount", p.text)
                assertTrue(TranslationValidator.validate(source, p.text, p.protectedTerms).valid)
                assertFalse(TranslationValidator.validate(source, p.text.replace(amount, "999 ₪"), p.protectedTerms).valid)
            }
        }
        for (percent in listOf("10%", "25%")) {
            val p = prepare("עד $percent הנחה בהזנת קוד קופון SALE22")
            assertTrue(p.text, p.text.contains("скидка до $percent"))
            assertTrue(p.text.endsWith("SALE22"))
            assertTrue(p.text, p.text.contains("при вводе промокода"))
        }
        assertEquals("משלוח ב-AB123", prepare("משלוח ב-AB123").text)
    }

    @Test fun elongatedSloganAndAccountCasesRemainConservative() {
        for (elongation in listOf("ו", "וו", "וווווו")) {
            assertEquals("Спросите кого угодно...", prepare("תשאלו את כ${elongation}לם...").text)
        }
        assertEquals("כוווונה", prepare("כוווונה").text)
        assertEquals("из личного кабинета", prepare("מהאזור האישי").text)
        val text = "משלוח ב-45₪"
        assertEquals(text, OfflineTranslationPreparation.prepare(text, "he", "ru", null, listOf(text)).text)
        val plan = TokenProtector.protect("FreeDayפה", listOf("FreeDay"))
        assertEquals("FreeDay здесь", TokenProtector.restore(plan, "⟦0⟧здесь"))
        val code = TokenProtector.protect("AB123", listOf("AB123"))
        assertEquals("AB123", TokenProtector.restore(code, code.encodedText))
    }

    @Test fun catalogueAndDeliKeepEveryTechnicalValue() {
        for ((sender, source) in fixtures) {
            val p = prepare(source, sender)
            val result = TranslationValidator.validate(source, p.text, p.protectedTerms)
            assertTrue("$sender: $result\n${p.text}", result.valid)
            val plan = TokenProtector.protect(p.text, p.protectedTerms + p.fixedTerms)
            assertEquals(p.text, TokenProtector.restore(plan, plan.encodedText))
            if (sender == "Cal") assertTrue(p.text, p.text.startsWith("Амфитеатр Тель-Авив: клиенты Cal первыми"))
            if (sender == "YDeli") {
                assertTrue(p.text, p.text.contains("со сладкой акцией"))
                assertTrue(p.text, p.text.contains("при покупке на сумму 45₪"))
            }
        }
    }

    @Test fun bilingualSmsKeepsExistingRussianAndSelectsHebrewForTheRest() = sync {
        val russian = "В данный момент по вашей карте 3843 проходит оплата на сайте IHERB.COM на сумму 444.19.\nЕсли вы не совершали эту покупку, срочно позвоните по телефону 03-6177750.\nКод подтверждения 109955"
        val original = "להעברת תשלום בסך 444.19 שקל ישראלי מכרטיס 3843 יש להקליד את הקוד באתר. קוד האימות הוא 109955\nתרגום לרוסית:\n$russian\n@max.co.il #109955"
        assertEquals("he", LanguageScriptHeuristics.strongLanguageHint(original))
        val p = prepare(original, "max code")
        val modelInputs = mutableListOf<String>()
        val result = ValidatedOfflineTranslator.translate(original, p.text, p.protectedTerms, p.fixedTerms, false, "he", "ru") {
            modelInputs += it
            it.replace(Regex("[א-ת]+"), "перевод")
        }
        assertTrue(result, result.contains(russian))
        assertTrue(modelInputs.none { it.contains("В данный момент") || it.contains("Код подтверждения") })
        assertFalse(TranslationValidator.validate(original, result.replace("109955", "109956"), p.protectedTerms).valid)
        assertNotEquals("he", LanguageScriptHeuristics.strongLanguageHint(russian))
    }

    @Test fun paragraphsAndFallbackDoNotLoseTheRestOfALongMessage() = sync {
        val original = "שלום ראשון AB123\n\nשלום שני 45₪\n\nשלום שלישי https://example.test/path?a=1"
        val inputs = mutableListOf<String>()
        val result = ValidatedOfflineTranslator.translate(original, sourceLanguage = "he", targetLanguage = "ru") {
            inputs += it
            it.replace("שלום ראשון", "Первый абзац").replace("שלום שני", "Второй абзац").replace("שלום שלישי", "Третий абзац")
        }
        assertEquals(3, inputs.size)
        assertTrue(inputs.none { it.contains("\n\n") })
        assertEquals("Первый абзац AB123\n\nВторой абзац 45₪\n\nТретий абзац https://example.test/path?a=1", result)
        var calls = 0
        val fallback = ValidatedOfflineTranslator.translate(original, sourceLanguage = "he", targetLanguage = "ru") {
            calls++
            if (it.contains('⟦')) "Ошибка маркера" else it.replace(Regex("[א-ת]+"), "перевод")
        }
        assertTrue(calls > 1)
        assertTrue(TranslationValidator.validate(original, fallback).valid)
        assertTrue(fallback.contains("AB123") && fallback.contains("45₪") && fallback.contains("https://example.test/path?a=1"))
    }

    private fun sync(block: suspend () -> Unit) {
        var outcome: Result<Unit>? = null
        block.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { outcome = result }
        })
        requireNotNull(outcome).getOrThrow()
    }

    private val fixtures = listOf(
        "BIGELECTRIC" to """
            אלכס חגיגת המבצעים לחגים בביג אלקטריק בעיצומה 💥

            מכונת קפה פיליפס EP2224 רק 1,079 ₪ ובנוסף 200 ₪ תווי קנייה לרשת FOX HOME מתנה!

            עמדת טעינה ניידת לרכב תדיראן לשקע ביתי 3.5kW רק 489 ₪

            מכונת כביסה 6 ק"ג GALANZ GL6 WMA7W רק 575 ₪

            מאוורר תקרה BRIZA 48" 3525W כולל תאורה רק 299 ₪

            קונים ממגוון מוצרי Insta360 ומקבלים עד 600 ₪ תווי קנייה לרשת FOX HOME

            מזון לכלבים ולחתולים במחירים מטורפים ובנוסף 10% הנחה בהקלדת קוד קופון PET10 ועוד https://example.test/offers
            עד 30.9.26 או עד גמר המלאי, המוקדם מביניהם. הסר https://example.test/stop
        """.trimIndent(),
        "YDeli" to """
            יאנגו דלי עם מבצע מתוק: משלוח ב-1₪ 🍯
            רוכשים ב-45₪ ממגוון מוצרי יד מרדכי המשתתפים במבצע ומקבלים משלוח ב-1₪.
            קוד קופון: YADM1NIS

            יאללה שלא יימרח, להזמנה https://example.test/EhEb2-VUK2wFp5WEX <<
            בתוקף עד 22.9.26 או עד גמר המלאי, המוקדם מביניהם.
            בכפוף לתנאי השימוש. להסרה שלחו הסר למספר 0537000000
        """.trimIndent(),
        "Cal" to """
            אמפי תל אביב- כאל לפני כולם!
            ההנחה הכי גדולה לכרטיסים למופעים הכי חמים: אביב גפן, שב"ק ס, סאבלימינל והצל וגם לאונג' VIP משודרג הכולל אוכל, שתייה וכניסה נפרדת במחיר מיוחד ובלעדי ללקוחות כאל! מהרו להזמין, המלאי מוגבל:
            https://example.test/mobile?mainLinkName=AmphiTLV_C&ts=cal&tm=sms&tc=
            תמורת חוויה. כפוף לתנאים. להסרה יש להשיב 1 למספר 055-7000000
        """.trimIndent(),
        "ALM" to "תשאלו את כווווולם... שעות אחרונות למבצע החם באתר א.ל.מ! 🔥 מגוון ענק של מוצרים מובילים עד 10% הנחה בהזנת קוד קופון. מלאי מוגבל ⏳✅ אל תפספסו! לרכישה: https://example.test/offer לא ניתן להשיב להודעה זו. בכפוף לתקנון (פרסומת) להסרה, השיבו \"הסר\" למספר 0559000000",
    )
}
