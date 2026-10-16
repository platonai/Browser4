/**
 * Copyright (c) Vincent Zhang, ivincent.zhang@gmail.com, Platon.AI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.captcha.tools

import ai.platon.pulsar.agentic.model.TcEvaluate
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.tools.ToolResultTextRenderer
import ai.platon.pulsar.agentic.tools.specs.ToolResultValidator
import ai.platon.pulsar.captcha.CaptchaConfig
import ai.platon.pulsar.captcha.CaptchaServiceProvider
import ai.platon.pulsar.captcha.CaptchaSolution
import ai.platon.pulsar.captcha.CaptchaSolveRequest
import ai.platon.pulsar.captcha.CaptchaSolver
import ai.platon.pulsar.captcha.ChainedCaptchaSolver
import ai.platon.pulsar.captcha.detection.ChainedCaptchaDetector
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `captcha.solve` and `captcha.solve_image` used to hand the `CaptchaSolution` data
 * class to the result renderer, which has no mapping for a domain object — a client
 * received `{"type":…,"description":"CaptchaSolution(…)"}` instead of JSON
 * (issue 123037#1 follow-up). These tests drive the real executor and the real
 * renderer and assert on the JSON text.
 *
 * The failure path is covered too: an unsolved CAPTCHA fails the call (the payload
 * contract only describes successful solves), and an unconfigured install fails
 * with the configuration hint.
 */
@DisplayName("captcha.solve tool result")
class CaptchaSolveToolResultTest {

    private val conf = ImmutableConfig()

    private val solveArgs = mutableMapOf<String, Any?>(
        "type" to "HCAPTCHA",
        "siteKey" to "a5f74b19-demo",
        "pageUrl" to "https://example.com/captcha"
    )

    @Test
    @DisplayName("a solved CAPTCHA is published as JSON, never as a toString envelope")
    fun solvedCaptchaIsPublishedAsJson() {
        val json = solveJson(solution(CaptchaServiceProvider.TWO_CAPTCHA))

        assertEquals(
            setOf("token", "taskId", "status", "provider", "solveTimeMs", "error"),
            json.fieldNames().asSequence().toSet()
        )
        assertEquals("token-abc123", json["token"].asText())
        assertEquals("task-1", json["taskId"].asText())
        assertEquals("SOLVED", json["status"].asText())
        assertEquals("TWO_CAPTCHA", json["provider"].asText())
        assertEquals(1_234, json["solveTimeMs"].asLong())
        assertTrue(json["error"].isNull, "a successful solve has no error: $json")
        assertFalse(json.has("description"), "the toString envelope must be gone: $json")
    }

    @Test
    @DisplayName("a provider without a task id still reports the key, as an explicit null")
    fun providerWithoutTaskIdKeepsTheDocumentedKey() {
        val json = solveJson(noTaskIdSolution())

        assertTrue(json.has("taskId"), "taskId must not vanish: $json")
        assertTrue(json["taskId"].isNull, "taskId must be an explicit null: $json")
        assertEquals("token-abc123", json["token"].asText())
        assertEquals("SOLVED", json["status"].asText())
    }

    @Test
    @DisplayName("solveImage returns the same JSON object as solve")
    fun imageSolveReturnsTheSameJsonContract() {
        val evaluate = evaluate(
            executor(noTaskIdSolution()),
            ToolCall("captcha", "solveImage", mutableMapOf("base64Image" to "aGk=", "caseSensitive" to true))
        )
        assertNotNull(evaluate.value, "solveImage must return a payload")

        val json = pulsarObjectMapper().readTree(ToolResultTextRenderer.render(evaluate))
        assertEquals(
            setOf("token", "taskId", "status", "provider", "solveTimeMs", "error"),
            json.fieldNames().asSequence().toSet()
        )
        assertEquals("token-abc123", json["token"].asText())
        assertTrue(json["error"].isNull, json.toString())
    }

