package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.skeleton.common.metrics.MetricsSystem
import com.codahale.metrics.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.io.File
import java.io.RandomAccessFile

@RestController
@CrossOrigin
@RequestMapping("api/doctor")
class DoctorController(
) {
    @Value("\${logging.dir:logs}")
    lateinit var loggingDir: String

    @GetMapping("logs")
    fun logs(
        @RequestParam(defaultValue = "pulsar") file: String,
        @RequestParam(defaultValue = "50") lines: Int,
        @RequestParam(defaultValue = "") filter: String
    ): ResponseEntity<Map<String, Any?>> {
        val sanitizedFile = file.replace(Regex("[/\\\\]"), "")
        val cappedLines = lines.coerceIn(1, 500)
        val logFile = File(loggingDir, "$sanitizedFile.log")

        if (!logFile.exists() || !logFile.isFile) {
            return ResponseEntity.ok(
                mapOf(
                    "file" to logFile.name,
                    "path" to logFile.absolutePath,
                    "lines" to emptyList<String>(),
                    "totalLines" to 0,
                    "returnedLines" to 0
                )
            )
        }

        val result = try {
            tailLines(logFile, cappedLines)
        } catch (e: Exception) {
            logger.warn("Failed to tail log file {}: {}", logFile.absolutePath, e.message)
            return ResponseEntity.ok(
                mapOf(
                    "file" to logFile.name,
                    "path" to logFile.absolutePath,
                    "error" to (e.message ?: "Unknown error"),
                    "lines" to emptyList<String>(),
                    "totalLines" to 0,
                    "returnedLines" to 0
                )
            )
        }

        val (allLines, totalLines) = result
        val filteredLines = if (filter.isBlank()) {
            allLines
        } else {
            val regex = try {
                Regex(filter, setOf(RegexOption.IGNORE_CASE))
            } catch (e: Exception) {
                Regex(Regex.escape(filter), setOf(RegexOption.IGNORE_CASE))
            }
            allLines.filter { regex.containsMatchIn(it) }
        }

        return ResponseEntity.ok(
            mapOf(
                "file" to logFile.name,
                "path" to logFile.absolutePath,
                "lines" to filteredLines,
                "totalLines" to totalLines,
                "returnedLines" to filteredLines.size,
                "matchedLines" to if (filter.isNotBlank()) filteredLines.size else null
            )
        )
    }

    @GetMapping("metrics")
    fun metrics(
        @RequestParam(defaultValue = "") filter: String
    ): Map<String, Any> {
        val registry = MetricsSystem.reg
        val metricMap = registry.metrics

        val gauges = mutableMapOf<String, Any?>()
        val counters = mutableMapOf<String, Long>()
        val meters = mutableMapOf<String, Map<String, Any>>()
        val histograms = mutableMapOf<String, Map<String, Any>>()
        val timers = mutableMapOf<String, Map<String, Any>>()

        val filterRegex = if (filter.isBlank()) null else {
            try {
                Regex(filter, setOf(RegexOption.IGNORE_CASE))
            } catch (e: Exception) {
                Regex(Regex.escape(filter), setOf(RegexOption.IGNORE_CASE))
            }
        }

        for ((name, metric) in metricMap) {
            if (filterRegex != null && !filterRegex.containsMatchIn(name)) {
                continue
            }
            when (metric) {
                is Gauge<*> -> {
                    val value = metric.value
                    gauges[name] = value?.toString() ?: "null"
                }
                is Counter -> {
                    counters[name] = metric.count
                }
                is Meter -> {
                    meters[name] = mapOf(
                        "count" to metric.count,
                        "meanRate" to metric.meanRate,
                        "m1Rate" to metric.oneMinuteRate,
                        "m5Rate" to metric.fiveMinuteRate,
                        "m15Rate" to metric.fifteenMinuteRate,
                        "rateUnit" to "events/second"
                    )
                }
                is Histogram -> {
                    val snapshot = metric.snapshot
                    histograms[name] = mapOf(
                        "count" to metric.count,
                        "min" to snapshot.min,
                        "max" to snapshot.max,
                        "mean" to snapshot.mean,
                        "stddev" to snapshot.stdDev,
                        "median" to snapshot.median,
                        "p75" to snapshot.get75thPercentile(),
                        "p95" to snapshot.get95thPercentile(),
                        "p99" to snapshot.get99thPercentile(),
                        "p999" to snapshot.get999thPercentile()
                    )
                }
                is Timer -> {
                    val snapshot = metric.snapshot
                    timers[name] = mapOf(
                        "count" to metric.count,
                        "meanRate" to metric.meanRate,
                        "m1Rate" to metric.oneMinuteRate,
                        "m5Rate" to metric.fiveMinuteRate,
                        "m15Rate" to metric.fifteenMinuteRate,
                        "min" to snapshot.min,
                        "max" to snapshot.max,
                        "mean" to snapshot.mean,
                        "stddev" to snapshot.stdDev,
                        "median" to snapshot.median,
                        "p75" to snapshot.get75thPercentile(),
                        "p95" to snapshot.get95thPercentile(),
                        "p99" to snapshot.get99thPercentile(),
                        "durationUnit" to "nanoseconds"
                    )
                }
            }
        }

        return mapOf(
            "gauges" to gauges,
            "counters" to counters,
            "meters" to meters,
            "histograms" to histograms,
            "timers" to timers
        )
    }

    /**
     * Efficiently read the last [numLines] lines from a file using RandomAccessFile.
     * Returns a pair of (lines, totalLineCount).
     */
    private fun tailLines(file: File, numLines: Int): Pair<List<String>, Int> {
        val buffer = ArrayDeque<String>(numLines)
        var totalLines = 0

        RandomAccessFile(file, "r").use { raf ->
            val fileLength = raf.length()
            if (fileLength == 0L) {
                return Pair(emptyList(), 0)
            }

            // Estimate start position: assume ~200 bytes per line, read backward
            val estimatedBytes = numLines.toLong() * 200
            val startPos = (fileLength - estimatedBytes).coerceAtLeast(0)
            raf.seek(startPos)

            // Skip partial first line if not at the beginning
            if (startPos > 0) {
                raf.readLine() // skip the partial line
            }

            var line: String? = raf.readLine()
            while (line != null) {
                totalLines++
                buffer.addLast(line)
                if (buffer.size > numLines) {
                    buffer.removeFirst()
                }
                line = raf.readLine()
            }
        }

        return Pair(buffer.toList(), totalLines)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DoctorController::class.java)
    }
}
