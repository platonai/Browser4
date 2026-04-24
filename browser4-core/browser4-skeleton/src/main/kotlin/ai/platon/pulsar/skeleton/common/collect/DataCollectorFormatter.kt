package ai.platon.pulsar.skeleton.common.collect

import ai.platon.pulsar.common.DateTimes
import ai.platon.pulsar.common.Priority13
import ai.platon.pulsar.common.collect.collector.PriorityDataCollector
import ai.platon.pulsar.common.readable
import ai.platon.pulsar.common.sql.ResultSetFormatter
import ai.platon.pulsar.ql.common.ResultSets
import org.apache.commons.lang3.StringUtils
import org.h2.tools.SimpleResultSet
import java.time.Duration
import java.time.Instant

abstract class PriorityDataCollectorFormatterBase<T> {
    fun newResultSet(): SimpleResultSet {
        return ResultSets.newSimpleResultSet(
            "name", "priority", "pName",
            "collected", "cd/s", "collect", "c/s", "time",
            "size", "estSize", "firstCollect", "lastCollect", "labels"
        )
    }

    fun addRow(c: PriorityDataCollector<T>, rs: SimpleResultSet) {
        val dtFormatter = "dd HH:mm:ss"
        val firstCollectTime = c.firstCollectTime.atZone(DateTimes.zoneId).toLocalDateTime()
        val lastCollectedTime = c.lastCollectedTime.atZone(DateTimes.zoneId).toLocalDateTime()
        val elapsedTime = if (c.lastCollectedTime > c.firstCollectTime)
            Duration.between(c.firstCollectTime, c.lastCollectedTime) else Duration.ZERO
        val elapsedSeconds = elapsedTime.seconds.coerceAtLeast(1)
        val priorityName = Priority13.valueOfOrNull(c.priority)?.name ?: ""
        var labels = c.labels.joinToString { StringUtils.abbreviateMiddle(it, "*", 4) }
        if (labels.length > 16) {
            labels = StringUtils.abbreviateMiddle(labels, "..", 16)
        }

        rs.addRow(
            c.name, c.priority, priorityName,
            c.collectedCount,
            String.format("%.2f", 1.0 * c.collectedCount / elapsedSeconds),
            c.collectCount,
            String.format("%.2f", 1.0 * c.collectCount / elapsedSeconds),
            elapsedTime.readable(),
            c.size, c.estimatedSize,
            DateTimes.format(firstCollectTime, dtFormatter),
            DateTimes.format(lastCollectedTime, dtFormatter),
            labels
        )
    }
}

class PriorityDataCollectorFormatter<T>(
    val collector: PriorityDataCollector<T>,
) : PriorityDataCollectorFormatterBase<T>() {
    override fun toString(): String {
        val rs = newResultSet()
        addRow(collector, rs)
        return ResultSetFormatter(rs, withHeader = true).toString()
    }
}

class PriorityDataCollectorsTableFormatter<T>(
    val collectors: Collection<PriorityDataCollector<T>>,
) : PriorityDataCollectorFormatterBase<T>() {
    fun abstract(): String {
        val firstCollectTime = collectors.filter { it.firstCollectTime > Instant.EPOCH }
            .minOfOrNull { it.firstCollectTime }
        val lastCollectedTime = collectors.filter { it.firstCollectTime > Instant.EPOCH }.maxOf { it.lastCollectedTime }
        val collectedCount = collectors.sumOf { it.collectedCount }
        val collectCount = collectors.sumOf { it.collectCount }
        val size = collectors.sumOf { it.size }
        val estimatedSize = collectors.sumOf { it.estimatedSize }
        val elapsedTime = DateTimes.elapsedTime()
        val elapsedSeconds = elapsedTime.seconds

        return String.format(
            "Total collected %s/%s/%s/%s in %s, remaining %s/%s, collect time: %s -> %s",
            collectedCount,
            String.format("%.2f", 1.0 * collectedCount / elapsedSeconds),
            collectCount,
            String.format("%.2f", 1.0 * collectCount / elapsedSeconds),
            elapsedTime.readable(),
            size, estimatedSize,
            firstCollectTime, lastCollectedTime
        )
    }

    override fun toString(): String {
        val rs = newResultSet()
        collectors.forEach { addRow(it, rs) }
        return ResultSetFormatter(rs, withHeader = true).toString()
    }
}

/**
 * TODO: avoid free function
 * */
fun <T> formatAsTable(collectors: Collection<PriorityDataCollector<T>>): String {
    return PriorityDataCollectorsTableFormatter(collectors).toString()
}
