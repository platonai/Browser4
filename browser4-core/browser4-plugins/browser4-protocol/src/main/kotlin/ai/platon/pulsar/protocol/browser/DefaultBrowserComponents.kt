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
package ai.platon.pulsar.protocol.browser

//class DefaultBrowserManager(conf: ImmutableConfig) : BasicBrowserManager(DefaultBrowserFactory(conf), conf)
//
//class DefaultWebDriverPoolManager(conf: ImmutableConfig) :
//    WebDriverPoolManager(
//        DefaultBrowserManager(conf),
//        conf, suppressMetrics = true
//    )
//
//class DefaultBrowserEmulator(
//    driverPoolManager: WebDriverPoolManager,
//    conf: ImmutableConfig
//) : InteractiveBrowserEmulator(
//    driverPoolManager,
//    BrowserResponseHandlerImpl(conf),
//    conf
//)

//class DefaultPrivacyManagedBrowserFetcher(
//    browserManager: BrowserManager,
//    browserEmulator: DefaultBrowserEmulator,
//    privacyManager: BrowserPrivacyManager,
//    conf: ImmutableConfig,
//    closeCascaded: Boolean = true
//) : PrivacyManagedBrowserFetcher(
//    browserManager,
//    privacyManager,
//    browserEmulator,
//    conf,
//    closeCascaded
//) {
//    constructor(
//        conf: ImmutableConfig,
//        driverPoolManager: WebDriverPoolManager = DefaultWebDriverPoolManager(conf)
//    ) : this(
//        driverPoolManager.browserManager,
//        DefaultBrowserEmulator(driverPoolManager, conf),
//        MultiPrivacyContextManager(driverPoolManager, conf),
//        conf,
//        closeCascaded = true
//    )
//}
//
//class DefaultBrowserComponents(val conf: ImmutableConfig = ImmutableConfig.DEFAULT) {
//    private val logger = getLogger(this)
//
//    private val cache = ObjectCache.get(conf)
//
//    val incognitoBrowserFetcher: IncognitoBrowserFetcher = cache.computeIfAbsent<IncognitoBrowserFetcher> {
//        logger.info("Creating DefaultPrivacyManagedBrowserFetcher, the default one should be used only for test and develop")
//        DefaultPrivacyManagedBrowserFetcher(conf)
//    }
//
//    val privacyManager: PrivacyManager
//        get() = incognitoBrowserFetcher.privacyManager
//
//    val driverPoolManager: WebDriverPoolManager
//        get() = (privacyManager as BrowserPrivacyManager).driverPoolManager
//
//    val browserManager: BrowserManager
//        get() = (privacyManager as BrowserPrivacyManager).browserManager
//}
