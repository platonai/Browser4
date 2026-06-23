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
 * Request to solve a CAPTCHA.
 *
 * @param type The type of CAPTCHA to solve
 * @param siteKey The site key extracted from the CAPTCHA widget
 * @param pageUrl The URL of the page containing the CAPTCHA
 * @param metadata Additional provider-specific parameters (e.g., action, s, cdata)
 * @param imageData Base64-encoded image data (for image CAPTCHAs)
 * @param proxy Proxy string to route solving traffic through
 */
data class CaptchaSolveRequest(
    val type: CaptchaType,
    val siteKey: String,
    val pageUrl: String = "",
    val metadata: Map<String, String> = emptyMap(),
    val imageData: ByteArray? = null,
    val proxy: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CaptchaSolveRequest) return false
        return type == other.type &&
            siteKey == other.siteKey &&
            pageUrl == other.pageUrl &&
            metadata == other.metadata &&
            (imageData?.contentEquals(other.imageData) ?: (other.imageData == null)) &&
            proxy == other.proxy
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + siteKey.hashCode()
        result = 31 * result + pageUrl.hashCode()
        result = 31 * result + metadata.hashCode()
        result = 31 * result + (imageData?.contentHashCode() ?: 0)
        result = 31 * result + (proxy?.hashCode() ?: 0)
        return result
    }
}
