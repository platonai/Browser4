package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.assertThrows

class ArtifactScaffoldsTest {

    // --- Plugin scaffold ---

    @Test
    @DisplayName("pluginScaffold generates all required files")
    fun pluginScaffoldGeneratesAllFiles() {
        val result = ArtifactScaffolds.pluginScaffold(
            pluginName = "browser4-seo",
            domain = "seo",
            basePackage = "ai.platon.pulsar.seo",
            toolMethod = "extractMeta",
            toolDescription = "Extract SEO metadata from the page"
        )

        assertTrue(result.containsKey("pom.xml"))
        assertTrue(result.containsKey("build.ps1"))
        assertTrue(result.containsKey("src/main/resources/META-INF/browser4-plugin.json"))
        assertTrue(result.containsKey("src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"))
        assertTrue(result.containsKey("src/main/resources/seo/extractMeta.js"))
        assertTrue(result.containsKey("src/main/kotlin/ai/platon/pulsar/seo/service/SeoService.kt"))
        assertTrue(result.containsKey("README.md"))
        assertEquals(10, result.size)
    }

    @Test
    @DisplayName("pluginScaffold build.ps1 verifies JAR structure")
    fun pluginScaffoldBuildScript() {
        val result = ArtifactScaffolds.pluginScaffold(
            pluginName = "browser4-seo",
            domain = "seo",
            basePackage = "ai.platon.pulsar.seo",
            toolMethod = "extractMeta",
            toolDescription = "Extract SEO metadata from the page"
        )

        val script = result["build.ps1"]!!
        assertTrue(script.contains("mvn package -DskipTests"))
        assertTrue(script.contains("META-INF/browser4-plugin.json"))
        assertTrue(script.contains("AutoConfiguration.imports"))
        assertTrue(script.contains("seo/extractMeta.js"))
        assertTrue(script.contains("-RestInstall"))
        // Class names must be interpolated, not left as literal $vars (regression:
        // a prior template emitted literal "\$autoConfigClass" which breaks the check).
        assertTrue(script.contains("SeoAutoConfiguration.class"), "class name not interpolated: $script")
        assertTrue(script.contains("SeoToolExecutor.class"), "class name not interpolated: $script")
        assertFalse(script.contains("\$autoConfigClass"), "literal \$autoConfigClass leaked: $script")
    }

    @Test
    @DisplayName("pluginScaffold generates browser-side JS resource")
    fun pluginScaffoldJsResource() {
        val result = ArtifactScaffolds.pluginScaffold(
            pluginName = "browser4-seo",
            domain = "seo",
            basePackage = "ai.platon.pulsar.seo",
            toolMethod = "extractMeta",
            toolDescription = "Extract SEO metadata"
        )

        val js = result["src/main/resources/seo/extractMeta.js"]!!
        assertTrue(js.contains("(function"))
        assertTrue(js.contains("location.href"))
        assertTrue(js.contains("JSON.stringify"))
    }

    @Test
    @DisplayName("pluginScaffold Service loads JS and runs via WebDriver.evaluateValue")
    fun pluginScaffoldService() {
        val result = ArtifactScaffolds.pluginScaffold(
            pluginName = "browser4-seo",
            domain = "seo",
            basePackage = "ai.platon.pulsar.seo",
            toolMethod = "extractMeta",
            toolDescription = "Extract SEO metadata"
        )

        val service = result["src/main/kotlin/ai/platon/pulsar/seo/service/SeoService.kt"]!!
        assertTrue(service.contains("WebDriver"))
        assertTrue(service.contains("evaluateValue"))
        assertTrue(service.contains("loadResource(\"/seo/extractMeta.js\")"))
        assertTrue(service.contains("fun extractMeta(driver: WebDriver): Any?"))
    }

    @Test
    @DisplayName("pluginScaffold pom.xml contains parent browser4-pdk")
    fun pluginScaffoldPomHasParent() {
        val result = ArtifactScaffolds.pluginScaffold(
            pluginName = "test-plugin",
            domain = "test",
            basePackage = "ai.platon.pulsar.test",
            toolMethod = "doTest",
            toolDescription = "Test tool"
        )

        val pom = result["pom.xml"]!!
        assertTrue(pom.contains("browser4-pdk"))
        assertTrue(pom.contains("<artifactId>test-plugin</artifactId>"))
        assertTrue(pom.contains("provided"))
    }

