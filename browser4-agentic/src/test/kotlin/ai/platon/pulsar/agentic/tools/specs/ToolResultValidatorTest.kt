package ai.platon.pulsar.agentic.tools.specs

import ai.platon.pulsar.agentic.model.TaskPolicy
import ai.platon.pulsar.agentic.model.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A successful result must honour the contract its spec declares, otherwise the
 * documentation is a lie and clients break in the field.
 */
@DisplayName("Tool result validation")
class ToolResultValidatorTest {

    private val taskEnvelopeSchema = """
        {
          "type": "object",
          "required": ["taskId", "status", "statusTool"],
          "properties": {
            "taskId": {"type": "string"},
            "status": {"type": "string", "enum": ["running", "done", "failed"]},
            "pollAfterMs": {"type": "integer"},
            "statusTool": {"type": "string"},
            "resultTool": {"type": "string"}
          }
        }
    """.trimIndent()

    private fun submitSpec() = ToolSpec(
        domain = "crawl",
        method = "submit",
        arguments = listOf(ToolSpec.Arg("url", "String")),
        returnType = "String",
        description = "Submit a crawl task.",
        outputSchema = taskEnvelopeSchema,
        task = TaskPolicy(statusTool = "crawl_status", resultTool = "crawl_result", pollAfterMs = 500),
    )

    @BeforeEach
    fun resetCounters() = ToolResultValidator.resetCounters()

    @Test
    @DisplayName("the shared task envelope satisfies the declared schema")
    fun taskEnvelopeIsValid() {
        val spec = submitSpec()
        val envelope = ToolResultValidator.taskEnvelope("task-1", spec.task!!)

        assertEquals(emptyList<ToolResultValidator.Issue>(), ToolResultValidator.validate(spec, envelope))
    }

    @Test
    @DisplayName("a missing required field is a violation with its JSON path")
    fun missingRequiredFieldIsReported() {
        val spec = submitSpec()
        val broken = JsonObject(mapOf("status" to JsonPrimitive("running")))

        val issues = ToolResultValidator.validate(spec, broken)

        assertTrue(issues.any { it.path == "$.taskId" }, issues.toString())
    }

    @Test
    @DisplayName("a wrong type and a value outside the enum are both reported")
    fun typeAndEnumAreChecked() {
        val spec = submitSpec()
        val wrongType = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive("t"),
                "status" to JsonPrimitive("running"),
                "statusTool" to JsonPrimitive("crawl_status"),
                "pollAfterMs" to JsonPrimitive("soon"),
            )
        )
        val wrongEnum = JsonObject(
            mapOf(
                "taskId" to JsonPrimitive("t"),
                "status" to JsonPrimitive("weird"),
                "statusTool" to JsonPrimitive("crawl_status"),
            )
        )

        assertTrue(
            ToolResultValidator.validate(spec, wrongType).any { it.path == "$.pollAfterMs" },
            "pollAfterMs must be an integer"
        )
        assertTrue(
            ToolResultValidator.validate(spec, wrongEnum).any { it.path == "$.status" },
            "status must stay inside the enum"
        )
    }

    @Test
    @DisplayName("a task tool that does not return an envelope is a violation")
    fun missingEnvelopeIsReported() {
        val spec = submitSpec()

        val issues = ToolResultValidator.validate(spec, JsonPrimitive("not-an-envelope"))

        assertTrue(issues.any { it.message.contains("task envelope") }, issues.toString())
    }

    @Test
    @DisplayName("only JSON results are parsed; prose stays prose")
    fun onlyJsonIsParsed() {
        assertNotNull(ToolResultValidator.parse("""{"a": 1}"""))
        assertNotNull(ToolResultValidator.parse("[1,2]"))
        assertEquals(null, ToolResultValidator.parse("Example Domain"))
        assertEquals(null, ToolResultValidator.parse(""))
        assertTrue(ToolResultValidator.isBareTaskId("8f14e45f-ea0b"))
        assertTrue(!ToolResultValidator.isBareTaskId("""{"taskId":"x"}"""))
    }

    @Test
    @DisplayName("violations are counted per tool for the Phase 3 metric")
    fun violationsAreCounted() {
        val spec = submitSpec()
        val broken = JsonObject(mapOf("status" to JsonPrimitive("running")))

        ToolResultValidator.report(spec, ToolResultValidator.validate(spec, broken))
        ToolResultValidator.report(spec, ToolResultValidator.validate(spec, broken))

        assertTrue(ToolResultValidator.violationCount(spec) >= 2)
        assertTrue(ToolResultValidator.totalViolations() >= 2)
    }

    @Test
    @DisplayName("a spec without a declared schema is never flagged")
    fun undeclaredSchemaIsNotChecked() {
        val plain = ToolSpec(domain = "tab", method = "title", description = "Title")

        assertEquals(
            emptyList<ToolResultValidator.Issue>(),
            ToolResultValidator.validate(plain, JsonPrimitive("Example Domain"))
        )
    }
}
