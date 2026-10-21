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

import ai.platon.pulsar.core.api.WebDriver
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Offline [WebDriver] stand-in for the CAPTCHA detectors.
 *
 * The detectors only ever call `evaluateValue(script)`, so a reflective proxy is
 * enough: [probe] answers a detection script with the JSON a real page would
 * return. Nothing here touches a browser or the network, which is what keeps the
 * captcha module's tests runnable in the PR gate.
 *
 * @param probe maps a JavaScript expression to the value the page would produce
 */
internal class FakePageDriver(private val probe: (String) -> Any?) {
    /** How many page probes the code under test issued. */
    var probeCount: Int = 0
        private set

    /** The driver handed to the code under test. */
    val driver: WebDriver = Proxy.newProxyInstance(
        WebDriver::class.java.classLoader,
        arrayOf(WebDriver::class.java),
        InvocationHandler { proxy, method, args -> invoke(proxy, method, args) }
    ) as WebDriver

    private fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
        "evaluateValue", "evaluate", "evaluateDetail", "evaluateValueDetail" -> {
            ++probeCount
            probe(args?.firstOrNull() as? String ?: "")
        }
        "currentUrl", "url", "documentURI", "baseURI" -> URL
        "toString" -> "FakePageDriver"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.firstOrNull()
        else -> primitiveDefault(method.returnType)
    }

    private fun primitiveDefault(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0f
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> ' '
        else -> null
    }

    companion object {
        private const val URL = "https://example.com/"

        /**
         * A page whose probes answer with the JSON for the requested CAPTCHA types.
         *
         * A probe that is not mentioned finds nothing — it answers [NOT_PRESENT],
         * like a real page's detection script does. The detection scripts of the
         * four detectors are told apart by the globals they test for.
         */
        fun page(
            recaptcha: String = NOT_PRESENT,
            hcaptcha: String = NOT_PRESENT,
            turnstile: String = NOT_PRESENT,
            image: String = NOT_PRESENT
        ): (String) -> Any? = { script ->
            when {
                script.contains("grecaptcha") -> recaptcha
                script.contains("hcaptcha") -> hcaptcha
                script.contains("turnstile") -> turnstile
                script.contains("captchaKeywords") -> image
                else -> null
            }
        }

        /** Probe answer of a page without any CAPTCHA widget. */
        const val NOT_PRESENT = """{"present":false}"""

        /** Probe answer of a page showing one reCAPTCHA v2 widget. */
        const val RECAPTCHA_V2 =
            """{"present":true,"type":"RECAPTCHA_V2","siteKey":"6Le-demo","action":null,"widgetCount":1}"""

        /** Probe answer of a page showing two hCaptcha widgets. */
        const val HCAPTCHA = """{"present":true,"siteKey":"a5f74b19-demo","widgetCount":2}"""

        /** Probe answer of a page showing three Turnstile widgets, one carrying cdata. */
        const val TURNSTILE =
            """{"present":true,"siteKey":"1x00000000000000000000AA","action":"verify","cdata":"cdata-1","widgetCount":3}"""

        /** Probe answer of a page showing an image CAPTCHA. */
        const val IMAGE =
            """{"present":true,"imageSelector":"#captcha-img","inputSelector":"#captcha-input","confidence":0.8}"""
    }
}
