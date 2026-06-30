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
 * Result of a CAPTCHA solving attempt.
 *
 * @param token The solved CAPTCHA response token (g-recaptcha-response, h-captcha-response, etc.)
 * @param taskId The solver's task identifier (for reporting bad solutions)
 * @param status The final status of the solving attempt
 * @param provider Which service provider produced this result
 * @param solveTimeMs Time taken to solve in milliseconds
 * @param error Error message if solving failed
 */
data class CaptchaSolution(
    val token: String? = null,
    val taskId: String? = null,
    val status: CaptchaStatus,
    val provider: CaptchaServiceProvider = CaptchaServiceProvider.NONE,
    val solveTimeMs: Long = 0L,
    val error: String? = null
) {
    val isSolved: Boolean get() = status == CaptchaStatus.SOLVED && !token.isNullOrBlank()

    companion object {
        fun failed(provider: CaptchaServiceProvider, error: String, solveTimeMs: Long = 0L) =
            CaptchaSolution(
                status = CaptchaStatus.FAILED,
                provider = provider,
                error = error,
                solveTimeMs = solveTimeMs
            )

        fun timeout(provider: CaptchaServiceProvider, solveTimeMs: Long = 0L) =
            CaptchaSolution(
                status = CaptchaStatus.TIMEOUT,
                provider = provider,
                solveTimeMs = solveTimeMs
            )

        fun solved(provider: CaptchaServiceProvider, token: String, taskId: String?, solveTimeMs: Long = 0L) =
            CaptchaSolution(
                token = token,
                taskId = taskId,
                status = CaptchaStatus.SOLVED,
                provider = provider,
                solveTimeMs = solveTimeMs
            )
    }
}
