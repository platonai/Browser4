package ai.platon.pulsar.protocol.browser.driver.cdt.detail

import ai.platon.cdt.kt.protocol.types.network.Response
import ai.platon.pulsar.protocol.browser.driver.cdt.PulsarWebDriver

class CDPResponse(
    val driver: PulsarWebDriver,
    val request: CDPRequest,
    val response: Response
) {
    fun resolveBody(body: String?) {
    }
}
