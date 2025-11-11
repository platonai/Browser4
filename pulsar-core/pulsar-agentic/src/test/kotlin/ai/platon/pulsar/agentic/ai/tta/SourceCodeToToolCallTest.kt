package ai.platon.pulsar.agentic.ai.tta

import ai.platon.pulsar.common.ResourceLoader
import ai.platon.pulsar.common.code.ProjectUtils
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.exists

class SourceCodeToToolCallTest {
    @Test
    fun `extract methods from WebDriver resource`() {
        val code = ResourceLoader.readString("code-mirror/WebDriver.kt")
        val tools = SourceCodeToToolCallSpec.extract("driver", code)
        assertTrue(tools.isNotEmpty(), "Tool list should not be empty")
        val click = tools.firstOrNull { it.domain == "driver" && it.method == "click" }
        assertNotNull(click, "Should contain driver.click method")
        assertTrue(click!!.arguments.map { it.name }.contains("selector"), "click should have selector argument")
    }

    @Test
    fun `generate ToolCallSpec from WebDriver using reflection`() {
        // Generate ToolCallSpec list from WebDriver interface using reflection
        val toolSpecs = SourceCodeToToolCallSpec.generateFromReflection(
            domain = "driver",
            interfaceClass = WebDriver::class,
            outputFileName = "webdriver-toolcall-specs-test.json"
        )

        // Verify the list is not empty
        assertTrue(toolSpecs.isNotEmpty(), "Generated tool spec list should not be empty")

        // Verify the JSON file was created
        val rootDir = ProjectUtils.findProjectRootDir()
        if (rootDir != null) {
            val jsonFile = rootDir.resolve(ProjectUtils.CODE_RESOURCE_DIR)
                .resolve("webdriver-toolcall-specs-test.json")
            assertTrue(jsonFile.exists(), "JSON file should be created in CODE_RESOURCE_DIR")

            // Verify file has content
            val fileSize = Files.size(jsonFile)
            assertTrue(fileSize > 0, "JSON file should have content")

            // Clean up test file
            Files.deleteIfExists(jsonFile)
        }

        // Verify some expected methods exist
        val navigateTo = toolSpecs.firstOrNull { it.method == "navigateTo" }
        assertNotNull(navigateTo, "Should contain navigateTo method")

        val click = toolSpecs.firstOrNull { it.method == "click" }
        assertNotNull(click, "Should contain click method")
        assertTrue(click!!.arguments.isNotEmpty(), "click should have arguments")

        val currentUrl = toolSpecs.firstOrNull { it.method == "currentUrl" }
        assertNotNull(currentUrl, "Should contain currentUrl method")

        // Verify domain is set correctly
        toolSpecs.forEach { spec ->
            assertEquals("driver", spec.domain, "All specs should have domain 'driver'")
        }
    }

    @Test
    fun `generate and save permanent JSON file for WebDriver`() {
        // Generate the actual JSON file that should be committed
        val toolSpecs = SourceCodeToToolCallSpec.generateFromReflection(
            domain = "driver",
            interfaceClass = WebDriver::class,
            outputFileName = "webdriver-toolcall-specs.json"
        )

        // Verify the list is not empty
        assertTrue(toolSpecs.isNotEmpty(), "Generated tool spec list should not be empty")
        println("Generated ${toolSpecs.size} tool call specs from WebDriver interface")

        // Verify the JSON file was created
        val rootDir = ProjectUtils.findProjectRootDir()
        assertNotNull(rootDir, "Project root directory should be found")
        
        val jsonFile = rootDir!!.resolve(ProjectUtils.CODE_RESOURCE_DIR)
            .resolve("webdriver-toolcall-specs.json")
        assertTrue(jsonFile.exists(), "JSON file should be created in CODE_RESOURCE_DIR")

        // Verify file has content
        val fileSize = Files.size(jsonFile)
        assertTrue(fileSize > 100, "JSON file should have substantial content (at least 100 bytes)")
        println("JSON file created at: ${jsonFile.toAbsolutePath()}")
        println("File size: $fileSize bytes")
    }
}
