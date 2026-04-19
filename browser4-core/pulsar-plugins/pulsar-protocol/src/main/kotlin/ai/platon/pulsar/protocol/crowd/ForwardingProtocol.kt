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
package ai.platon.pulsar.protocol.crowd

import ai.platon.pulsar.common.concurrent.ConcurrentExpiringLRUCache
import ai.platon.pulsar.skeleton.crawl.protocol.Response
import ai.platon.pulsar.skeleton.crawl.protocol.http.AbstractHttpProtocol
import ai.platon.pulsar.persist.WebPage
import org.slf4j.LoggerFactory
import java.time.Duration

open class ForwardingProtocol : AbstractHttpProtocol() {
    private val logger = LoggerFactory.getLogger(ForwardingProtocol::class.java)
    private val cacheTTL = Duration.ofMinutes(5)
    private val cacheCapacity = 200
    private val cache = ConcurrentExpiringLRUCache<String, Response>(cacheTTL, cacheCapacity)

    override fun setResponse(response: Response) {
        cache.putDatum(response.url, response)
        logAfterPutResponse()
    }
    
    @Throws(Exception::class)
    override fun getResponse(page: WebPage, followRedirects: Boolean): Response? {
        val response = cache.remove(page.url)?.datum?: return null
        logAfterRemoveResponse(page.url, response)
        return response
    }
    
    @Throws(Exception::class)
    override suspend fun getResponseDeferred(page: WebPage, followRedirects: Boolean): Response? {
        // TODO: wait if not in the cache?
        val response = cache.remove(page.url)?.datum?: return null
        logAfterRemoveResponse(page.url, response)
        return response
    }

    private fun logAfterRemoveResponse(url: String, response: Response?) {
        if (response == null) {
            if (logger.isTraceEnabled) {
                logger.trace("No page in forward cache, total {} | {}", cache.size, url)
            }
        }
    }

    private fun logAfterPutResponse() {
        if (logger.isTraceEnabled) {
            logger.trace("Putting page to forward cache, total {}", cache.size)
        }
        if (cache.size > 100) {
            logger.warn("Forwarding cache is too large, there might be a bug")
            if (cache.size > 1000) {
                logger.warn("!!!WARNING!!! FORWARDING CACHE IS UNEXPECTED TOO LARGE, CLEAR IT TO PREVENT MEMORY EXHAUSTING")
                cache.clear()
            }
        }
    }
}