    @Test
    @DisplayName("pluginScaffold plugin.json has required fields")
    fun pluginScaffoldJsonHasRequiredFields() {
        val result = ArtifactScaffolds.pluginScaffold(
            pluginName = "test-plugin",
            domain = "test",
            basePackage = "ai.platon.pulsar.test",
            toolMethod = "doTest",
            toolDescription = "Test tool"
        )

        val json = result["src/main/resources/META-INF/browser4-plugin.json"]!!
        assertTrue(json.contains("\"name\": \"test-plugin\""))
        assertTrue(json.contains("\"version\""))
        assertTrue(json.contains("\"dependsOn\""))
        assertTrue(json.contains("\"autoConfigurationClasses\""))
        assertTrue(json.contains("ai.platon.pulsar.test.config.TestPluginAutoConfiguration"))
        assertFalse(json.contains("\"id\""))
        assertFalse(json.contains("\"domain\""))
    }

    @Test
    @DisplayName("pluginScaffold AutoConfiguration.imports has correct class name")
    fun pluginScaffoldImportsCorrect() {
        val result = ArtifactScaffolds.pluginScaffold(
            pluginName = "browser4-seo",
            domain = "seo",
            basePackage = "ai.platon.pulsar.seo",
            toolMethod = "extractMeta",
            toolDescription = "Extract SEO metadata"
        )

        val imports = result["src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"]!!
        assertTrue(imports.contains("ai.platon.pulsar.seo.config.SeoAutoConfiguration"))
    }

    @Test
    @DisplayName("pluginScaffold ToolExecutor has domain and callFunctionOn")
    fun pluginScaffoldToolExecutorStructure() {
        val result = ArtifactScaffolds.pluginScaffold(
            pluginName = "browser4-seo",
            domain = "seo",
            basePackage = "ai.platon.pulsar.seo",
            toolMethod = "extractMeta",
            toolDescription = "Extract SEO metadata"
        )

        val executorFile = result.entries.find { it.key.endsWith("SeoToolExecutor.kt") }!!.value
        assertTrue(executorFile.contains("override val domain = \"seo\""))
        assertTrue(executorFile.contains("callFunctionOn"))
        assertTrue(executorFile.contains("extractMeta"))
        assertTrue(executorFile.contains("package ai.platon.pulsar.seo.tools"))
        assertTrue(executorFile.contains("receiverClass: KClass<*> = WebDriver::class"))
        assertTrue(executorFile.contains("private val service: SeoService"))
        assertTrue(executorFile.contains("service.extractMeta(driver)"))
    }

    @Test
    @DisplayName("pluginScaffold Config extends ImmutableConfig")
    fun pluginScaffoldConfigStructure() {
        val result = ArtifactScaffolds.pluginScaffold(
            pluginName = "browser4-seo",
            domain = "seo",
            basePackage = "ai.platon.pulsar.seo",
            toolMethod = "extractMeta",
            toolDescription = "Extract SEO metadata"
        )

        val configFile = result.entries.find { it.key.endsWith("SeoConfig.kt") }!!.value
        assertTrue(configFile.contains("ImmutableConfig"))
        assertTrue(configFile.contains("MutableConfig"))
    }

    // --- Skill scaffold ---

    @Test
    @DisplayName("skillScaffold has YAML frontmatter")
    fun skillScaffoldHasFrontmatter() {
        val result = ArtifactScaffolds.skillScaffold(
            name = "my-skill",
            description = "A test skill",
            triggers = listOf("When user asks to test", "When testing is needed"),
            tools = listOf("coding.read", "coding.write")
        )

        assertTrue(result.startsWith("---"))
        assertTrue(result.contains("name: my-skill"))
        assertTrue(result.contains("description: \"A test skill\""))
        assertTrue(result.contains("allowed-tools: coding.read coding.write"))
        assertTrue(result.contains("When user asks to test"))
        assertTrue(result.contains("When testing is needed"))
        assertTrue(result.contains("coding.read"))
        assertTrue(result.contains("coding.write"))
    }

    @Test
    @DisplayName("skillScaffold works with empty triggers and tools")
    fun skillScaffoldEmptyParams() {
        val result = ArtifactScaffolds.skillScaffold(
            name = "simple",
            description = "Simple skill",
            triggers = emptyList(),
            tools = emptyList()
        )

        assertTrue(result.contains("name: simple"))
        assertTrue(result.contains("## When to Use"))
    }

    // --- JS scaffold ---

    @Test
    @DisplayName("jsScaffold extract template has IIFE and return")
    fun jsScaffoldExtractTemplate() {
        val result = ArtifactScaffolds.jsScaffold("extract-data", "extract")

        assertTrue(result.contains("'use strict'"))
        assertTrue(result.contains("(function"))
        assertTrue(result.contains("return JSON.stringify"))
        assertTrue(result.contains("window.location.href"))
    }

