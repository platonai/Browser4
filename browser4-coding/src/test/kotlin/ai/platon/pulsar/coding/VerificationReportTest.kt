package ai.platon.pulsar.coding

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for the structured verification verdict (plan P0.1): the report model,
 * its pass/fail semantics (a check that did not RUN is never green), the JSON
 * snapshot the finish gate and CLI consume, and the human rendering.
 */
class VerificationReportTest {

    private val mapper = ObjectMapper()

    private fun check(
        name: String = "compile",
        target: String = "browser4-coding",
        ran: Boolean = true,
        ok: Boolean = true,
        required: Boolean = true,
        exitCode: Int = 0,
        evidence: String = if (ok) "exit 0" else "exit 1",
        detail: String? = null,
        diagnostics: List<BuildDiagnostic> = emptyList(),
    ) = VerificationCheck(
        name = name,
        target = target,
        ran = ran,
        ok = ok,
        required = required,
        exitCode = exitCode,
        durationMs = 1_200,
        evidence = evidence,
        detail = detail,
        diagnostics = diagnostics,
    )

    @Test
    @DisplayName("all checks ran and passed → passed")
    fun allGreen() {
        val report = VerificationReport(
            checks = listOf(check(), check(name = "repo-consistency", target = "repo")),
            module = "browser4-coding",
            requested = listOf("compile", "repo-consistency"),
            durationMs = 2_400,
        )
        assertTrue(report.passed)
        assertNull(report.failureReason())
    }

    @Test
    @DisplayName("a failed check fails the report and names the reason")
    fun failedCheck() {
        val report = VerificationReport(
            checks = listOf(
                check(),
                check(
                    name = "test",
                    ok = false,
                    exitCode = 1,
                    detail = "FooTest.kt:12:5 error: Unresolved reference 'x'",
                ),
            ),
        )
        assertFalse(report.passed)
        val reason = report.failureReason()
        assertNotNull(reason)
        assertTrue(reason!!.contains("test"), "reason: $reason")
        assertTrue(reason.contains("Unresolved reference"), "reason: $reason")
    }

    @Test
    @DisplayName("a required check that did not run fails the report")
    fun skippedRequiredCheckFails() {
        val report = VerificationReport(
            checks = listOf(check(ran = false, ok = false, detail = "no module resolved")),
        )
        assertFalse(report.passed, "a gate that did not run must never be green")
        assertTrue(report.failureReason()!!.contains("did not run"))
    }

    @Test
    @DisplayName("an optional check that did not run does not fail the report")
    fun skippedOptionalCheckPasses() {
        val report = VerificationReport(
            checks = listOf(
                check(),
                check(name = "trapCheck", target = "(no files)", ran = false, ok = false, required = false),
            ),
        )
        assertTrue(report.passed)
        assertEquals(1, report.skipped.size)
    }

    @Test
    @DisplayName("toJson exposes the stable structure consumers depend on")
    fun jsonStructure() {
        val report = VerificationReport(
            checks = listOf(
                check(
                    diagnostics = listOf(
                        BuildDiagnostic("a/b/Foo.kt", 12, 5, "error", "boom")
                    )
                )
            ),
            module = "browser4-coding",
            requested = listOf("compile"),
            durationMs = 1_200,
        )
        val json = mapper.readTree(report.toJson())
        assertEquals(true, json["passed"].asBoolean())
        assertEquals("browser4-coding", json["module"].asText())
        assertTrue(json["durationMs"].asLong() >= 0)
        val check0 = json["checks"][0]
        assertEquals("compile", check0["name"].asText())
        assertEquals("browser4-coding", check0["target"].asText())
        assertEquals(true, check0["ran"].asBoolean())
        assertEquals(true, check0["ok"].asBoolean())
        assertEquals(0, check0["exitCode"].asInt())
        val diag = check0["diagnostics"][0]
        assertEquals("a/b/Foo.kt", diag["file"].asText())
        assertEquals(12, diag["line"].asInt())
        assertEquals(5, diag["column"].asInt())
        assertEquals("error", diag["severity"].asText())
        assertEquals("boom", diag["message"].asText())
    }

    @Test
    @DisplayName("format renders a human summary plus the machine verdict line")
    fun humanRendering() {
        val report = VerificationReport(
            checks = listOf(check(name = "compile", target = "browser4-coding")),
            module = "browser4-coding",
            requested = listOf("compile"),
            durationMs = 1_200,
        )
        val text = report.format()
        assertTrue(text.startsWith("Verification: PASSED"), "text: $text")
        assertTrue(text.contains("✓ compile browser4-coding"), "text: $text")
        val verdictLine = text.lineSequence().last()
        assertTrue(verdictLine.startsWith(VerificationReport.VERDICT_PREFIX), "line: $verdictLine")

        val extracted = VerificationReport.extractJson(text)
        assertNotNull(extracted)
        val parsed = mapper.readTree(extracted)
        assertTrue(parsed["passed"].asBoolean())
    }

    @Test
    @DisplayName("format without the verdict line is human-only (no machine payload)")
    fun humanOnlyRendering() {
        val report = VerificationReport(checks = listOf(check()))
        val text = report.format(includeVerdictLine = false)
        assertNull(VerificationReport.extractJson(text))
    }

    @Test
    @DisplayName("extractJson returns null when the reply carries no verdict")
    fun extractMissingVerdict() {
        assertNull(VerificationReport.extractJson("mvn -pl x -am compile → exit 0\n✓ Build succeeded"))
    }

    @Test
    @DisplayName("fromJson / parseFromText round-trip a verdict")
    fun jsonRoundTrip() {
        val report = VerificationReport(
            checks = listOf(
                check(
                    diagnostics = listOf(
                        BuildDiagnostic("a/b/Foo.kt", 12, 5, "error", "boom")
                    )
                )
            ),
            module = "browser4-coding",
            requested = listOf("compile", "repo-consistency"),
            durationMs = 1_200,
        )

        val fromJson = VerificationReport.fromJson(report.toJson())
        assertNotNull(fromJson)
        assertEquals(report.passed, fromJson!!.passed)
        assertEquals("browser4-coding", fromJson.module)
        assertEquals(listOf("compile", "repo-consistency"), fromJson.requested)
        assertEquals(1, fromJson.checks.size)
        val diagnostic = fromJson.checks[0].diagnostics.single()
        assertEquals("a/b/Foo.kt", diagnostic.file)
        assertEquals(12, diagnostic.line)
        assertEquals(5, diagnostic.column)
        assertEquals("error", diagnostic.severity)
        assertEquals("boom", diagnostic.message)

        // The human rendering carries the same verdict on its last line.
        val fromText = VerificationReport.parseFromText(report.format())
        assertNotNull(fromText)
        assertEquals(report.passed, fromText!!.passed)
        assertEquals("browser4-coding", fromText.module)

        assertNull(VerificationReport.parseFromText("no verdict in this reply"))
        assertNull(VerificationReport.fromJson("{not json"))
    }
}