    @Test
    @DisplayName("a successful payload honours the outputSchema both solve tools declare")
    fun payloadHonoursTheDeclaredOutputSchema() {
        val executor = executor(solution(CaptchaServiceProvider.CAPSOLVER))
        val schemas = listOf("solve", "solveImage").map { method ->
            val spec = executor.getToolSpecs()[method]!!
            assertNotNull(spec.outputSchema, "$method must declare its result contract")
            spec
        }

        val payloads = listOf(solveJson(solution(CaptchaServiceProvider.CAPSOLVER)), solveJson(noTaskIdSolution()))
        schemas.forEach { spec ->
            payloads.forEach { json ->
                assertEquals(
                    emptyList<ToolResultValidator.Issue>(),
                    ToolResultValidator.validate(spec, ToolResultValidator.parse(json.toString())),
                    "payload violates ${spec.expression}: $json"
                )
            }
        }
    }

    @Test
    @DisplayName("a failed solve fails the call and names the provider's reason")
    fun failedSolveFailsTheCall() {
        val evaluate = evaluate(
            executor(CaptchaSolution.failed(CaptchaServiceProvider.TWO_CAPTCHA, "provider rejected the siteKey")),
            ToolCall("captcha", "solve", solveArgs)
        )

        assertNotNull(evaluate.exception, "a failed solve must not look like a successful call")
        // The provider's own reason must reach the caller, not only the server log:
        // the chain used to report nothing but "all N solver(s) failed".
        val message = evaluate.exception!!.cause!!.message!!
        assertTrue(message.contains("CAPTCHA solving failed"), message)
        assertTrue(message.contains("status=FAILED"), message)
        assertTrue(message.contains("provider rejected the siteKey"), message)
        assertTrue(message.contains("TWO_CAPTCHA"), message)
    }

    @Test
    @DisplayName("an unconfigured install fails the call with the configuration hint")
    fun unconfiguredInstallFailsTheCall() {
        val evaluate = evaluate(
            CaptchaToolExecutor(
                captchaSolver = ChainedCaptchaSolver(conf),
                captchaDetector = ChainedCaptchaDetector(conf),
                tokenInjectors = emptyMap(),
                config = CaptchaConfig()
            ),
            ToolCall("captcha", "solve", solveArgs)
        )

        assertNotNull(evaluate.exception)
        assertEquals(CaptchaToolExecutor.NO_PROVIDER_MESSAGE, evaluate.exception!!.cause!!.message)
    }

    private fun solveJson(solution: CaptchaSolution): JsonNode {
        val evaluate = evaluate(executor(solution), ToolCall("captcha", "solve", solveArgs))
        assertNotNull(evaluate.value, "solve must return a payload")
        return pulsarObjectMapper().readTree(ToolResultTextRenderer.render(evaluate))
    }

    private fun executor(solution: CaptchaSolution): CaptchaToolExecutor {
        val solver = ChainedCaptchaSolver(conf)
        solver.addLast(FakeSolver(solution))

        return CaptchaToolExecutor(
            captchaSolver = solver,
            captchaDetector = ChainedCaptchaDetector(conf),
            tokenInjectors = emptyMap(),
            config = CaptchaConfig()
        )
    }

    private fun evaluate(executor: CaptchaToolExecutor, call: ToolCall): TcEvaluate = runBlocking {
        executor.callFunctionOn(call, Any())
    }

    private fun solution(provider: CaptchaServiceProvider) = CaptchaSolution.solved(
        provider = provider,
        token = "token-abc123",
        taskId = "task-1",
        solveTimeMs = 1_234
    )

    private fun noTaskIdSolution() = CaptchaSolution.solved(
        provider = CaptchaServiceProvider.CAPSOLVER,
        token = "token-abc123",
        taskId = null,
        solveTimeMs = 42
    )

    /** A solver that answers with a canned solution: the payload shape is under test. */
    private class FakeSolver(private val outcome: CaptchaSolution) : CaptchaSolver {
        override val serviceProvider: CaptchaServiceProvider get() = outcome.provider

        override suspend fun solve(request: CaptchaSolveRequest): CaptchaSolution = outcome

        override suspend fun reportBad(solution: CaptchaSolution) = Unit

        override suspend fun balance(): Double = 0.0
    }
}
