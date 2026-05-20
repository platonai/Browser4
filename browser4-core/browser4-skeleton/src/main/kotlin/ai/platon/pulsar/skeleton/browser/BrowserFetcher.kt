package ai.platon.pulsar.skeleton.browser

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.persist.WebPage

/**
 * Created by Vincent on 18-1-1.
 * Copyright @ 2013-2023 Platon AI. All rights reserved
 */
interface BrowserFetcher : AutoCloseable {

    val conf: ImmutableConfig

    fun reset()

    fun cancel(page: WebPage)

    fun cancelAll()
}
