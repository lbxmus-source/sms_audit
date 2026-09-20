package com.berelson.smstranslator.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainRuleEngineTest {
    @Test
    fun detectsAllSupportedDomainsFromRepresentativeMessages() {
        val samples = mapOf(
            DomainRuleEngine.Domain.GROCERY to "המבצע בתוקף עד גמר המלאי בסופרמרקט",
            DomainRuleEngine.Domain.DELIVERY to "החבילה נקלטה והיא בדרך אליך",
            DomainRuleEngine.Domain.MEDICAL to "נקבע לך תור לרופא עיניים במרפאה",
            DomainRuleEngine.Domain.PHARMACY to "התרופה מוכנה לאיסוף בבית מרקחת",
            DomainRuleEngine.Domain.BANKING to "חיוב בכרטיס הופיע ביתרת החשבון",
            DomainRuleEngine.Domain.GOVERNMENT to "הבקשה אושרה בביטוח לאומי",
            DomainRuleEngine.Domain.WORK to "סידור עבודה חדש כולל משמרת ערב",
            DomainRuleEngine.Domain.SCHOOL to "אסיפת הורים בבית הספר עם מחנכת הכיתה",
            DomainRuleEngine.Domain.TRANSPORT to "הרכבת מאחרת בתחנת רכבת",
            DomainRuleEngine.Domain.UTILITIES to "הפסקת חשמל עקב תקלה אזורית",
            DomainRuleEngine.Domain.CONSTRUCTION to "חומרי בניין: לוח גבס ודבק קרמיקה",
            DomainRuleEngine.Domain.SECURITY to "קוד חד פעמי לכניסה ואימות דו שלבי",
        )

        samples.forEach { (expected, message) ->
            assertEquals(message, expected, DomainRuleEngine.detect(message))
        }
    }

    @Test
    fun senderHintSelectsContextForOtherwiseGenericMessage() {
        assertEquals(
            DomainRuleEngine.Domain.GROCERY,
            DomainRuleEngine.detect("שלום, מחכה לך הטבה חדשה", "Shufersal"),
        )
        assertEquals(
            DomainRuleEngine.Domain.MEDICAL,
            DomainRuleEngine.detect("יש עבורך הודעה חדשה", "Clalit"),
        )
    }

    @Test
    fun detectsGroceryCatalogueFromContentWithoutKnowingTheStore() {
        val message = """
            🍎 29.90 ₪ לק"ג- לשון קפואה
            🍎 29.90 ₪ לק"ג- אסאדו קפוא עם עצם
            🍎 49.90 ₪ לק"ג- שוק אווז קפוא
            🍎 89.90 ₪ לק"ג- חזה אווז קפוא
            🍎 89.90 ₪ לק"ג- רבע טלה טרי
            🍎 2.90 ₪ למארז- עגבניות שרי אדומות
            🍎 2.90 ₪ לק"ג- בצל אדום/ כרוב לבן/ גזר ארוז
            בתוקף עד 2.10.2026 או עד גמר המלאי
        """.trimIndent()

        assertEquals(
            DomainRuleEngine.Domain.GROCERY,
            DomainRuleEngine.detect(message, sender = "Unknown shop"),
        )
    }

    @Test
    fun detectsGroceryCatalogueInAnotherCountryFromStructureAndProducts() {
        val message = """
            €3.99 per kg - fresh tomatoes
            €2.49 per kg - red onions
            €5.90 per kg - frozen vegetables
            €1.99 per kg - fresh fruit
            Offer valid until Friday while stocks last
        """.trimIndent()

        assertEquals(
            DomainRuleEngine.Domain.GROCERY,
            DomainRuleEngine.detect(message, sender = "Local Market 24"),
        )
    }

    @Test
    fun isolatedMoneyAmountDoesNotSelectGroceryContext() {
        assertNull(DomainRuleEngine.detect("העברתי לך 150 ₪, תודה"))
    }

    @Test
    fun manualSelectionOverridesAutomaticDetection() {
        val message = "החבילה נקלטה והיא בדרך אליך"
        assertEquals(
            DomainRuleEngine.Domain.CONSTRUCTION,
            DomainRuleEngine.resolve(
                message,
                forced = DomainRuleEngine.Domain.CONSTRUCTION,
                sender = "Cheetah",
            ),
        )
    }

    @Test
    fun groceryRulesReplacePhrasesButPreserveTheRest() {
        val source = "המבצע בתוקף עד 14.9.26. עד גמר המלאי"
        val segments = DomainRuleEngine.segments(source, DomainRuleEngine.Domain.GROCERY)
        val assembled = segments.joinToString("") { it.fixedTranslation ?: it.text }

        assertEquals("акция действует עד 14.9.26. пока товар есть в наличии", assembled)
        assertEquals(2, segments.count { it.fixedTranslation != null })
    }

    @Test
    fun groceryRulesCorrectAmbiguousProductsFromUniversePromotion() {
        val source = """
            לשון קפואה
            אסאדו קפוא עם עצם
            שוק אווז קפוא
            חזה אווז קפוא
            רבע טלה טרי
            עגבניות שרי אדומות
            בצל אדום/ כרוב לבן/ גזר ארוז/ בייבי בטטה/ דלורית
            סלק בוואקום/ שום יבש
        """.trimIndent()
        val translated = DomainRuleEngine.segments(source, DomainRuleEngine.Domain.GROCERY)
            .joinToString("") { segment -> segment.fixedTranslation ?: segment.text }

        listOf(
            "замороженный говяжий язык",
            "замороженное асадо на кости",
            "замороженная гусиная ножка",
            "замороженная гусиная грудка",
            "свежая четверть ягнёнка",
            "красные помидоры черри",
            "красный лук",
            "белокочанная капуста",
            "морковь в упаковке",
            "мини-батат",
            "тыква баттернат",
            "свёкла в вакуумной упаковке",
            "сухой чеснок",
        ).forEach { expected ->
            assertTrue("Missing replacement: $expected", translated.contains(expected))
        }
    }

    @Test
    fun groceryRulesCorrectPromotionConditions() {
        val source = """
            לפרטים ומבצעים נוספים
            בתוקף עד 2.10.2026 או עד גמר המלאי, המוקדם שבהם.
            מבצע לשון מוגבל ל3 ק"ג.
            מבצעי ירקות ופירות בתוקף עד 20.9.26.
            מבצע שרי חל בסניפים נבחרים, ולא כולל לובלו.
            מחירם הקודם של המוצרים מוצג בסניף.
            כפוף לתנאי המבצע, למלאי ולמגוון בסניף.
            להסרה שלחו 'הסר' ל-050-8085055
        """.trimIndent()
        val translated = DomainRuleEngine.segments(source, DomainRuleEngine.Domain.GROCERY)
            .joinToString("") { segment -> segment.fixedTranslation ?: segment.text }

        listOf(
            "подробности и другие акции",
            "или до окончания запасов — в зависимости от того, что наступит раньше",
            "акция на говяжий язык",
            "акции на овощи и фрукты",
            "действует в отдельных филиалах",
            "предыдущая цена товаров указана в магазине",
            "согласно условиям акции, при наличии и с учётом ассортимента магазина",
            "отправьте 'הסר' на",
        ).forEach { expected ->
            assertTrue("Missing replacement: $expected", translated.contains(expected))
        }
    }

    @Test
    fun preparesFullUniversePromotionForOneContextualTranslationRequest() {
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

        assertEquals(DomainRuleEngine.Domain.GROCERY, DomainRuleEngine.detect(source, "Universe"))
        val prepared = requireNotNull(
            DomainRuleEngine.prepareTranslation(source, DomainRuleEngine.Domain.GROCERY),
        )

        listOf(
            "замороженный говяжий язык",
            "замороженное асадо на кости",
            "замороженная гусиная ножка",
            "замороженная гусиная грудка",
            "свежая четверть ягнёнка",
            "красные помидоры черри",
            "свёкла в вакуумной упаковке",
            "подробности и другие акции",
            "предыдущая цена товаров указана в магазине",
            "за кг",
            "за упаковку",
        ).forEach { expected ->
            assertTrue("Missing prepared phrase: $expected", prepared.text.contains(expected))
            assertTrue("Phrase must be protected: $expected", expected in prepared.fixedTranslations)
        }
        listOf(
            "29.90 ₪",
            "2.10.2026",
            "20.9.26",
            "150 ₪",
            "100 ₪",
            "050-8085055",
            "shufersal.club/46Wdzs8",
        ).forEach { unchanged ->
            assertTrue("Technical value changed: $unchanged", prepared.text.contains(unchanged))
        }
        val protectionPlan = TokenProtector.protect(prepared.text, prepared.fixedTranslations)
        assertEquals(prepared.text, TokenProtector.restore(protectionPlan, protectionPlan.encodedText))
        assertTrue(TranslationValidator.validate(source, prepared.text).valid)
        assertFalse(prepared.text.contains("לשון קפואה"))
        assertFalse(prepared.text.contains("שוק אווז קפוא"))
    }

    @Test
    fun rulesFromAnotherContextAreNotApplied() {
        val source = "נקבע לך תור לרופא עיניים"
        val segments = DomainRuleEngine.segments(source, DomainRuleEngine.Domain.GROCERY)

        assertEquals(listOf(DomainRuleEngine.Segment(source)), segments)
    }

    @Test
    fun userProtectedTermTakesPriorityOverContextRule() {
        val source = "המבצע בתוקף"
        val segments = DomainRuleEngine.segments(
            source,
            DomainRuleEngine.Domain.GROCERY,
            protectedTerms = listOf("המבצע בתוקף"),
        )

        assertEquals(listOf(DomainRuleEngine.Segment(source)), segments)
    }

    @Test
    fun shortConstructionWordDoesNotMatchInsideAnotherWord() {
        val source = "החשוד נמלט מהמקום"
        val segments = DomainRuleEngine.segments(source, DomainRuleEngine.Domain.CONSTRUCTION)

        assertFalse(segments.any { it.fixedTranslation == "цемент" })
    }

    @Test
    fun unknownGeneralMessageKeepsAutomaticContextEmpty() {
        assertNull(DomainRuleEngine.detect("שלום, מה נשמע היום?"))
    }

    @Test
    fun everyDomainHasLabelsAndItsOwnCacheSalt() {
        val salts = DomainRuleEngine.Domain.values().map { domain ->
            assertTrue(DomainRuleEngine.displayName(domain, "ru").isNotBlank())
            assertTrue(DomainRuleEngine.displayName(domain, "he").isNotBlank())
            assertTrue(DomainRuleEngine.displayName(domain, "en").isNotBlank())
            DomainRuleEngine.cacheSalt(domain)
        }

        assertEquals(DomainRuleEngine.Domain.values().size, salts.toSet().size)
    }
}
