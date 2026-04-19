package ai.platon.pulsar.skeleton.crawl.fetch.privacy

import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.browser.fingerprint.Fingerprint
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.config.MutableConfig
import org.apache.commons.io.FileUtils
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import org.junit.jupiter.api.DisplayName

class SequentialPrivacyAgentGeneratorTest {

    private lateinit var conf: ImmutableConfig
    private lateinit var generator: SequentialPrivacyAgentGenerator
    private lateinit var mockFingerprint: Fingerprint
    private lateinit var contextBaseDir: Path
    private val contextDirs = mutableListOf<Path>()

    @BeforeEach
    fun setUp() {
        conf = MutableConfig()
        generator = SequentialPrivacyAgentGenerator("test")
        mockFingerprint = Fingerprint.EXAMPLE
        contextBaseDir = AppPaths.CONTEXT_GROUP_BASE_DIR.resolve("test/PULSAR_CHROME")
        IntRange(1, 10).forEach { i ->
            val contextDir = contextBaseDir.resolve("cx.$i")
            contextDirs.add(contextDir)
            Files.createDirectories(contextDir)
        }
    }

    @AfterEach
    fun tearDown() {
        kotlin.runCatching { FileUtils.deleteDirectory(contextBaseDir.toFile()) }.onFailure { printlnPro(it.brief()) }
    }

    @Test
        @DisplayName("test invoke with valid context directory")
    fun testInvokeWithValidContextDirectory() {
        // Given

        // When
        val actualAgent = generator.invoke(mockFingerprint)

        // Then
        assertEquals(contextDirs[0], actualAgent.contextDir)
    }

    @Test
        @DisplayName("test invoke with non-existent fingerprint config file")
    fun testInvokeWithNonExistentFingerprintConfigFile() {
        // Given

        // When
        // val actualAgent = generator.invoke(mockFingerprint)

        // Then
    }
}

