package com.berelson.smstranslator.translation

import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.*

class ReviewedSmsTest {
    private fun prepared(text: String, terms: List<String> = emptyList()) =
        OfflineTranslationPreparation.prepare(text, "he", "ru", DomainRuleEngine.detect(text), terms)

    @Test fun stopMarketBrandAndGiftMeaningSurviveWithoutAStoreSenderWhitelist() = sync {
        val source = "מתנת חג מסטופמרקט 🎁 https://ticket.stopmarket.co.il/gift.html?g/c0bbf5"
        val p = prepared(source)
        assertTrue(p.text.contains("Подарок к празднику от סטופמרקט"))
        val result = ValidatedOfflineTranslator.translate(source, p.text, p.protectedTerms, p.fixedTerms, false, "he", "ru") { it }
        assertEquals(p.text, result)
        assertTrue(result.contains("https://ticket.stopmarket.co.il/gift.html?g/c0bbf5"))
        assertEquals("מ סטופמרקט", HebrewPrefixAnalyzer().prepare("מסטופמרקט").text)
    }

    @Test fun haifaResidentsAndSloganArePhrasesWithCorrectCase() = sync {
        val source = "תושבי חיפה, כאן בנט.\n\nביקרתי בשוק תלפיות,\nופגשתי תומך בן גביר שרוצה שהמדינה שלנו תתחיל להיות מנוהלת.\nתקשיבו לשיחה: https://bnt1.net/T8N8Kc\n\nתושבי חיפה, רק ביחד נתקן!"
        val p = prepared(source)
        assertTrue(p.text.startsWith("Жители Хайфы, это Беннет."))
        assertTrue(p.text.contains("сторонника Бен-Гвира"))
        assertTrue(p.text.contains("Только вместе мы сможем это исправить"))
        val result = ValidatedOfflineTranslator.translate(source, p.text, p.protectedTerms, p.fixedTerms, false, "he", "ru") { it }
        assertEquals(source.count { it == '\n' }, result.count { it == '\n' })
        assertFalse(result.contains('⟦'))
    }

    @Test fun ticketsAreNotLightningFlashesOrBankPayments() {
        val source = "⚡ מבזק קופות ת\"א ⚡\nרוצים כרטיס זוגי במתנה להופעה המדוברת של השנה?\nהטבה בלעדית ומוגבלת ללקוחות הבנקים בישראל מבית קופות ת\"א!\n👇 היכנסו עכשיו לבדיקת הזכאות שלכם:\nhttps://nexttelavivo.com 🎟️✨\nמשהו ענק הולך לקרות, אל תישארו מאחור."
        val p = prepared(source)
        assertTrue(p.text.contains("Новости билетных касс Тель-Авива"))
        assertTrue(p.text.contains("билет на двоих"))
        assertTrue(p.text.contains("Проверьте сейчас, доступно ли вам предложение"))
        assertFalse(HebrewPhraseRules.forText("מבזק על מחלת הבזק בבנק").any { it.first == "מבזק" })
        assertEquals("מבזק על מחלת הבזק בבנק", prepared("מבזק על מחלת הבזק בבנק").text)
    }

    @Test fun amphiAndArtistNamesAreContextualAndNoDictionaryRewritesUrls() {
        val source = "אמפי תל אביב- כאל לפני כולם! ההנחה הכי גדולה לכרטיסים למופעים הכי חמים: אביב גפן, שב\"ק ס, סאבלימינל והצל וגם לאונג' VIP משלמה ארצי ועד אייל גולן, כולל אוכל, שתייה וכניסה נפרדת במחיר מיוחד ובלעדי ללקוחות כאל! מהרו להזמין,המלאי מוגבל. https://dl.cal-online.co.il/mobile?mainLinkName=AmphiTLV_C&ts=cal&tm=sms&tc="
        val p = prepared(source)
        assertTrue(p.text.startsWith("Амфитеатр Тель-Авив: клиенты Cal первыми"))
        assertTrue(p.text.contains("שב\"ק ס"))
        assertTrue("שב\"ק ס" in p.fixedTerms)
        assertTrue(p.text.contains("от שלמה ארצי до אייל גולן"))
        assertTrue(p.text.endsWith("mainLinkName=AmphiTLV_C&ts=cal&tm=sms&tc="))
    }

    @Test fun noReliableNameStaysHebrewAndUserTermsHavePriority() {
        val text = "מתנת חג מסטופמרקט"
        assertEquals(text, prepared(text, listOf(text)).text)
        assertTrue(prepared(text).text.endsWith("סטופמרקט"))
        val address = "חורב 15 חיפה"
        assertEquals(address, prepared(address).text)
    }

    @Test fun ordinaryGrammaticalWordsAreNotBrokenUpForNoReason() {
        val source = "בחנות למשפחה מהעבודה"
        assertEquals(source, HebrewPrefixAnalyzer().prepare(source).text)
        assertEquals("ב Смотрич", HebrewPrefixAnalyzer().prepare("בסמוטריץ").text)
        assertTrue(prepared("למה אתם בוחרים בסמוטריץ?").text.contains("выбираете Смотрича"))
    }

    @Test fun restoredNamesDoNotStickToNeighbouringWordsWhenModelDropsSpaces() {
        val plan = TokenProtector.protect("Жители Хайфы, это Беннет.", listOf("Жители Хайфы", "это Беннет"))
        assertEquals("Жители Хайфы это Беннет", TokenProtector.restore(plan, "⟦0⟧⟦1⟧"))
        val name = TokenProtector.protect("встреча с Хайфа завтра", listOf("Хайфа"))
        assertEquals("текст Хайфа здесь", TokenProtector.restore(name, "текст⟦0⟧здесь"))
        assertFalse(TranslationValidator.validate("Хайфа", "ХайфаЗдесь", listOf("Хайфа")).valid)
    }

