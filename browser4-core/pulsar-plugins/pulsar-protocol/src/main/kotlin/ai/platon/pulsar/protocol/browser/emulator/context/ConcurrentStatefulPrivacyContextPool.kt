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
package ai.platon.pulsar.protocol.browser.emulator.context

import ai.platon.pulsar.common.*
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.proxy.ProxyException
import ai.platon.pulsar.common.proxy.ProxyPoolManager
import ai.platon.pulsar.skeleton.crawl.CoreMetrics
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.BrowserProfile
import ai.platon.pulsar.skeleton.crawl.fetch.privacy.PrivacyContext
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

class ConcurrentStatefulPrivacyContextPool(
    val proxyPoolManager: ProxyPoolManager,
    val driverPoolManager: WebDriverPoolManager,
    val coreMetrics: CoreMetrics,
    val conf: ImmutableConfig,
    val allowedPrivacyContextCount: Int
) {
    private val logger = getLogger(this)

    /**
     * life cycle of the permanent context is relatively long. The system will never delete the permanent contexts.
     *
     * The predefined privacy agents for permanent contexts are:
     *
     * 1. PrivacyAgent.USER_DEFAULT
     * 2. PrivacyAgent.PROTOTYPE
     * 2. PrivacyAgent.DEFAULT
     * */
    private val _permanentContexts = ConcurrentHashMap<BrowserProfile, PrivacyContext>()

    /**
     * The life cycle of the temporary context is very short. Whenever the system detects that the
     * privacy context is leaked, the system discards the leaked context and creates a new one.
     *
     * NOTE: we can use a priority queue and every time we need a context, take the top one
     * */
    private val _temporaryContexts = ConcurrentHashMap<BrowserProfile, PrivacyContext>()

    private val _zombieContexts = ConcurrentLinkedDeque<PrivacyContext>()

    private val _deadContexts = ConcurrentLinkedDeque<PrivacyContext>()

    val permanentContexts: Map<BrowserProfile, PrivacyContext> get() = _permanentContexts

    val temporaryContexts: Map<BrowserProfile, PrivacyContext> get() = _temporaryContexts

    val zombieContexts: Deque<PrivacyContext> get() = _zombieContexts

    val deadContexts: Deque<PrivacyContext> get() = _deadContexts

    @get:Synchronized
    val activeContexts get() = permanentContexts + temporaryContexts

    @get:Synchronized
    val activeContextCount get() = permanentContexts.size + temporaryContexts.size

    @Throws(ProxyException::class)
    fun computeIfAbsent(profile: BrowserProfile): PrivacyContext {
        return if (profile.isPermanent) {
            _permanentContexts.computeIfAbsent(profile) { createUnmanagedContext(profile) }
        } else {
            _temporaryContexts.computeIfAbsent(profile) { createUnmanagedContext(profile) }
        }
    }

    /**
     * Create a privacy context who is not added to the context list yet.
     * */
    @Throws(ProxyException::class)
    fun createUnmanagedContext(profile: BrowserProfile): BrowserPrivacyContext {
        val context = BrowserPrivacyContext(proxyPoolManager, driverPoolManager, coreMetrics, conf, profile)
        if (profile.isPermanent) {
            logger.info("Permanent privacy context is created #{} | {}", context.display, context.baseDir)
        } else if (profile.isTemporary) {
            logger.info(
                "Temporary privacy context is created #{}, active: {}, allowed: {} | {}",
                context.display, temporaryContexts.size, allowedPrivacyContextCount, context.baseDir
            )
        } else if (profile.isGroup) {
            logger.info(
                "Sequential privacy context in group is created #{}, active: {}, allowed: {} | {}",
                context.display, temporaryContexts.size, allowedPrivacyContextCount, context.baseDir
            )
        } else {
            logger.warn("Unexpected privacy context is created #{} | {}", context.display, context.baseDir)
        }

        return context
    }

    /**
     * Close the zombie contexts, and the resources release immediately.
     * */
    @Synchronized
    fun closeDyingContexts() {
        logger.debug("Closing zombie contexts ...")

        val dyingContexts = zombieContexts.filter { !it.isClosed }
        if (dyingContexts.isEmpty()) {
            return
        }

        logger.debug("Closing {} zombie contexts ...", dyingContexts.size)

        dyingContexts.forEach { privacyContext ->
            privacyContext.runCatching { close() }.onFailure { warnForClose(this, it) }

            zombieContexts.remove(privacyContext)
            deadContexts.add(privacyContext)
        }
    }

    @Synchronized
    fun close(privacyContext: PrivacyContext) {
        val privacyAgent = privacyContext.profile

        _permanentContexts.remove(privacyAgent)
        _temporaryContexts.remove(privacyAgent)

        if (!_zombieContexts.contains(privacyContext)) {
            // every time we add the item to the head,
            // so when we report the deque, the latest contexts are reported.
            _zombieContexts.addFirst(privacyContext)
        }

        closeDyingContexts()
    }
}
