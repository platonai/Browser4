package ai.platon.pulsar.skeleton.workflow.common

import ai.platon.pulsar.common.config.Configurable
import ai.platon.pulsar.common.config.ImmutableConfig

interface LazyConfigurable: Configurable {
    fun configure(conf1: ImmutableConfig)
}
