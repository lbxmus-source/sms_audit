package com.berelson.smstranslator.translation

import org.junit.Assert.*
import org.junit.Test

class HebrewPrefixAnalyzerTest {
    private val analyzer = HebrewPrefixAnalyzer()

    @Test fun supportsAllSevenPrefixesOnReviewedEntity() {
        for (prefix in "בלכמוהש") {
            val a = requireNotNull(analyzer.analyze(prefix + "סמוטריץ"))
            assertEquals(prefix.toString(), a.prefixes)
            assertEquals("סמוטריץ", a.stem)
            assertEquals("Смотрич", a.entity?.russian)
        }
    }

    @Test fun protectsTheSurnameInActualQuestionAndKeepsOriginalImmutable() {
        val original = "למה אתם בוחרים בסמוטריץ? 5- לא מצביע לסמוטריץ"
        val prepared = analyzer.prepare(original)
        assertEquals("למה אתם בוחרים ב Смотрич? 5- לא מצביע ל Смотрич", prepared.text)
        assertTrue("Смотрич" in prepared.protectedTerms)
        assertEquals("למה אתם בוחרים בסמוטריץ? 5- לא מצביע לסמוטריץ", original)
        val markers = TokenProtector.protect(prepared.text, prepared.protectedTerms)
        assertFalse(markers.encodedText.contains("Смотрич"))
        assertEquals(prepared.text, TokenProtector.restore(markers, markers.encodedText))
    }

    @Test fun wholeKnownWordsAndNeverSplitWinOverGuessedStems() {
        for (word in HebrewPrefixRules.neverSplit) assertNull(word, analyzer.analyze(word))
        val a = HebrewPrefixAnalyzer(knownWords = setOf("מקום", "קום", "מלך", "לך"))
        assertNull(a.analyze("מקום"))
        assertNull(a.analyze("מלך"))
    }

    @Test fun forceSplitWinsButCannotDeleteOrInventCharacters() {
        val a = HebrewPrefixAnalyzer(
            forceSplit = mapOf("בבית" to HebrewPrefixRules.Split("ב", "בית")),
            neverSplit = setOf("בבית"), knownWords = setOf("בבית"),
        )
        assertEquals("בית", a.analyze("בבית")?.stem)
        val invalid = HebrewPrefixAnalyzer(forceSplit = mapOf("מקום" to HebrewPrefixRules.Split("מ", "בית")))
        assertNull(invalid.analyze("מקום"))
    }

    @Test fun unknownWordsAndObservedTokensDoNotAuthorizeGuesses() {
        for (prefix in "בלכמוהש") assertNull(analyzer.analyze(prefix + "זרקפל"))
        assertNull(analyzer.analyze("בזרקפל", observed = setOf("זרקפל")))
        assertNull(analyzer.analyze("וחנות"))
        assertNull(analyzer.analyze("החנות"))
        assertNull(analyzer.analyze("שחנות"))
        assertEquals("חנות", analyzer.analyze("בחנות")?.stem)
    }

    @Test fun strictPrefixesRequireReviewedContextOrConfirmedCombination() {
        assertNull(analyzer.analyze("שהחבילה"))
        assertEquals("חבילה", analyzer.analyze("שהחבילה", "נמסר עדכון")?.stem)
        val a = HebrewPrefixAnalyzer(confirmedSplits = setOf("וחנות"))
        assertEquals("חנות", a.analyze("וחנות")?.stem)
        assertNull(HebrewPrefixAnalyzer(confirmedSplits = setOf("וזרקפל")).analyze("וזרקפל"))
    }

    @Test fun chainsAreOrderedBoundedAndPreferLongRecognizedStem() {
        assertEquals("ושבה", analyzer.analyze("ושבהסמוטריץ")?.prefixes)
        assertNull(analyzer.analyze("ווושבהסמוטריץ"))
        assertNull(analyzer.analyze("בשסמוטריץ"))
        val entities = listOf(
            HebrewPrefixRules.Entity("הדס", null, HebrewPrefixRules.EntityType.PERSON),
            HebrewPrefixRules.Entity("דס", null, HebrewPrefixRules.EntityType.BRAND),
        )
        val a = HebrewPrefixAnalyzer(entities = entities)
        assertEquals("הדס", a.analyze("בהדס")?.stem)
    }

    @Test fun doNotTouchAddressesLinksCodesOrUserProtectedPhrases() {
        val text = "בסמוטריץ https://example.com/בסמוטריץ חורב 15 חיפה CN100997761"
        assertEquals(text, analyzer.prepare(text, listOf("בסמוטריץ")).text)
        assertEquals("ב סמוטריץ", analyzer.prepare("בסמוטריץ", listOf("סמוטריץ")).text)
    }

    @Test fun unavailableTranslationsStayHebrewAndNonRussianTargetsNeverGetRussian() {
        assertEquals("ב Хайфа", analyzer.prepare("בחיפה").text)
        assertEquals("ב חיפה", analyzer.prepare("בחיפה", target = "en").text)
        assertEquals("מ סטופמרקט", analyzer.prepare("מסטופמרקט").text)
        assertEquals("מ צ'יטה", analyzer.prepare("מצ'יטה").text)
        assertEquals("ב סמוטריץ", analyzer.prepare("בסמוטריץ", target = "en").text)
        assertEquals("סְמוֹטְרִיץ", analyzer.prepare("סְמוֹטְרִיץ").text)
        assertEquals("ב Тель-Авив", analyzer.prepare("בתל אביב").text)
        assertEquals("ו ש ב טירת כרמל", analyzer.prepare("ושבטירת כרמל").text)
    }

    @Test fun localObservationsExcludeOperationalDataAndDoNotPromoteEntities() {
        val observed = analyzer.observedTokens("סמוטריץ חנות https://example.com/בסמוטריץ CN100997761")
        assertTrue("חנות" in observed)
        assertFalse("בסמוטריץ" in observed)
        assertNull(analyzer.analyze("והחנות", observed = observed))
    }
}
