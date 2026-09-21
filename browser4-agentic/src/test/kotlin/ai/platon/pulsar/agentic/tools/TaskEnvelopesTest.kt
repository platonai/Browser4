package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.tools.specs.ToolResultValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The async contract is only useful if every domain answers in the same shape and
 * vocabulary — a client polling three domains must not need three parsers.
 */
@DisplayName("Task envelopes")
class TaskEnvelopesTest {

    @Test
    @DisplayName("domain status words map onto the shared vocabulary")
    fun statusWordsAreNormalised() {
        assertEquals("queued", TaskEnvelopes.normalise("CREATED"))
        assertEquals("queued", TaskEnvelopes.normalise("pending"))
        assertEquals("running", TaskEnvelopes.normalise("in_progress"))
        assertEquals("running", TaskEnvelopes.normalise("OK-ish".let { "started" }))
        assertEquals("done", TaskEnvelopes.normalise("OK"))
        assertEquals("done", TaskEnvelopes.normalise("completed"))
        assertEquals("failed", TaskEnvelopes.normalise("TIMEOUT"))
        assertEquals("failed", TaskEnvelopes.normalise("ERROR"))
        assertEquals("cancelled", TaskEnvelopes.normalise("Cancelled"))
        assertEquals(
            "failed", TaskEnvelopes.normalise("something-new"),
            "an unknown status must never look healthy",
        )
        assertEquals("failed", TaskEnvelopes.normalise(null))
    }

    @Test
    @DisplayName("only the terminal statuses stop a polling loop")
    fun terminalStatusesAreClosed() {
        assertFalse(TaskEnvelopes.isTerminal("queued"))
        assertFalse(TaskEnvelopes.isTerminal("running"))
        assertTrue(TaskEnvelopes.isTerminal("done"))
        assertTrue(TaskEnvelopes.isTerminal("failed"))
        assertTrue(TaskEnvelopes.isTerminal("cancelled"))
        assertFalse(TaskEnvelopes.isTerminal(null))
    }

    @Test
    @DisplayName("unknown values are omitted, so null never reads as zero")
    fun unknownFieldsAreOmitted() {
        val envelope = TaskEnvelopes.of(taskId = "t1", status = "running")

        assertEquals(mapOf("taskId" to "t1", "status" to "running"), envelope)
        assertFalse(envelope.containsKey("progress"))
        assertFalse(envelope.containsKey("processed"))
    }

    @Test
    @DisplayName("a progress of zero is kept: it is a real measurement")
    fun zeroProgressIsKept() {
        val envelope = TaskEnvelopes.of(taskId = "t1", status = "running", progress = 0.0, processed = 0)

        assertEquals(0.0, envelope["progress"])
        assertEquals(0, envelope["processed"])
    }

    @Test
    @DisplayName("progress is clamped into 0..1 and blank errors are dropped")
    fun valuesAreSanitised() {
        assertEquals(1.0, TaskEnvelopes.of("t", "running", progress = 1.7)["progress"])
        assertEquals(0.0, TaskEnvelopes.of("t", "running", progress = -3.0)["progress"])
        assertNull(TaskEnvelopes.of("t", "failed", error = "  ")["error"])
        assertEquals("boom", TaskEnvelopes.of("t", "failed", error = "boom")["error"])
    }

    @Test
    @DisplayName("both schemas accept the envelopes their tools actually produce")
    fun schemasAcceptRealEnvelopes() {
        val statusEnvelope = TaskEnvelopes.of(
            taskId = "t1", status = "running", processed = 3, elapsedMs = 1204,
            statusTool = "crawl_status", resultTool = "crawl_result", cancelTool = "crawl_cancel",
        )
        val submitEnvelope = TaskEnvelopes.of(
            taskId = "t1", status = "queued", statusTool = "crawl_status",
            resultTool = "crawl_result", cancelTool = "crawl_cancel",
        )

        val statusIssues = ToolResultValidator.validate(schema(TaskEnvelopes.STATUS_SCHEMA), ToolResultValidator.parse(json(statusEnvelope)))
        val submitIssues = ToolResultValidator.validate(schema(TaskEnvelopes.SUBMIT_SCHEMA), ToolResultValidator.parse(json(submitEnvelope)))

        assertEquals(emptyList<String>(), statusIssues.map { "${it.path} ${it.message}" })
        assertEquals(emptyList<String>(), submitIssues.map { "${it.path} ${it.message}" })
    }

    @Test
    @DisplayName("both schemas are valid JSON with the full status vocabulary")
    fun schemasAreValidJson() {
        listOf("submit" to TaskEnvelopes.SUBMIT_SCHEMA, "status" to TaskEnvelopes.STATUS_SCHEMA).forEach { (name, schema) ->
            val parsed = ToolResultValidator.parse(schema)
            assertTrue(parsed != null, "the $name schema must parse as JSON: $schema")
            val properties = (parsed as kotlinx.serialization.json.JsonObject)["properties"]
                ?: error("the $name schema has no properties")
            assertTrue(
                properties.toString().contains("\"queued\"") && properties.toString().contains("\"cancelled\""),
                "the $name schema must enumerate every status, saw: $properties",
            )
        }
    }

    @Test
    @DisplayName("the schemas reject a status outside the vocabulary")
    fun schemasRejectUnknownStatuses() {
        val bogus = TaskEnvelopes.of(taskId = "t1", status = "running") + mapOf("status" to "done-ish")
        val parsed = ToolResultValidator.parse(json(bogus))

        assertTrue(parsed != null, "the result must parse as JSON")

        val issues = ToolResultValidator.validate(schema(TaskEnvelopes.STATUS_SCHEMA), parsed)

        assertTrue(
            issues.any { it.message.contains("not one of") || it.message.contains("enum") },
            issues.map { it.message }.toString(),
        )
    }

    private fun schema(jsonText: String) = ai.platon.pulsar.agentic.model.ToolSpec(
        domain = "crawl",
        method = "status",
        description = "test",
        outputSchema = jsonText,
    )

    private fun json(value: Map<String, Any>): String =
        ai.platon.pulsar.common.serialize.json.pulsarObjectMapper().writeValueAsString(value)
}
