package ai.platon.pulsar.external

import ai.platon.pulsar.common.config.ImmutableConfig

class ChatModelSettings(conf: ImmutableConfig) {

    /**
     * The maximum length of the prompt.
     *
     * Example:
     * Doubao-Seed-1.6:
     * - 最大输入Token长度: 224k
     * - 最大思考内容Token长度: 32k
     * - 上下文窗口: 256k
     * - 最大生成Token长度: 32K
     * */
    val maximumInputTokenLength = conf.getInt("llm.max.input.token.length", 224 * 1000 - 1000)
}
