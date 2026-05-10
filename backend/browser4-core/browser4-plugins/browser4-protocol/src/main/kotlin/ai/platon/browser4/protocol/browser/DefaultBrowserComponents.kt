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
package ai.platon.browser4.protocol.browser

import ai.platon.pulsar.common.ObjectCache
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager
import ai.platon.pulsar.protocol.browser.emulator.BrowserEmulator
import ai.platon.pulsar.protocol.browser.emulator.IncognitoBrowserFetcher
import ai.platon.pulsar.protocol.browser.emulator.context.BrowserPrivacyManager
import ai.platon.pulsar.protocol.browser.emulator.context.MultiPrivacyContextManager
import ai.platon.pulsar.protocol.browser.emulator.impl.BrowserResponseHandlerImpl
import ai.platon.pulsar.protocol.browser.emulator.impl.InteractiveBrowserEmulator
import ai.platon.pulsar.protocol.browser.emulator.impl.PrivacyManagedBrowserFetcher
import ai.platon.pulsar.protocol.browser.impl.BasicBrowserManager
import ai.platon.pulsar.protocol.browser.impl.DefaultBrowserFactory
import ai.platon.pulsar.skeleton.workflow.fetch.driver.BrowserManager

class DefaultBrowserManager(conf: ImmutableConfig) : BasicBrowserManager(DefaultBrowserFactory(conf), conf)

class DefaultWebDriverPoolManager(conf: ImmutableConfig) :
    WebDriverPoolManager(
        DefaultBrowserManager(conf),
        conf, suppressMetrics = true
    )

class DefaultBrowserEmulator(
    driverPoolManager: WebDriverPoolManager,
    conf: ImmutableConfig
) : InteractiveBrowserEmulator(
    driverPoolManager,
    BrowserResponseHandlerImpl(conf),
    conf
)

class DefaultPrivacyManagedBrowserFetcher(
    browserManager: BrowserManager,
    browserEmulator: BrowserEmulator,
    privacyManager: BrowserPrivacyManager,
    conf: ImmutableConfig,
    closeCascaded: Boolean = true
) : PrivacyManagedBrowserFetcher(
    browserManager,
    privacyManager,
    browserEmulator,
    conf,
    closeCascaded
) {
    constructor(
        conf: ImmutableConfig,
        driverPoolManager: WebDriverPoolManager = DefaultWebDriverPoolManager(conf)
    ) : this(
        driverPoolManager.browserManager,
        DefaultBrowserEmulator(driverPoolManager, conf),
        MultiPrivacyContextManager(driverPoolManager, conf),
        conf,
        closeCascaded = true
    )
}

class DefaultBrowserComponents(val conf: ImmutableConfig = ImmutableConfig.DEFAULT) {
    private val logger = getLogger(this)

    private val cache = ObjectCache.get(conf)

    val incognitoBrowserFetcher: IncognitoBrowserFetcher = cache.computeIfAbsent<IncognitoBrowserFetcher> {
        logger.info("Creating DefaultPrivacyManagedBrowserFetcher")
        DefaultPrivacyManagedBrowserFetcher(conf)
    }

    val privacyManager: BrowserPrivacyManager
        get() = incognitoBrowserFetcher.privacyManager

    val driverPoolManager: WebDriverPoolManager
        get() = privacyManager.driverPoolManager

    val browserManager: BrowserManager
        get() = privacyManager.browserManager
}
