package ai.platon.browser4.chrome.detail

import ai.platon.browser4.chrome.PulsarWebDriver
import ai.platon.cdt.kt.protocol.types.network.Response

class CDPResponse(
    val driver: PulsarWebDriver,
    val request: CDPRequest,
    val response: Response
) {
    fun resolveBody(body: String?) {
    }
}
