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

import ai.platon.pulsar.protocol.browser.emulator.context.BrowserPrivacyManager
import ai.platon.pulsar.skeleton.workflow.fetch.Fetcher
import ai.platon.pulsar.skeleton.workflow.fetch.WebDriverFetcher
import ai.platon.pulsar.skeleton.workflow.fetch.driver.BrowserFetcher

/**
 * Created by Vincent on 18-1-1.
 * Copyright @ 2013-2023 Platon AI. All rights reserved
 */
interface IncognitoBrowserFetcher : Fetcher, BrowserFetcher {
    val privacyManager: BrowserPrivacyManager
    val webdriverFetcher: WebDriverFetcher
    val browserEmulator: BrowserEmulator
}
