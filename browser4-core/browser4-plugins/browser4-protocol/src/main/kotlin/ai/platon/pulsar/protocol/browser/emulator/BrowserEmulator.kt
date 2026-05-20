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

import ai.platon.pulsar.common.event.EventEmitter
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.workflow.fetch.FetchResult
import ai.platon.pulsar.skeleton.workflow.fetch.FetchTask
import ai.platon.pulsar.skeleton.browser.driver.WebDriver
import ai.platon.pulsar.skeleton.browser.driver.WebDriverException

enum class EmulateEvents {
    willNavigate,
    navigated,
    willInteract,
    didInteract,
    willCheckDocumentState,
    documentFullyLoaded,
    willScroll,
    didScroll,
    documentSteady,
    willComputeFeature,
    featureComputed,
    willStopTab,
    tabStopped,
}

/**
 * Created by Vincent on 18-1-1.
 * Copyright @ 2013-2023 Platon AI. All rights reserved.
 *
 * About emulate, simulate, mimic and imitate:
 * 1. Emulate is usually used with someone as an object.
 * 2. Simulate has the idea of copying something so that the copy pretends to be the original thing.
 * 3. Mimic, a person who imitate mannerisms of others.
 * 4. Imitate is the most general of the four words, can be used in all the three senses.
 */
interface BrowserEmulator: EventEmitter<EmulateEvents>, AutoCloseable {

    /**
     * Fetch a page using a browser which can render the DOM and execute scripts.
     *
     * @param task The task to fetch
     * @return The result of this fetch
     * */
    @Throws(WebDriverException::class)
    suspend fun visit(task: FetchTask, driver: WebDriver): FetchResult

    fun cancelNow(task: FetchTask)

    suspend fun cancel(task: FetchTask)

    suspend fun onWillNavigate(page: WebPage, driver: WebDriver)

    suspend fun onNavigated(page: WebPage, driver: WebDriver)

    suspend fun onWillInteract(page: WebPage, driver: WebDriver)

    suspend fun onWillCheckDocumentState(page: WebPage, driver: WebDriver)

    suspend fun onDocumentFullyLoaded(page: WebPage, driver: WebDriver)

    suspend fun onWillScroll(page: WebPage, driver: WebDriver)

    suspend fun onDidScroll(page: WebPage, driver: WebDriver)

    suspend fun onDocumentSteady(page: WebPage, driver: WebDriver)

    suspend fun onWillComputeFeature(page: WebPage, driver: WebDriver)

    suspend fun onFeatureComputed(page: WebPage, driver: WebDriver)

    suspend fun onDidInteract(page: WebPage, driver: WebDriver)

    suspend fun onWillStopTab(page: WebPage, driver: WebDriver)

    suspend fun onTabStopped(page: WebPage, driver: WebDriver)
}
