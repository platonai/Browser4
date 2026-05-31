package ai.platon.pulsar.protocol.browser

import ai.platon.pulsar.protocol.crowd.ForwardingProtocol
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.protocol.browser.emulator.IncognitoBrowserFetcher
import ai.platon.pulsar.skeleton.workflow.protocol.Response

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
class BrowserEmulatorProtocol(
    val browserEmulator: IncognitoBrowserFetcher
) : ForwardingProtocol() {

    override val name: String = "browser"

    @Throws(Exception::class)
    override suspend fun getResponseDeferred(page: WebPage, followRedirects: Boolean): Response? {
        require(page.isNotInternal) { "Unexpected internal page ${page.url}" }
        return super.getResponseDeferred(page, followRedirects) ?: browserEmulator.fetchContentDeferred(page)
    }

    override fun reset() {
        browserEmulator.reset()
    }

    override fun cancel(page: WebPage) {
        browserEmulator.cancel(page)
    }

    override fun cancelAll() {
        browserEmulator.cancelAll()
    }
}
