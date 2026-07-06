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
package ai.platon.pulsar.skeleton.workflow.protocol.browser

import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.skeleton.workflow.fetch.BrowserFetcher
import ai.platon.pulsar.skeleton.workflow.fetch.Fetcher
import ai.platon.pulsar.skeleton.workflow.protocol.Response
import kotlinx.coroutines.runBlocking

/**
 * Created by Vincent on 18-1-1.
 * Copyright @ 2013-2023 Platon AI. All rights reserved
 */
abstract class AbstractBrowserFetcher : BrowserFetcher, Fetcher {

    abstract val isActive: Boolean

    /**
     * Fetch page content
     * */
    @Throws(Exception::class)
    override fun fetchContent(page: WebPage): Response = runBlocking {
        fetchContentDeferred(page)
    }

    @Throws(Exception::class)
    override suspend fun fetchDeferred(url: String) =
        fetchContentDeferred(GoraWebPage.newWebPage(url, conf.toVolatileConfig()))

    @Throws(Exception::class)
    override suspend fun fetchDeferred(url: String, volatileConfig: VolatileConfig) =
        fetchContentDeferred(GoraWebPage.newWebPage(url, volatileConfig))

    /**
     * Fetch page content
     * */
    @Throws(Exception::class)
    abstract override suspend fun fetchContentDeferred(page: WebPage): Response
}
