package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.skeleton.common.metrics.MetricsSystem
import com.codahale.metrics.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import kotlin.text.Charsets
import java.io.File
import java.io.RandomAccessFile

@RestController
@CrossOrigin
@RequestMapping("api/doctor")
class DoctorController(
) {
    @Value("\${logging.dir:logs}")
    lateinit var loggingDir: String

    @GetMapping("log-files")
    fun logFiles(): ResponseEntity<Map<String, Any?>> {
        val logDir = File(loggingDir)
        if (!logDir.exists() || !logDir.isDirectory) {
            return ResponseEntity.ok(
                mapOf(
                    "files" to emptyList<Map<String, Any?>>(),
                    "directory" to logDir.absolutePath,
                    "exists" to false
                )
            )
        }

        val logFiles = logDir.listFiles { f ->
            f.isFile && f.name.endsWith(".log")
        }?.sortedBy { it.name } ?: emptyList<File>()

        val fileInfos = logFiles.map { f ->
            mapOf(
                "name" to f.name,
                "nameWithoutExt" to f.nameWithoutExtension,
                "size" to f.length(),
                "sizeHuman" to formatFileSize(f.length()),
                "lastModified" to f.lastModified(),
                "path" to f.absolutePath
            )
        }

        return ResponseEntity.ok(
            mapOf(
                "files" to fileInfos,
                "directory" to logDir.absolutePath,
                "count" to fileInfos.size
            )
        )
    }

    @GetMapping("logs")
    fun logs(
        @RequestParam(defaultValue = "pulsar") file: String,
        @RequestParam(defaultValue = "50") lines: Int,
        @RequestParam(defaultValue = "") filter: String
    ): ResponseEntity<Map<String, Any?>> {
        val sanitizedFile = file.replace(Regex("[/\\\\]"), "")
        val cappedLines = lines.coerceIn(1, 5000)
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

    @GetMapping("llm-status")
    fun llmStatus(): ResponseEntity<Map<String, Any?>> {
        val envKeyNames = listOf(
            "OPENROUTER_API_KEY",
            "DEEPSEEK_API_KEY",
            "VOLCENGINE_API_KEY",
            "OPENAI_API_KEY",
            "LLM_API_KEY",
        )
        val propertyKeyNames = listOf(
            "llm.api.key",
            "openrouter.api.key",
            "volcengine.api.key",
            "deepseek.api.key",
            "openai.api.key",
        )

        val foundEnvVars = envKeyNames.filter { System.getenv(it) != null }
        val foundProperties = propertyKeyNames.filter { System.getProperty(it) != null }

        val configured = foundEnvVars.isNotEmpty() || foundProperties.isNotEmpty()

        val message = if (configured) {
            null
        } else {
            "LLM is not configured, you can only use non-LLM commands. " +
                "X-SQL is still available. " +
                "It is highly recommended to set OPENROUTER_API_KEY or other LLM keys to enable LLM features."
        }

        return ResponseEntity.ok(
            mapOf(
                "configured" to configured,
                "foundEnvVars" to foundEnvVars,
                "foundProperties" to foundProperties,
                "keyPrefixes" to listOf("OPENROUTER", "DEEPSEEK", "VOLCENGINE", "OPENAI"),
                "message" to message,
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
     *
     * Reads the raw bytes and decodes them as UTF-8, because
     * [RandomAccessFile.readLine] uses the platform default charset which
     * corrupts non-ASCII characters on Windows (e.g. GBK instead of UTF-8).
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
                skipPartialLine(raf)
            }

            var rawLine: ByteArray? = readUtf8Line(raf)
            while (rawLine != null) {
                totalLines++
                val line = String(rawLine, Charsets.UTF_8)
                buffer.addLast(line)
                if (buffer.size > numLines) {
                    buffer.removeFirst()
                }
                rawLine = readUtf8Line(raf)
            }
        }

        return Pair(buffer.toList(), totalLines)
    }

    /**
     * Skip to the end of the current (possibly partial) line.
     */
    private fun skipPartialLine(raf: RandomAccessFile) {
        var b = raf.read()
        while (b != -1 && b.toByte() != '\n'.code.toByte()) {
            b = raf.read()
        }
    }

    /**
     * Read a single line as raw UTF-8 bytes, handling CR, LF, and CR+LF.
     * Returns null at EOF.
     */
    private fun readUtf8Line(raf: RandomAccessFile): ByteArray? {
        val lineBytes = java.io.ByteArrayOutputStream()
        var b = raf.read()
        if (b == -1) return null

        while (b != -1) {
            val byte = b.toByte()
            if (byte == '\n'.code.toByte()) {
                // LF — end of line (handles LF and CR+LF)
                break
            }
            if (byte == '\r'.code.toByte()) {
                // CR — peek ahead for LF
                val next = raf.read()
                if (next != -1 && next.toByte() != '\n'.code.toByte()) {
                    // Not CR+LF — seek back one byte
                    raf.seek(raf.filePointer - 1)
                }
                break
            }
            lineBytes.write(b)
            b = raf.read()
        }
        return lineBytes.toByteArray()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DoctorController::class.java)

        private fun formatFileSize(bytes: Long): String {
            val units = arrayOf("B", "KB", "MB", "GB")
            var size = bytes.toDouble()
            var unitIndex = 0
            while (size >= 1024 && unitIndex < units.size - 1) {
                size /= 1024
                unitIndex++
            }
            return "%.1f %s".format(size, units[unitIndex])
        }
    }
}
