package ai.platon.pulsar.rest.mcp.contract

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.ToolErrorCode
import ai.platon.pulsar.agentic.tools.specs.ToolResultValidator
import ai.platon.pulsar.agentic.tools.specs.ToolSpecLint
import ai.platon.pulsar.agentic.tools.specs.ToolSpecValidator
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The contract matrix of requirement 3: every advertised tool is checked against
 * the cases a client can get wrong, **without a browser**.
 *
 * The browser-dependent cases (a real happy-path call, session errors, rate
 * limiting end to end, cache replay, batches) live in the channel tests and in the
 * e2e suites; what this test guarantees is that the *published contract* — the one
 * a client reads from `tools/list` and `help` — is complete, self-consistent and
 * enforced by the same validator both channels run.
 *
 * | Case | Input | Assertion |
 * |---|---|---|
 * | happy | the spec's own executable example, or synthesized required args | no violation |
 * | missing | the required arguments removed | `MISSING_REQUIRED_ARG`, signature echoed |
 * | type | `"abc"` into a numeric/boolean argument | `INVALID_ARGUMENT` |
 * | unknown | one undeclared argument | allowed by default, `UNKNOWN_ARGUMENT` in strict mode |
 * | transport | `sessionId` / `cache` | never reported as unknown |
 * | result | the declared `outputSchema` | parses, is an object, declares `type` |
 *
 * Tagged `Fast`: it is pure inspection, so it belongs in the PR gate.
 */
@Tag("Unit")
@Tag("Fast")
@DisplayName("Tool contract matrix")
class ToolContractMatrixTest {

    private val specs: List<ToolSpec> = ToolRegistryFixture.specs()
    private val validator = ToolSpecValidator()
    private val strictValidator = ToolSpecValidator(strictUnknownArgs = true)

    /** Type-appropriate sample values, so a synthesized call can be validated. */
    private fun sampleValue(type: String): Any = when (type.trimEnd('?').lowercase()) {
        "int", "integer", "long", "short" -> 1L
        "double", "float", "number" -> 1.0
        "boolean", "bool" -> true
        else -> "sample"
    }

    /** A call that satisfies every required argument of [spec]. */
    private fun happyArgs(spec: ToolSpec): Map<String, Any?> =
        spec.arguments
            .filter { it.defaultValue == null && it.name !in ToolSpecValidator.DEFAULT_CONTEXT_ARGS }
            .associate { it.name to sampleValue(it.type) }

    /** The spec's own example when it is executable — documentation as a test input. */
    private fun exampleArgs(spec: ToolSpec): Map<String, Any?>? =
        spec.examples.firstOrNull { it.executable }?.args

    private fun signature(spec: ToolSpec) = spec.expression

    // ---------------------------------------------------------------------
    // The matrix
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("every tool's own example (or a synthesized call) satisfies its contract")
    fun happyPathHoldsForEveryTool() {
        val failures = mutableListOf<String>()

        specs.forEach { spec ->
            val args = exampleArgs(spec) ?: happyArgs(spec)
            val violations = validator.validate(spec, args)
            if (violations.isNotEmpty()) {
                failures += "${ToolRegistryFixture.toolName(spec)}: " +
                    violations.joinToString("; ") { it.message } +
                    " | example=${exampleArgs(spec) != null} args=$args"
            }
        }

        assertEquals(emptyList<String>(), failures, "A published example must be callable as documented")
    }

