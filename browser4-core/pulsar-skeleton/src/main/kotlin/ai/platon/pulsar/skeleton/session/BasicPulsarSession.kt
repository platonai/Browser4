package ai.platon.pulsar.skeleton.session

import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.skeleton.context.support.AbstractPulsarContext

/**
 * Created by Vincent on 18-1-17.
 * Copyright @ 2013-2023 Platon AI. All rights reserved
 */
open class BasicPulsarSession(
    context: AbstractPulsarContext,
    sessionConfig: VolatileConfig,
    id: Long = nextId()
) : AbstractPulsarSession(context, sessionConfig, id)
