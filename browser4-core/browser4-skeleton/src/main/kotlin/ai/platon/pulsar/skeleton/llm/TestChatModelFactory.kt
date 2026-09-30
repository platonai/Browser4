package ai.platon.pulsar.skeleton.llm

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.getLogger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Creates the test-only [FileBackedChatModel] when the test harness opts in
 * through environment variables (or equivalent system properties).
 *
 * Environment variables:
 * - `BROWSER4_TEST_LLM_RESPONSE_FILE` — a single file whose content is
 *   returned for every LLM call.
 * - `BROWSER4_TEST_LLM_RESPONSE_DIR` — a directory of response files consumed
 *   in lexicographic order, one per LLM call.
 *
 * System property mirrors (`browser4.test.llm.responseFile` /
 * `browser4.test.llm.responseDir`) are also accepted so JVM tests can enable
 * the mock without mutating the process environment.
 */
object TestChatModelFactory {
    const val RESPONSE_FILE_ENV = "BROWSER4_TEST_LLM_RESPONSE_FILE"
    const val RESPONSE_DIR_ENV = "BROWSER4_TEST_LLM_RESPONSE_DIR"
    const val RESPONSE_FILE_PROPERTY = "browser4.test.llm.responseFile"
    const val RESPONSE_DIR_PROPERTY = "browser4.test.llm.responseDir"

    private val logger = getLogger(this)
    private val models = ConcurrentHashMap<String, FileBackedChatModel>()

    /** True when the file-backed test LLM has been enabled. */
    fun isEnabled(): Boolean =
        !responseFile().isNullOrBlank() || !responseDir().isNullOrBlank()

    /** Returns the file-backed model, or null when the test LLM is not enabled. */
    fun getOrCreate(conf: ImmutableConfig): FileBackedChatModel? {
        val file = responseFile()?.takeIf { it.isNotBlank() }
        if (file != null) {
            val path = Path.of(file)
            require(Files.isRegularFile(path)) {
                "$RESPONSE_FILE_ENV points to a non-existent file: $path"
            }
            logger.info("Using file-backed mock LLM: {}", path)
            return models.computeIfAbsent(path.toString()) { FileBackedChatModel(conf, path) }
        }

        val dir = responseDir()?.takeIf { it.isNotBlank() }
        if (dir != null) {
            val path = Path.of(dir)
            require(Files.isDirectory(path)) {
                "$RESPONSE_DIR_ENV points to a non-existent directory: $path"
            }
            logger.info("Using directory-backed mock LLM: {}", path)
            return models.computeIfAbsent(path.toString()) { FileBackedChatModel(conf, path) }
        }

        return null
    }

    /** Single-response file configured via env var or system property. */
    fun responseFile(): String? =
        System.getenv(RESPONSE_FILE_ENV) ?: System.getProperty(RESPONSE_FILE_PROPERTY)

    /** Response directory configured via env var or system property. */
    fun responseDir(): String? =
        System.getenv(RESPONSE_DIR_ENV) ?: System.getProperty(RESPONSE_DIR_PROPERTY)
}
