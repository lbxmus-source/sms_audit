package com.berelson.smsaudit

import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.*
import java.util.concurrent.CancellationException

class AuditPipelineTest {
    @Test fun onlyHebrewMessagesAreSelected() {
        assertTrue(AuditPipeline.hasHebrew("קוד 1234"))
        assertTrue(AuditPipeline.hasHebrew("Адрес חורב 15"))
        assertFalse(AuditPipeline.hasHebrew("Ваш код 1234"))
        assertFalse(AuditPipeline.hasHebrew("https://example.com"))
    }
    @Test fun wrongCodeIsFlagged() {
        assertTrue("PROTECTED_VALUE_CHANGED" in AuditPipeline.flags("קוד 1234", "Код 9999"))
    }
    @Test fun preservedCodeHasNoTechnicalError() {
        assertTrue(AuditPipeline.flags("קוד 1234", "Код 1234").isEmpty())
    }
    @Test fun unchangedHebrewIsNeverMarkedAsVerified() {
        val flags = AuditPipeline.flags("שלום עולם", "שלום עולם")
        assertTrue("UNCHANGED_TEXT" in flags); assertTrue("NO_RUSSIAN_TEXT" in flags)
    }
    @Test fun protectedNamesDoNotCountAsUntranslatedText() {
        assertFalse("HEBREW_REMAINS_REVIEW_NAMES" in AuditPipeline.flags("שלום דנה", "Здравствуйте דנה", listOf("דנה")))
    }
    @Test fun missingTranslationIsVisible() {
        assertEquals(listOf("NO_TRANSLATION"), AuditPipeline.flags("שלום", null))
    }
    @Test fun cancellationStopsWork() {
        var thrown = false
        try { runSync { AuditPipeline.run("שלום", "") { throw CancellationException() } } }
        catch (_: CancellationException) { thrown = true }
        assertTrue(thrown)
    }
    @Test fun modelFailureProducesReportInsteadOfLosingMessage() {
        val result = runSync { AuditPipeline.run("משפט חדש שלא מוכר כאן", "") { error("offline failure") } }
        assertNull(result.rawTranslation); assertNull(result.translation)
        assertTrue("NO_TRANSLATION" in result.flags); assertNotNull(result.failure)
    }
    @Test fun ordinaryModelAndRulesResultsAreBothRetained() {
        val result = runSync { AuditPipeline.run("שלום", "") { "Здравствуйте" } }
        assertEquals("Здравствуйте", result.rawTranslation)
        assertEquals("Здравствуйте", result.translation)
    }
    private fun <T> runSync(action: suspend () -> T): T {
        var outcome: Result<T>? = null
        action.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { outcome = result }
        })
        return requireNotNull(outcome).getOrThrow()
    }
}
