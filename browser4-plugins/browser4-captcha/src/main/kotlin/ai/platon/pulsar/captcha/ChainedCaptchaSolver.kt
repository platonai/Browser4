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

    override val serviceProvider: CaptchaServiceProvider
        get() = CaptchaServiceProvider.NONE

    /**
     * Try each solver in order. Returns the first successful solution.
     * If all solvers fail, returns a FAILED solution.
     */
    override suspend fun solve(request: CaptchaSolveRequest): CaptchaSolution {
        for (solver in solvers) {
            try {
                val solution = solver.solve(request)
                if (solution.isSolved) {
                    return solution
                }
                logger.debug("Solver {} failed: {}", solver.serviceProvider, solution.error)
            } catch (e: Exception) {
                logger.warn("Solver {} threw exception: {}", solver.serviceProvider, e.message)
            }
        }

        return CaptchaSolution.failed(
            CaptchaServiceProvider.NONE,
            "All ${solvers.size} solver(s) failed to solve the CAPTCHA"
        )
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
}
