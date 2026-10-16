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

import ai.platon.pulsar.captcha.CaptchaDetectionResult
import ai.platon.pulsar.captcha.CaptchaSolution
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.util.RawValue

/**
 * JSON payloads of the `captcha` tool domain.
 *
 * A tool returns a `Map` and the shared result renderer turns it into the JSON
 * text an MCP client receives, but it serializes with `pulsarObjectMapper()`,
 * whose inclusion is `NON_EMPTY`: a `null`, an empty string, an *empty*
 * collection and an empty object are all dropped from the output. A result
 * contract that promises "these keys are always there" therefore degrades
 * silently — `captcha_get_balance` documented `balance`/`providers` and shipped
 * neither (issue 123037#5), and a `captcha_detect` payload would lose `siteKey`
 * on every negative page.
 *
 * Two helpers keep the wire form honest without touching the shared renderer:
 *
 * - [jsonNull] — an explicit `null`: a [NullNode] is not an empty container, so
 *   it survives.
 * - [jsonLiteral] — a collection/object written verbatim as the JSON it already
 *   is, `[]` and `{}` included.
 *
 * Booleans and numbers need neither: Jackson's emptiness test covers `null`, empty
 * containers and empty strings, so `false` and `0` reach the client as they are,
 * and no field of these payloads is contractually blank.
 */
internal object CaptchaToolPayload {
    /**
     * Result contract of `captcha.detect`, as JSON Schema (the subset
     * `ToolResultValidator` understands).
     *
     * `siteKey`, `action` and `selector` are always present but are `null` on a
     * page without a widget; like every other nullable payload field of this
     * repo they are not typed here, because the validator reads an explicit
     * `null` as a type violation. They are documented in the tool help instead.
     */
    const val DETECT_OUTPUT_SCHEMA = """{"type":"object",""" +
        """"required":["isPresent","captchaType","confidence","metadata",""" +
        """"waitMillis","attempts","waited","elapsedMillis"],"properties":{""" +
        """"isPresent":{"type":"boolean"},"captchaType":{"type":"string"},""" +
        """"confidence":{"type":"number"},"metadata":{"type":"object"},""" +
        """"waitMillis":{"type":"integer"},"attempts":{"type":"integer"},""" +
        """"waited":{"type":"boolean"},"elapsedMillis":{"type":"integer"}}}"""

    /**
     * Result contract of `captcha.getBalance`.
     *
     * `balance` is `null` until a provider is configured, so it is left untyped
     * for the same reason as above; `providers` is always an array.
     */
    const val BALANCE_OUTPUT_SCHEMA = """{"type":"object",""" +
        """"required":["configured","providers"],"properties":{""" +
        """"configured":{"type":"boolean"},""" +
        """"providers":{"type":"array","items":{"type":"string"}},""" +
        """"message":{"type":"string"}}}"""

    /**
     * Result contract of `captcha.solve` and `captcha.solveImage`.
     *
     * A successful call always carries a non-blank `token`: an unsolved CAPTCHA
     * fails the call instead of returning a payload. `taskId` and `error` stay
     * untyped because they are nullable — providers do not all report a task id —
     * and are documented in the tool help.
     */
    const val SOLUTION_OUTPUT_SCHEMA = """{"type":"object",""" +
        """"required":["token","status","provider","solveTimeMs"],"properties":{""" +
        """"token":{"type":"string"},"status":{"type":"string"},""" +
        """"provider":{"type":"string"},"solveTimeMs":{"type":"integer"}}}"""

    private val mapper = pulsarObjectMapper()

    /**
     * An explicit JSON `null` that survives the shared mapper's `NON_EMPTY` inclusion.
     */
    fun jsonNull(): Any = NullNode.instance

    /**
     * [value] as the exact JSON literal it serializes to — an empty collection or
     * object is written as `[]`/`{}` instead of being pruned from the payload.
     */
    fun jsonLiteral(value: Any?): Any = RawValue(mapper.writeValueAsString(value))

