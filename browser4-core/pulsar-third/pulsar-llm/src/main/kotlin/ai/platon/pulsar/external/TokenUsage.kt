package ai.platon.pulsar.external

import ai.platon.pulsar.common.measure.ByteUnit

data class TokenUsage(
    val inputTokenCount: Int = 0,
    val outputTokenCount: Int = 0,
    val totalTokenCount: Int = 0,
) {
    override fun toString(): String {
        val i = ByteUnit.BYTE.toKB(inputTokenCount.toDouble())
        val o = ByteUnit.BYTE.toKB(outputTokenCount.toDouble())
        val t = ByteUnit.BYTE.toKB(totalTokenCount.toDouble())

        return String.format("in: %.0f out: %.0f total: %.0f", i, o, t)
    }
}