    @Test
    @DisplayName("jsScaffold inject template modifies DOM")
    fun jsScaffoldInjectTemplate() {
        val result = ArtifactScaffolds.jsScaffold("inject-content", "inject")

        assertTrue(result.contains("'use strict'"))
        assertTrue(result.contains("modified"))
        assertTrue(result.contains("return JSON.stringify"))
    }

    @Test
    @DisplayName("jsScaffold interact template has click pattern")
    fun jsScaffoldInteractTemplate() {
        val result = ArtifactScaffolds.jsScaffold("click-button", "interact")

        assertTrue(result.contains("'use strict'"))
        assertTrue(result.contains("result.success"))
    }

    @Test
    @DisplayName("jsScaffold unknown purpose defaults to extract")
    fun jsScaffoldUnknownPurpose() {
        val result = ArtifactScaffolds.jsScaffold("test", "unknown")

        assertTrue(result.contains("'use strict'"))
        assertTrue(result.contains("extract"))
    }

    // --- Script scaffold ---

    @Test
    @DisplayName("scriptScaffold PS1 has param and ErrorActionPreference")
    fun scriptScaffoldPs1() {
        val result = ArtifactScaffolds.scriptScaffold("build", "build", "ps1")

        assertTrue(result.contains("param("))
        assertTrue(result.contains("ErrorActionPreference"))
        assertTrue(result.contains("SYNOPSIS"))
    }

    @Test
    @DisplayName("scriptScaffold Bash has shebang and set -e")
    fun scriptScaffoldBash() {
        val result = ArtifactScaffolds.scriptScaffold("build", "build", "bash")

        assertTrue(result.startsWith("#!/usr/bin/env bash"))
        assertTrue(result.contains("set -euo pipefail"))
    }

    // --- Dispatch ---

    @Test
    @DisplayName("scaffold dispatch returns plugin files for type=plugin")
    fun scaffoldDispatchPlugin() {
        val result = ArtifactScaffolds.scaffold("plugin", mapOf(
            "pluginName" to "test-plugin",
            "domain" to "test",
            "basePackage" to "ai.platon.pulsar.test",
            "toolMethod" to "doTest",
            "toolDescription" to "Test"
        ))

        assertTrue(result.size > 1)
        assertTrue(result.containsKey("pom.xml"))
    }

    @Test
    @DisplayName("scaffold dispatch returns single content for type=skill")
    fun scaffoldDispatchSkill() {
        val result = ArtifactScaffolds.scaffold("skill", mapOf(
            "name" to "my-skill",
            "description" to "Test"
        ))

        assertEquals(1, result.size)
        assertTrue(result.containsKey("_content"))
        assertTrue(result["_content"]!!.contains("name: my-skill"))
    }

    @Test
    @DisplayName("scaffold dispatch returns single content for type=js")
    fun scaffoldDispatchJs() {
        val result = ArtifactScaffolds.scaffold("js", mapOf(
            "name" to "test",
            "purpose" to "extract"
        ))

        assertEquals(1, result.size)
        assertTrue(result["_content"]!!.contains("'use strict'"))
    }

    @Test
    @DisplayName("scaffold dispatch returns single content for type=script")
    fun scaffoldDispatchScript() {
        val result = ArtifactScaffolds.scaffold("script", mapOf(
            "name" to "build",
            "shell" to "bash"
        ))

        assertEquals(1, result.size)
        assertTrue(result["_content"]!!.contains("#!/usr/bin/env bash"))
    }

    @Test
    @DisplayName("scaffold throws for unknown type")
    fun scaffoldUnknownType() {
        assertThrows<IllegalArgumentException> {
            ArtifactScaffolds.scaffold("unknown", emptyMap())
        }
    }

    // --- Helpers ---

    @Test
    @DisplayName("toClassName converts kebab-case to PascalCase")
    fun toClassNameTest() {
        assertEquals("Browser4Seo", ArtifactScaffolds.toClassName("browser4-seo"))
        assertEquals("MyPlugin", ArtifactScaffolds.toClassName("my-plugin"))
        assertEquals("Simple", ArtifactScaffolds.toClassName("simple"))
    }

    @Test
    @DisplayName("toCamelCase converts kebab-case to camelCase")
    fun toCamelCaseTest() {
        assertEquals("browser4Seo", ArtifactScaffolds.toCamelCase("browser4-seo"))
        assertEquals("myPlugin", ArtifactScaffolds.toCamelCase("my-plugin"))
    }
}



