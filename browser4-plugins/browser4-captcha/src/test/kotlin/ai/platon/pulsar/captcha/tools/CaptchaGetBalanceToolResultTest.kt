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
 * The documented `captcha_get_balance` contract is a map with `configured`,
 * `balance`, `providers` and `message`. Issue 123037#5 reported that an
 * unconfigured install shipped only two of them, because the shared result
 * serializer drops `null` and empty-collection values — so these tests assert the
 * JSON text a client actually receives, not the map the executor builds.
 */
@DisplayName("captcha.getBalance tool result")
class CaptchaGetBalanceToolResultTest {

    private val conf = ImmutableConfig()

    @Test
    @DisplayName("an unconfigured install reports configured=false, balance=null and providers=[]")
    fun unconfiguredInstallKeepsTheDocumentedKeys() {
        val json = balanceJson()

        assertEquals(
            setOf("configured", "balance", "providers", "message"),
            json.fieldNames().asSequence().toSet()
        )
        assertFalse(json["configured"].asBoolean())
        assertTrue(json["balance"].isNull, "balance must be an explicit null: $json")
        assertTrue(json["providers"].isArray, "providers must be an array: $json")
        assertEquals(0, json["providers"].size())
        assertEquals(CaptchaToolExecutor.NO_PROVIDER_MESSAGE, json["message"].asText())
    }

    @Test
    @DisplayName("a configured solver reports its balance and provider names")
    fun configuredSolverReportsBalanceAndProviders() {
        val json = balanceJson(CaptchaServiceProvider.TWO_CAPTCHA, solverBalance = 12.5)

        assertEquals(
            setOf("configured", "balance", "providers"),
            json.fieldNames().asSequence().toSet()
        )
        assertTrue(json["configured"].asBoolean())
        assertEquals(12.5, json["balance"].asDouble(), 0.0001)
        assertEquals(listOf("TWO_CAPTCHA"), json["providers"].map { it.asText() })
        assertFalse(json.has("message"), "the hint belongs to the unconfigured state only: $json")
    }

    @Test
    @DisplayName("both states honour the outputSchema the tool declares")
    fun bothStatesHonourTheDeclaredOutputSchema() {
        val spec = executor(CaptchaServiceProvider.CAPSOLVER, solverBalance = 1.0).getToolSpecs()["getBalance"]!!
        assertNotNull(spec.outputSchema, "getBalance must declare its result contract")

        val payloads = listOf(balanceJson(), balanceJson(CaptchaServiceProvider.ANTI_CAPTCHA, solverBalance = 3.0))
        payloads.forEach { json ->
            assertEquals(
                emptyList<ToolResultValidator.Issue>(),
                ToolResultValidator.validate(spec, ToolResultValidator.parse(json.toString())),
                "payload violates the declared schema: $json"
            )
        }
    }

    private fun balanceJson(
        provider: CaptchaServiceProvider? = null,
        solverBalance: Double? = null
    ): JsonNode {
        val evaluate = evaluate(executor(provider, solverBalance))
        assertNotNull(evaluate.value, "getBalance must return a payload")
        return pulsarObjectMapper().readTree(ToolResultTextRenderer.render(evaluate))
    }

    private fun executor(provider: CaptchaServiceProvider?, solverBalance: Double?): CaptchaToolExecutor {
        val solver = ChainedCaptchaSolver(conf)
        if (provider != null) {
            solver.addLast(FakeSolver(provider, solverBalance ?: 0.0))
        }

        return CaptchaToolExecutor(
            captchaSolver = solver,
            captchaDetector = ChainedCaptchaDetector(conf),
            tokenInjectors = emptyMap(),
            config = CaptchaConfig()
        )
    }

    private fun evaluate(executor: CaptchaToolExecutor): TcEvaluate = runBlocking {
        executor.callFunctionOn(ToolCall("captcha", "getBalance"), Any())
    }

    /** A solver that never talks to a service: the payload shape is what is under test. */
    private class FakeSolver(
        override val serviceProvider: CaptchaServiceProvider,
        private val balanceValue: Double
    ) : CaptchaSolver {
        override suspend fun solve(request: CaptchaSolveRequest): CaptchaSolution =
            CaptchaSolution.failed(serviceProvider, "not implemented")

        override suspend fun reportBad(solution: CaptchaSolution) = Unit

        override suspend fun balance(): Double = balanceValue
    }
}