    @Test fun knownDeliveryMessagesUseOnlyExactSourceFields() {
        val samples = listOf(
            Triple("CN100997761", "החמניה,חורב 15 חיפה", "https://u.cheetahint.com/s13f2uw"),
            Triple("CN100997592", "מיני מרקט יוני,סנש חנה 43 חיפה", "https://u.cheetahint.com/qvhww2d"),
            Triple("AE041531431", "מינימרקט תנובה,הכלניות 2 טירת כרמל", "https://u.cheetahint.com/o20rzpv"),
        )
        for ((code, location, url) in samples) {
            val source = "שלום Alex Example, איזה כיף :), משלוח $code ממתין לאיסוף ב $location. לשעות פתיחה, פרטים ואישור איסוף לחץ $url מומלץ לאסוף תוך 3 ימי עסקים. תודה צ'יטה שליחויות *אין להשיב*"
            val result = requireNotNull(HebrewDeliveryTemplates.translate(source)) { code }
            assertTrue(result.contains(code))
            assertTrue(result.contains(location))
            assertTrue(result.contains(url))
            assertTrue(result.contains("3 рабочих дней"))
            assertTrue(TranslationValidator.validate(source, result).valid)
            assertNull(HebrewDeliveryTemplates.translate(source.replace("ממתין לאיסוף", "נמסר לנמען")))
            assertNull(HebrewDeliveryTemplates.translate(source, listOf("מומלץ לאסוף")))
            assertTrue(requireNotNull(HebrewDeliveryTemplates.translate(source.replace("3 ימי", "1 ימי"))).contains("1 рабочего дня"))
        }
    }

    @Test fun deliveryReminderKeepsDateTimeAndBothNumbers() {
        val source = "שלום Alex Example, בתאריך 17/09 בשעה 13:15 ביצענו ספירת מלאי בחנות מיני מרקט יוני,סנש חנה 43 חיפה והמשלוח שמספרו CN100997592 (חבילה 102267100) עדיין מחכה לך שתבוא לאסוף אותו. לשעות פתיחה, פרטים ואישור איסוף לחץ https://u.cheetahint.com/xwon8he מומלץ לאסוף תוך 2 ימי עסקים. בברכה צ'יטה שליחויות *אין להשיב להודעה זו*"
        val result = requireNotNull(HebrewDeliveryTemplates.translate(source))
        for (value in listOf("17/09", "13:15", "CN100997592", "102267100", "מיני מרקט יוני,סנש חנה 43 חיפה", "https://u.cheetahint.com/xwon8he")) {
            assertTrue(value, result.contains(value))
            assertFalse(TranslationValidator.validate(source, result.replace(value, "0")).valid)
        }
        assertTrue(result.contains("2 рабочих дней"))
    }

    @Test fun checkingProtectedPhraseDoesNotInventStandaloneStars() {
        val source = "*Не отвечайте* CN100997761"
        assertTrue(TranslationValidator.validate(source, source, listOf("Не отвечайте")).valid)
        assertFalse(TranslationValidator.validate(source, source + " *", listOf("Не отвечайте")).valid)
        assertTrue(TranslationValidator.validate("пункт", "пункт и подпункт", listOf("пункт")).valid)
        assertFalse(TranslationValidator.validate("пункт", "пункт и пункт", listOf("пункт")).valid)
    }

    @Test fun shortNumbersArePreservedAndHebrewNumericPrefixesAreNotCodes() {
        val values = TokenProtector.extractedTokens("מוגבלים ל3 ובקנייה ב150 ₪ תוך 2 ימי עסקים")
        assertTrue(values.any { it.second == "3" })
        assertTrue(values.any { it.second == "150 ₪" })
        assertFalse(values.any { it.second == "ב150" })
        assertFalse(TranslationValidator.validate("תוך 3 ימי עסקים", "В течение 4 рабочих дней").valid)
        assertFalse(TranslationValidator.validate("תוך 3 ימי עסקים", "В течение рабочих дней").valid)
    }

    @Test fun unchangedHebrewParagraphIsRetriedAndNotCachedButAddressIsAllowed() = sync {
        val source = "אני מאמין שהוא יכול להביא שינוי וביטחון ואחדות לכולם"
        var cacheWrites = 0
        try {
            ValidatedOfflineTranslator.translate(source, sourceLanguage = "he", targetLanguage = "ru") { it }
            cacheWrites++
            fail("Untranslated paragraph must not be a success")
        } catch (_: ValidatedOfflineTranslator.Failure) { }
        assertEquals(0, cacheWrites)
        val address = "מינימרקט תנובה,הכלניות 2 טירת כרמל"
        assertTrue(TranslationValidator.validateLanguageCoverage(source, "Адрес: $address", listOf(address), "he", "ru").valid)
        assertTrue(TranslationValidator.validateLanguageCoverage(source, "Здравствуйте, אלכסנדר ברלסון. Посылка доставлена.", emptyList(), "he", "ru").valid)
    }

    private fun sync(block: suspend () -> Unit) {
        var outcome: Result<Unit>? = null
        block.startCoroutine(object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) { outcome = result }
        })
        requireNotNull(outcome).getOrThrow()
    }
}