    @Test
    @DisplayName("every required argument is enforced, with the signature in the message")
    fun requiredArgumentsAreEnforced() {
        val checked = mutableListOf<String>()
        val failures = mutableListOf<String>()

        specs.forEach { spec ->
            // `sessionId`/`cache` are transport arguments: the validator deliberately
            // never requires them, so they are not what this case is about.
            val required = spec.arguments.filter {
                it.defaultValue == null && it.name !in ToolSpecValidator.DEFAULT_CONTEXT_ARGS
            }
            if (required.isEmpty()) return@forEach
            checked += ToolRegistryFixture.toolName(spec)

            val args = happyArgs(spec) - required.first().name
            val violations = validator.validate(spec, args)
            val violation = violations.firstOrNull { it.code == ToolErrorCode.MISSING_REQUIRED_ARG }
            if (violation == null) {
                failures += "${ToolRegistryFixture.toolName(spec)}: missing '${required.first().name}' was accepted"
            } else if (!violation.message.contains(signature(spec))) {
                failures += "${ToolRegistryFixture.toolName(spec)}: message omits the signature — ${violation.message}"
            }
        }

        assertTrue(
            checked.size >= 50,
            "expected a substantial number of tools to declare required arguments, saw ${checked.size}",
        )
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    @DisplayName("a wrongly typed value is rejected for every typed argument")
    fun wrongTypesAreRejected() {
        val checked = mutableListOf<String>()
        val failures = mutableListOf<String>()

        specs.forEach { spec ->
            spec.arguments
                .filter { it.type.trimEnd('?').lowercase() in setOf("int", "integer", "long", "double", "float", "boolean", "bool") }
                .forEach { arg ->
                    checked += "${ToolRegistryFixture.toolName(spec)}(${arg.name})"
                    val args = happyArgs(spec) + (arg.name to "abc")
                    val violations = validator.validate(spec, args)
                    if (violations.none { it.code == ToolErrorCode.INVALID_ARGUMENT }) {
                        failures += "${ToolRegistryFixture.toolName(spec)}(${arg.name}): 'abc' was accepted as ${arg.type}"
                    }
                }
        }

        assertTrue(checked.isNotEmpty(), "expected typed arguments in the registry")
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    @DisplayName("an undeclared argument is allowed by default and rejected in strict mode")
    fun unknownArgumentsFollowThePolicy() {
        val failures = mutableListOf<String>()

        specs.forEach { spec ->
            val args = happyArgs(spec) + ("__undeclared__" to "x")
            if (validator.validate(spec, args).isNotEmpty()) {
                failures += "${ToolRegistryFixture.toolName(spec)}: default mode rejected an extra argument"
            }
            if (strictValidator.validate(spec, args).none { it.code == ToolErrorCode.UNKNOWN_ARGUMENT }) {
                failures += "${ToolRegistryFixture.toolName(spec)}: strict mode accepted an extra argument"
            }
        }

        assertEquals(emptyList<String>(), failures)
    }

    @Test
    @DisplayName("transport arguments are never treated as unknown")
    fun transportArgumentsAreContext() {
        val failures = mutableListOf<String>()

        specs.forEach { spec ->
            val args = happyArgs(spec) + mapOf("sessionId" to "s1", "cache" to false)
            val unknown = strictValidator.validate(spec, args).filter { it.code == ToolErrorCode.UNKNOWN_ARGUMENT }
            if (unknown.isNotEmpty()) {
                failures += "${ToolRegistryFixture.toolName(spec)}: ${unknown.joinToString("; ") { it.message }}"
            }
        }

        assertEquals(emptyList<String>(), failures, "sessionId/cache belong to the transport, not the tool")
    }

    @Test
    @DisplayName("every declared result schema is a valid JSON Schema object")
    fun resultSchemasAreValid() {
        val withSchema = specs.filter { !it.outputSchema.isNullOrBlank() }
        assertTrue(withSchema.isNotEmpty(), "the task tools declare result schemas")

        withSchema.forEach { spec ->
            val parsed = ToolResultValidator.parse(spec.outputSchema!!)
            assertTrue(parsed is JsonObject, "${ToolRegistryFixture.toolName(spec)}: outputSchema is not a JSON object")
            assertTrue(
                (parsed as JsonObject)["type"]?.toString()?.contains("object") == true,
                "${ToolRegistryFixture.toolName(spec)}: outputSchema must declare type=object",
            )
        }
    }

    @Test
    @DisplayName("a task tool names all three lifecycle tools, and they exist")
    fun taskPoliciesAreComplete() {
        val names = specs.map { ToolRegistryFixture.toolName(it) }.toSet()

        specs.filter { it.task != null }.forEach { spec ->
            val policy = spec.task!!
            listOfNotNull(policy.statusTool, policy.resultTool, policy.cancelTool).forEach { tool ->
                assertTrue(names.contains(tool), "${ToolRegistryFixture.toolName(spec)} references missing tool '$tool'")
            }
            assertTrue(policy.pollAfterMs > 0, "${ToolRegistryFixture.toolName(spec)}: pollAfterMs must be positive")
        }
    }

    // ---------------------------------------------------------------------
    // Coverage
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("the matrix covers every advertised tool, and reports the density")
    fun matrixCoversEveryTool() {
        val cases = listOf("happy", "required", "type", "unknown", "transport", "result")
        val perTool = specs.associate { spec ->
            ToolRegistryFixture.toolName(spec) to cases.count { case ->
                when (case) {
                    "happy" -> validator.validate(spec, exampleArgs(spec) ?: happyArgs(spec)).isEmpty()
                    "required" -> spec.arguments.any { it.defaultValue == null } ||
                        validator.validate(spec, happyArgs(spec)).isEmpty()
                    "type" -> true
                    "unknown" -> strictValidator.validate(spec, happyArgs(spec) + ("__x__" to 1)).isNotEmpty()
                    "transport" -> strictValidator
                        .validate(spec, happyArgs(spec) + mapOf("sessionId" to "s"))
                        .none { it.code == ToolErrorCode.UNKNOWN_ARGUMENT }

                    else -> !spec.outputSchema.isNullOrBlank() || spec.task == null
                }
            }
        }

        val uncovered = perTool.filterValues { it < cases.size }.keys
        val lintErrors = ToolSpecLint.errors(ToolSpecLint.check(specs)).size
        val withExamples = specs.count { spec -> spec.examples.any { it.executable } }

        println("Contract matrix: ${specs.size} tools x ${cases.size} cases")
        println("  - executable examples: $withExamples/${specs.size}")
        println("  - tools with a declared result schema: ${specs.count { !it.outputSchema.isNullOrBlank() }}")
        println("  - task tools: ${specs.count { it.task != null }}")
        println("  - lint errors: $lintErrors")

        assertEquals(emptyList<String>(), uncovered.toList(), "every advertised tool must pass every case")
        assertTrue(specs.size >= 140, "the registry must be fully enumerated, saw ${specs.size}")
        assertEquals(0, lintErrors, "the contract matrix runs against an error-free registry")
    }

    @Test
    @DisplayName("advertised names are unique, and cli names use the spaced form")
    fun namesAreUniqueAndWellFormed() {
        val names = specs.map { ToolRegistryFixture.toolName(it) }
        val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

        assertEquals(emptySet<String>(), duplicates, "two specs cannot share an MCP tool name")
        specs.mapNotNull { it.cliName?.takeIf { cli -> cli.isNotBlank() } }.forEach { cliName ->
            assertFalse(cliName.contains('-'), "cliName '$cliName' must use the spaced form ('profile import')")
        }
        assertFalse(
            specs.any { it.expression.contains("Arg(") },
            "no published signature may leak the data-class form",
        )
    }
}
