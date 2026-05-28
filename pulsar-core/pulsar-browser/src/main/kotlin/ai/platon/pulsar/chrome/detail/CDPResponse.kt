package ai.platon.pulsar.chrome.detail

import ai.platon.cdt.kt.protocol.types.network.Response
import ai.platon.pulsar.chrome.PulsarWebDriver

class CDPResponse(
    val driver: PulsarWebDriver,
    val request: CDPRequest,
    val response: Response
) {
    fun resolveBody(body: String?) {
    }
}
