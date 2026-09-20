package com.berelson.smstranslator.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageScriptHeuristicsTest {
    @Test
    fun russianMessageIsNotMistakenForHebrewBecauseOfOptOutFooter() {
        val source = """
            50% женщин, два бывших премьер-министра, бывшие министры, мэры городов,
            резервисты и герои 7 октября. Сегодня мы подали список «Бе-Яхад».
            Это люди дела, настоящие профессионалы.
            להסרה השב הסר
        """.trimIndent()

        assertEquals(null, LanguageScriptHeuristics.strongLanguageHint(source))
        assertEquals(
            "ru",
            LanguageScriptHeuristics.correctedIdentifiedLanguage(source, "he"),
        )
    }

    @Test
    fun predominantlyHebrewMessageStillUsesHebrew() {
        val source =
            "המשלוח יגיע מחר לכתובת חורב 15 חיפה. השירות זמין בכל שעות היום. Ответить: הסר"

        assertEquals("he", LanguageScriptHeuristics.strongLanguageHint(source))
        assertEquals("he", LanguageScriptHeuristics.correctedIdentifiedLanguage(source, "he"))
    }

    @Test
    fun ukrainianDistinctiveLettersAreNotTreatedAsRussian() {
        val source = "Ваші нові повідомлення вже доступні для перегляду"

        assertEquals("uk", LanguageScriptHeuristics.strongLanguageHint(source))
    }
}
