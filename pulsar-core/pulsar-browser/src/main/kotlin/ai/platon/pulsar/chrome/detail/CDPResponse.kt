package ai.platon.pulsar.chrome.detail

import ai.platon.cdt.kt.protocol.types.network.Response
import ai.platon.pulsar.browser.impl.BrowserProtocol

class CDPResponse(
    val browserProtocol: BrowserProtocol,
    val request: CDPRequest,
    val response: Response
) {
    fun resolveBody(body: String?) {
    }
}
