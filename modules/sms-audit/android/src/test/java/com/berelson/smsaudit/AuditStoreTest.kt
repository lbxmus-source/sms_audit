package com.berelson.smsaudit

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import java.io.StringReader
import java.io.StringWriter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AuditStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clean() { context.deleteDatabase("audit.db") }
    private fun message(key: String = "sms:1") = AuditStore.Message(key, "קוד 1234", "sender", 1000L, 1)
    private fun result() = AuditPipeline.Result("Код 1234", "Код 1234", "קוד 1234", emptyList(), emptyList(), "auto", emptyList(), emptyList(), null)
    private fun report(store: AuditStore, includeSenders: Boolean = false): JSONObject {
        val out = StringWriter(); store.export(out, includeSenders); return JSONObject(out.toString())
    }
    @Test fun progressSurvivesClosingAndOpeningDatabase() {
        AuditStore(context).use { it.put(message()); it.complete("sms:1", result()) }
        AuditStore(context).use {
            assertEquals(1, it.summary().done); assertNull(it.next())
            assertFalse(it.put(message())); assertEquals(1, it.summary().done)
        }
    }
    @Test fun changedOriginalIsReprocessedAndDeletedSmsAreRemovedAfterCompleteScan() {
        AuditStore(context).use {
            it.put(message()); it.complete("sms:1", result())
            assertTrue(it.put(message().copy(original = "קוד 5678")))
            assertEquals(0, it.summary().done)
            it.put(message("sms:2")); it.put(message("paste:1"))
            it.markSeen("sms:1", "new"); it.finishScan("new")
            assertEquals(2, it.summary().total)
        }
    }
    @Test fun reportPreservesOriginalAndTranslationsAndMakesNoSemanticClaim() {
        AuditStore(context).use {
            it.put(message()); it.complete("sms:1", result())
            val report = report(it)
            assertTrue(report.getBoolean("complete"))
            val row = report.getJSONArray("messages").getJSONObject(0)
            assertEquals("קוד 1234", row.getString("original")); assertFalse(row.has("sender"))
            val analysis = row.getJSONObject("analysis")
            assertEquals("Код 1234", analysis.getString("raw_mlkit_translation"))
            assertEquals("NOT_PERFORMED", analysis.getString("semantic_review"))
            assertEquals("sender", report(it, true).getJSONArray("messages").getJSONObject(0).getString("sender"))
        }
    }
    @Test fun interruptedScanIsNotClaimedCompleteAndExcludedSmsDoNotExport() {
        AuditStore(context).use {
            it.put(message()); it.complete("sms:1", result())
            it.metadata("device_scan_state", "incomplete")
            assertFalse(report(it).getBoolean("complete"))
            it.exclude("sms:1")
            assertEquals(0, report(it).getJSONArray("messages").length())
        }
    }
    @Test fun failedResultsCanBeRetriedWithoutRepeatingSuccessfulOnes() {
        AuditStore(context).use {
            it.put(message()); it.put(message("sms:2"))
            it.complete("sms:1", result()); it.fail("sms:2", "TIMEOUT")
            assertEquals(1, it.summary().failed)
            it.retryFailures()
            assertEquals(1, it.summary().done); assertEquals("sms:2", it.next()?.key)
        }
    }
    @Test fun jsonImportFiltersHebrewAndRollsBackMalformedInput() = runBlocking {
        AuditStore(context).use {
            val input = """[{"original":"קוד 1234","date":1},{"original":"Hello"}]"""
            val counts = SmsReader.readJson(StringReader(input), it)
            assertEquals(2, counts.scanned); assertEquals(1, counts.hebrew)
            var failed = false
            try { SmsReader.readJson(StringReader("""[{"original":"שלום"},{"date":"bad"}]"""), it) }
            catch (_: Exception) { failed = true }
            assertTrue(failed); assertEquals(1, it.summary().total)
        }
    }
}
