package ai.platon.pulsar.skeleton.workflow.schedule

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.skeleton.common.message.MiscMessageWriter

class DefaultFetchSchedule(
        conf: ImmutableConfig,
        messageWriter: MiscMessageWriter? = null
) : AbstractFetchSchedule(conf, messageWriter)
