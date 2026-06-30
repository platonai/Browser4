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

/**
 * Interface for CAPTCHA solving service providers.
 *
 * Implementations handle communication with external CAPTCHA solving APIs
 * such as CapSolver, 2Captcha, and Anti-Captcha.
 */
interface CaptchaSolver {
    /** The service provider this solver represents */
    val serviceProvider: CaptchaServiceProvider

    /**
     * Submit a CAPTCHA for solving and wait for the result.
     *
     * @param request The CAPTCHA solve request with type, siteKey, pageUrl, etc.
     * @return The solution result including the token if solved
     */
    suspend fun solve(request: CaptchaSolveRequest): CaptchaSolution

    /**
     * Report a bad/failed solution to the provider (for refund/learning).
     *
     * @param solution The solution that was incorrect
     */
    suspend fun reportBad(solution: CaptchaSolution)

    /**
     * Get the current account balance for this provider.
     *
     * @return Balance in the provider's currency (usually USD)
     */
    suspend fun balance(): Double
}