    /**
     * Payload of `captcha.detect`: the whole detection result, always with the same
     * keys, so a client can address `isPresent`, `captchaType`, `siteKey`, `action`,
     * `selector`, `confidence` and `metadata` without scraping prose
     * (issue 123037#1).
     *
     * The timing keys answer "did this call look for a widget that had not rendered
     * yet" (issue 123037#9): `waitMillis` is the window the caller allowed,
     * `attempts`/`waited` say whether the chain had to poll, and `elapsedMillis` is
     * the wall-clock cost of the whole detection.
     *
     * @param result the detection to publish
     * @return the JSON-friendly payload, key order preserved
     */
    fun detect(result: CaptchaDetectionResult): Map<String, Any?> = linkedMapOf(
        "isPresent" to result.isPresent,
        "captchaType" to result.captchaType.name,
        "siteKey" to (result.siteKey ?: jsonNull()),
        "action" to (result.action ?: jsonNull()),
        "selector" to (result.selector ?: jsonNull()),
        "confidence" to result.confidence,
        "metadata" to jsonLiteral(metadataLiteral(result.metadata)),
        "waitMillis" to result.waitMillis,
        "attempts" to result.attempts,
        "waited" to (result.attempts > 1),
        "elapsedMillis" to result.elapsedMillis
    )

    /**
     * Payload of `captcha.getBalance`: the documented four keys, with `balance` as an
     * explicit `null` and `providers` as `[]` when no provider is configured, so an
     * unconfigured install is never mistaken for a renamed field (issue 123037#5).
     *
     * @param balance the averaged provider balance, or null when nothing is configured
     * @param providers the configured provider names, in priority order
     * @param message a configuration hint, only sent when no provider is configured
     */
    fun balance(
        configured: Boolean,
        balance: Double?,
        providers: List<String>,
        message: String? = null
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "configured" to configured,
        "balance" to (balance ?: jsonNull()),
        "providers" to jsonLiteral(providers)
    ).apply {
        if (message != null) {
            put("message", message)
        }
    }

    /**
     * Payload of `captcha.solve` and `captcha.solveImage`: the whole solution, always
     * with the same keys, so a client reads `token` instead of regex-scraping the
     * Kotlin `toString()` of [CaptchaSolution] (issue 123037#1 follow-up).
     *
     * `token`, `taskId` and `error` are explicit `null`s rather than absent keys
     * when they do not apply, so a client cannot mistake "no task id" for a renamed
     * field.
     *
     * @param solution the solution returned by the solver chain
     * @return the JSON-friendly payload, key order preserved
     */
    fun solution(solution: CaptchaSolution): Map<String, Any?> = linkedMapOf(
        "token" to (solution.token ?: jsonNull()),
        "taskId" to (solution.taskId ?: jsonNull()),
        "status" to solution.status.name,
        "provider" to solution.provider.name,
        "solveTimeMs" to solution.solveTimeMs,
        "error" to (solution.error ?: jsonNull())
    )

    /**
     * Detector metadata as a JSON object.
     *
     * The metadata map carries every value as a `String`, but
     * [CaptchaDetectionResult.METADATA_WIDGET_COUNT] is a count — it is always
     * written, as a JSON number, and defaults to `0` when no detector reported one
     * (a negative detection), so a consumer can rely on the key instead of having to
     * tell "no widget" apart from "this detector does not count widgets"
     * (issue 123037#8). Every other key keeps the raw probe value.
     */
    private fun metadataLiteral(metadata: Map<String, String>): Map<String, Any?> = buildMap {
        val widgetCount = metadata[CaptchaDetectionResult.METADATA_WIDGET_COUNT]
        put(CaptchaDetectionResult.METADATA_WIDGET_COUNT, widgetCount?.toIntOrNull() ?: 0)
        metadata.forEach { (key, value) ->
            if (key != CaptchaDetectionResult.METADATA_WIDGET_COUNT) {
                put(key, value)
            }
        }
    }
}
