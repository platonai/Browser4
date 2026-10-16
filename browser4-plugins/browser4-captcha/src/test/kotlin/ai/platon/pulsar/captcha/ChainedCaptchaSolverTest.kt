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
package ai.platon.pulsar.captcha

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.config.MutableConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The chain is the only thing a caller hears from when every provider fails; a bare
 * "all N solver(s) failed" left the actual reason (rejected siteKey, quota, bad key)
 * in the server log, which is the failure mode AGENTS.md's "error messages must
 * surface the real cause to the CLI/user" rule is about.
 */
@DisplayName("captcha solver chain failures")
class ChainedCaptchaSolverTest {

    private val request = CaptchaSolveRequest(
        type = CaptchaType.HCAPTCHA,
        siteKey = "a5f74b19-demo",
        pageUrl = "https://example.com/captcha"
    )

    @Test
    @DisplayName("a single failing provider is named with its reason")
    fun failingProviderIsNamedWithItsReason() {
        val chain = chainOf(FailingSolver(CaptchaServiceProvider.CAPSOLVER, "ERROR_WRONG_USER_KEY"))

        val solution = runBlocking { chain.solve(request) }

        assertFalse(solution.isSolved)
        assertEquals(CaptchaServiceProvider.NONE, solution.provider)
        val error = solution.error!!
        assertTrue(error.contains("All 1 solver(s) failed"), error)
        assertTrue(error.contains("last failure (CAPSOLVER): ERROR_WRONG_USER_KEY"), error)
    }

    @Test
    @DisplayName("a solver that throws is reported too")
    fun throwingSolverIsReported() {
        val chain = chainOf(ThrowingSolver(CaptchaServiceProvider.TWO_CAPTCHA, "connection reset"))

        val error = runBlocking { chain.solve(request) }.error!!

        assertTrue(error.contains("last failure (TWO_CAPTCHA): connection reset"), error)
    }

    @Test
    @DisplayName("the last failure of a chain is the one reported")
    fun lastFailureOfTheChainIsReported() {
        val chain = chainOf(
            FailingSolver(CaptchaServiceProvider.CAPSOLVER, "ERROR_ZERO_BALANCE"),
            FailingSolver(CaptchaServiceProvider.ANTI_CAPTCHA, "ERROR_KEY_DENIED_IP")
        )

        val error = runBlocking { chain.solve(request) }.error!!

        assertTrue(error.contains("All 2 solver(s) failed"), error)
        assertTrue(error.contains("last failure (ANTI_CAPTCHA): ERROR_KEY_DENIED_IP"), error)
        assertFalse(error.contains("ERROR_ZERO_BALANCE"), "only the last failure is carried: $error")
    }

    @Test
    @DisplayName("a configured API key echoed by a provider is redacted")
    fun configuredApiKeyIsRedacted() {
        val conf = MutableConfig()
        conf["captcha.capsolver.api.key"] = API_KEY
        val chain = ChainedCaptchaSolver(conf)
        chain.addLast(FailingSolver(CaptchaServiceProvider.CAPSOLVER, "invalid clientKey: $API_KEY"))

        val error = runBlocking { chain.solve(request) }.error!!

        assertFalse(error.contains(API_KEY), "the API key must not reach the caller: $error")
        assertTrue(error.contains("invalid clientKey: ***"), error)
    }

    @Test
    @DisplayName("a successful solver still short-circuits the chain")
    fun successfulSolverShortCircuitsTheChain() {
        val failing = FailingSolver(CaptchaServiceProvider.CAPSOLVER, "ERROR_ZERO_BALANCE")
        val working = SolvedSolver(CaptchaServiceProvider.TWO_CAPTCHA)
        val chain = chainOf(failing, working)

        val solution = runBlocking { chain.solve(request) }

        assertTrue(solution.isSolved)
        assertEquals("token-from-TWO_CAPTCHA", solution.token)
        assertEquals(1, failing.calls)
    }

    @Test
    @DisplayName("an empty chain reports zero solvers and no failure detail")
    fun emptyChainReportsNoFailureDetail() {
        val error = runBlocking { chainOf().solve(request) }.error!!

        assertEquals("All 0 solver(s) failed to solve the CAPTCHA", error)
    }

    private fun chainOf(vararg solvers: CaptchaSolver): ChainedCaptchaSolver {
        val chain = ChainedCaptchaSolver(ImmutableConfig())
        solvers.forEach { chain.addLast(it) }
        return chain
    }

    private abstract class BaseSolver(override val serviceProvider: CaptchaServiceProvider) : CaptchaSolver {
        override suspend fun reportBad(solution: CaptchaSolution) = Unit

        override suspend fun balance(): Double = 0.0
    }

    private class FailingSolver(
        provider: CaptchaServiceProvider,
        private val reason: String
    ) : BaseSolver(provider) {
        var calls: Int = 0
            private set

        override suspend fun solve(request: CaptchaSolveRequest): CaptchaSolution {
            ++calls
            return CaptchaSolution.failed(serviceProvider, reason)
        }
    }

    private class ThrowingSolver(
        provider: CaptchaServiceProvider,
        private val reason: String
    ) : BaseSolver(provider) {
        override suspend fun solve(request: CaptchaSolveRequest): CaptchaSolution =
            throw IllegalStateException(reason)
    }

    private class SolvedSolver(provider: CaptchaServiceProvider) : BaseSolver(provider) {
        override suspend fun solve(request: CaptchaSolveRequest): CaptchaSolution =
            CaptchaSolution.solved(serviceProvider, "token-from-${serviceProvider.name}", "task-1", 10)
    }

    companion object {
        private const val API_KEY = "cs-secret-key-123456"
    }
}
