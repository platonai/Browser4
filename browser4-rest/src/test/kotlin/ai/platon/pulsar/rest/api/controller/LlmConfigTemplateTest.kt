package ai.platon.pulsar.rest.api.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [LlmConfigTemplate], the template that closes the gap behind
 * "I configured the file and nothing changed": the user is handed the exact
 * writable path instead of having to invent `conf-enabled/application-private.properties`.
 *
 * Every test works in a temporary directory — the real `~/.browser4/config` is never
 * written to from a test.
 */
class LlmConfigTemplateTest {

    private fun withTempDirs(block: (Path, Path) -> Unit) {
        val tempDir = Files.createTempDirectory("llm-config-template")
        try {
            block(tempDir.resolve("conf-available"), tempDir.resolve("conf-enabled"))
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    @DisplayName("the bundled template should exist and contain no active setting")
    fun bundledTemplateShouldExistAndBeInert() {
        val resource = LlmConfigTemplate::class.java.getResourceAsStream(LlmConfigTemplate.RESOURCE)
        assertNotNull(resource, "the template must ship on the classpath: ${LlmConfigTemplate.RESOURCE}")

        val lines = resource!!.use { it.reader().readLines() }
        assertTrue(lines.isNotEmpty(), "the template must not be empty")

        // An uncommented line would configure — or worse, enable — something for every
        // user who runs 'doctor --fix'. The template must stay inert, and it must never
        // carry a real key.
        val active = lines
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(
            active.isEmpty(),
            "every setting in the template must be commented out, found: $active"
        )

        val text = lines.joinToString("\n")
        assertTrue(text.contains("conf-enabled"), "the template must explain where it is read from")
        assertTrue(text.contains("openrouter.api.key"), "the template must show at least one provider")
        assertTrue(text.contains("llm.provider.deny.list"), "the template must mention the escape hatch")

        // The classpath resource is a `.template` (see LlmConfigTemplate.RESOURCE) so that
        // the repository's "never commit application-private.properties" rule still holds.
        assertTrue(
            LlmConfigTemplate.RESOURCE.endsWith(".template"),
            "the shipped resource must not be named like a real secrets file"
        )
    }

    @Test
    @DisplayName("install should write the template and enable it")
    fun installShouldWriteTemplateAndEnableIt() {
        withTempDirs { availableDir, enabledDir ->
            val installation = LlmConfigTemplate.install(availableDir, enabledDir)

            assertNotNull(installation)
            assertEquals(LlmConfigTemplate.FILE_NAME, installation!!.fileName)
            assertTrue(installation.templateWritten, "the inert copy should be written")
            assertTrue(installation.enabledWritten, "the active copy should be written")
            assertTrue(installation.restartRequired, "a newly enabled file needs a restart")
            assertEquals(availableDir.resolve(LlmConfigTemplate.FILE_NAME), installation.availablePath)
            assertEquals(enabledDir.resolve(LlmConfigTemplate.FILE_NAME), installation.enabledPath)

            val enabled = enabledDir.resolve(LlmConfigTemplate.FILE_NAME)
            assertTrue(Files.exists(enabled), "the enabled file must exist at $enabled")
            assertTrue(
                Files.readString(enabled).contains("openrouter.api.key"),
                "the enabled file must carry the template content"
            )
            assertTrue(Files.exists(availableDir.resolve(LlmConfigTemplate.FILE_NAME)))
        }
    }

    @Test
    @DisplayName("install should be idempotent and never overwrite an edited file")
    fun installShouldBeIdempotent() {
        withTempDirs { availableDir, enabledDir ->
            LlmConfigTemplate.install(availableDir, enabledDir)

            // The user added a key: a second run must leave it alone.
            val enabled = enabledDir.resolve(LlmConfigTemplate.FILE_NAME)
            Files.writeString(enabled, "deepseek.api.key=my-own-key")

            val second = LlmConfigTemplate.install(availableDir, enabledDir)

            assertNotNull(second)
            assertFalse(second!!.templateWritten, "an existing template must not be rewritten")
            assertFalse(second.enabledWritten, "an existing enabled file must not be overwritten")
            assertFalse(second.restartRequired)
            assertEquals("deepseek.api.key=my-own-key", Files.readString(enabled))
        }
    }

    @Test
    @DisplayName("the reported paths should be the ones the configuration loader reads")
    fun reportedPathsShouldMatchTheLoader() {
        val enabled = LlmConfigTemplate.enabledPath().toString().replace('\\', '/')
        val available = LlmConfigTemplate.availablePath().toString().replace('\\', '/')

        assertTrue(
            enabled.endsWith("config/conf-enabled/${LlmConfigTemplate.FILE_NAME}"),
            "unexpected enabled path: $enabled"
        )
        assertTrue(
            available.endsWith("config/conf-available/${LlmConfigTemplate.FILE_NAME}"),
            "unexpected template path: $available"
        )
    }

    @Test
    @DisplayName("displayPath should abbreviate the home directory")
    fun displayPathShouldAbbreviateHome() {
        val home = System.getProperty("user.home")
        val file = Path.of(home, ".browser4", "config", "conf-enabled", LlmConfigTemplate.FILE_NAME)

        val display = LlmConfigTemplate.displayPath(file)

        assertTrue(display.startsWith("~"), "the home prefix should collapse to ~ but was: $display")
        assertTrue(display.endsWith(LlmConfigTemplate.FILE_NAME))
        assertFalse(display.contains(home), "the absolute home prefix must not leak: $display")
    }
}
