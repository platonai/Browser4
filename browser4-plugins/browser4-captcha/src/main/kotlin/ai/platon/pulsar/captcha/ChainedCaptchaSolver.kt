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
import ai.platon.pulsar.common.getLogger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Chain of [CaptchaSolver] instances that tries each solver in order until
 * one returns a solution.
 *
 * Follows the same chain pattern as [ai.platon.pulsar.protocol.browser.emulator.util.ChainedPageCategorySniffer]
 * and [ai.platon.pulsar.protocol.browser.emulator.util.ChainedHtmlIntegrityChecker].
 */
open class ChainedCaptchaSolver(val conf: ImmutableConfig) : CaptchaSolver {
    private val logger = getLogger(ChainedCaptchaSolver::class)

    private val solvers = CopyOnWriteArrayList<CaptchaSolver>()

    /**
     * API keys a provider might echo back in its error text, so they can be redacted
     * from the message a caller sees.
     */
    private val secretKeys: List<String> = API_KEY_PROPERTIES
        .mapNotNull { conf.get(it)?.trim()?.takeIf { key -> key.isNotEmpty() } }

    override val serviceProvider: CaptchaServiceProvider
        get() = CaptchaServiceProvider.NONE

    /**
     * Try each solver in order. Returns the first successful solution.
     * If all solvers fail, returns a FAILED solution whose message names the provider
     * that failed last and why — a caller that only reads "all solvers failed" has to
     * open the server log to learn whether the siteKey, the quota or the API key was
     * rejected.
     */
    override suspend fun solve(request: CaptchaSolveRequest): CaptchaSolution {
        var lastFailure: String? = null

        for (solver in solvers) {
            try {
                val solution = solver.solve(request)
                if (solution.isSolved) {
                    return solution
                }
                logger.debug("Solver {} failed: {}", solver.serviceProvider, solution.error)
                lastFailure = failureOf(solver, solution.error)
            } catch (e: Exception) {
                logger.warn("Solver {} threw exception: {}", solver.serviceProvider, e.message)
                lastFailure = failureOf(solver, e.message)
            }
        }

        val message = buildString {
            append("All ${solvers.size} solver(s) failed to solve the CAPTCHA")
            lastFailure?.let { append("; last failure $it") }
        }
        return CaptchaSolution.failed(CaptchaServiceProvider.NONE, message)
    }

    /**
     * One solver failure as a caller may see it: the provider and its reason, with
     * configured API keys redacted (the raw text stays in the log above).
     */
    private fun failureOf(solver: CaptchaSolver, reason: String?): String {
        val text = reason?.trim().orEmpty().ifEmpty { "no error details" }
        val redacted = secretKeys.fold(text) { acc, key -> acc.replace(key, REDACTED_SECRET) }
        return "(${solver.serviceProvider}): ${redacted.take(MAX_FAILURE_LENGTH)}"
    }

    /**
     * Report a bad solution to the provider that produced it.
     */
    override suspend fun reportBad(solution: CaptchaSolution) {
        solvers.firstOrNull { it.serviceProvider == solution.provider }
            ?.reportBad(solution)
            ?: logger.warn("Cannot report bad: provider {} not found", solution.provider)
    }

    /**
     * Average balance across all configured solvers.
     */
    override suspend fun balance(): Double {
        val balances = solvers.mapNotNull {
            try {
                it.balance()
            } catch (e: Exception) {
                logger.debug("Failed to get balance for {}: {}", it.serviceProvider, e.message)
                null
            }
        }
        return if (balances.isEmpty()) 0.0 else balances.average()
    }

    /**
     * Add a solver at the front (highest priority).
     */
    fun addFirst(solver: CaptchaSolver): ChainedCaptchaSolver {
        solvers.add(0, solver)
        return this
    }

    /**
     * Add a solver at the end (lowest priority, fallback).
     */
    fun addLast(solver: CaptchaSolver): ChainedCaptchaSolver {
        solvers.add(solver)
        return this
    }

    /**
     * Remove a solver from the chain.
     */
    fun remove(solver: CaptchaSolver) {
        solvers.remove(solver)
    }

    /**
     * Number of solvers in the chain.
     */
    val size: Int get() = solvers.size

    /**
     * The providers of the solvers currently in the chain, in priority order.
     * An empty list means no CAPTCHA solving provider is configured.
     */
    val providers: List<CaptchaServiceProvider> get() = solvers.map { it.serviceProvider }

    companion object {
        /** Config keys holding provider credentials, checked before a message leaves the chain. */
        private val API_KEY_PROPERTIES = listOf(
            "captcha.capsolver.api.key",
            "captcha.twocaptcha.api.key",
            "captcha.anticaptcha.api.key"
        )

        private const val REDACTED_SECRET = "***"

        /**
         * Longest failure reason carried in a chain message: the message is a
         * headline for a caller, the full text stays in the log.
         */
        private const val MAX_FAILURE_LENGTH = 200
    }
}
