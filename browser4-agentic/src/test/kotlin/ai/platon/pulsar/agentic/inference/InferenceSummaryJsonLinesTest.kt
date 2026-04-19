package ai.platon.pulsar.agentic.inference

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readLines

@Tag("inference")
class InferenceSummaryJsonLinesTest {

    @AfterEach
    fun cleanupTempDirs() {
        // Clean up any temporary directories created during tests.
        // In a real test suite, consider using a more robust temp directory management strategy.
        val tempRoot = System.getProperty("java.io.tmpdir")
        Files.list(Path.of(tempRoot))
            .filter {
                it.fileName.toString().startsWith("pulsar-agentic-jsonl-") || it.fileName.toString()
                    .startsWith("pulsar-agentic-jsonl-concurrent-")
            }
            .forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun appendJsonLineAtomicallyWritesOneJsonPerLine() {
        val tempDir = Files.createTempDirectory("pulsar-agentic-jsonl-")
        val file = tempDir.resolve("observe_summary.json")

        val engineAppender = InferenceEngineJsonlAppender()

        engineAppender.append(tempDir, file, mapOf("n" to 1))
        engineAppender.append(tempDir, file, mapOf("n" to 2, "s" to "x"))

        val lines = file.readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)

        val nodes = lines.map { pulsarObjectMapper().readTree(it) }
        assertTrue(nodes.all { it.isObject }, "Each line should be a JSON object")
        assertEquals(1, nodes[0].path("n").asInt())
        assertEquals(2, nodes[1].path("n").asInt())
        assertEquals("x", nodes[1].path("s").asText())
    }

    @Test
    fun appendJsonLineAtomicallyIsSafeForConcurrentAppends() {
        val tempDir = Files.createTempDirectory("pulsar-agentic-jsonl-concurrent-")
        val file = tempDir.resolve("extract_summary.json")

        val engineAppender = InferenceEngineJsonlAppender()

        val threads = (1..16).map { id ->
            Thread {
                repeat(25) { i ->
                    engineAppender.append(tempDir, file, mapOf("thread" to id, "i" to i))
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val lines = file.readLines().filter { it.isNotBlank() }
        assertEquals(16 * 25, lines.size)

        // Ensure every line is valid JSON. (We don't assert ordering.)
        lines.forEach { pulsarObjectMapper().readTree(it) }
    }

    /**
     * Small test helper that exercises the same JSONL write strategy used by InferenceEngine.
     *
     * We keep it here (instead of instantiating a full InferenceEngine) to avoid heavy dependencies.
     */
    private class InferenceEngineJsonlAppender {
        fun append(summaryDir: Path, file: Path, entry: Map<String, Any?>) {
            val node = pulsarObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(entry)
            // Mirrors the production logic closely.
            Files.createDirectories(summaryDir)

            val lock = locks.computeIfAbsent(file.toAbsolutePath().normalize().toString()) { Any() }
            val line = pulsarObjectMapper().writeValueAsString(node)

            synchronized(lock) {
                java.nio.channels.FileChannel.open(
                    file,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.APPEND
                ).use { ch ->
                    val fileLock = runCatching { ch.lock() }.getOrNull()
                    try {
                        val bytes = (line + "\n").toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                        ch.write(java.nio.ByteBuffer.wrap(bytes))
                        ch.force(true)
                    } finally {
                        runCatching { fileLock?.release() }
                    }
                }
            }
        }

        companion object {
            private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        }
    }
}
