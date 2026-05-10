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
package ai.platon.pulsar.protocol.browser.emulator

import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.protocol.browser.emulator.impl.BrowserResponseHandlerImpl

class BrowserResponseHandlerFactory(
        private val immutableConfig: ImmutableConfig
) {
    private val reflectedHandler by lazy {
        val clazz = immutableConfig.getClass(
                CapabilityTypes.BROWSER_RESPONSE_HANDLER, BrowserResponseHandlerImpl::class.java)
        clazz.constructors.first { it.parameters.size == 1 }
                .newInstance(immutableConfig) as BrowserResponseHandler
    }

    var specifiedHandler: BrowserResponseHandler? = null

    val eventHandler get() = specifiedHandler ?: reflectedHandler
}
