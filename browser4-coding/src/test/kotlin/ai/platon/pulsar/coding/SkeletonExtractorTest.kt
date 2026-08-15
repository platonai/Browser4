package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [SkeletonExtractor] — the extract-from-real-code scaffold mechanism.
 */
class SkeletonExtractorTest {

    private val sampleToolExecutor = """
        package ai.platon.pulsar.seo.tools

        import ai.platon.pulsar.agentic.model.ToolSpec
        import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
        import ai.platon.pulsar.api.WebDriver
        import ai.platon.pulsar.seo.service.SeoService

        open class SeoToolExecutor(
            private val seoService: SeoService,
        ) : AbstractToolExecutor() {

            override val domain = "seo"

            override val receiverClass: KClass<*> = WebDriver::class

            init {
                toolSpec["extractMeta"] = ToolSpec(
                    domain = domain,
                    method = "extractMeta",
                    arguments = emptyList(),
                    returnType = "Any",
                    description = "Extract metadata"
                )
            }

            override suspend fun callFunctionOn(
                domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
            ): Any? {
                return when (functionName) {
                    "extractMeta" -> seoService.extractMeta(receiver as WebDriver)
                    else -> throw IllegalArgumentException("Unsupported seo method")
                }
            }
        }
    """.trimIndent()

    @Test
    @DisplayName("extract finds package, class, domain, and method placeholders")
    fun extractFindsPlaceholders() {
        val skeleton = SkeletonExtractor.extract(sampleToolExecutor, "SeoToolExecutor.kt")

        assertEquals("ai.platon.pulsar.seo.tools", skeleton.parameters["basePackage"])
        assertEquals("SeoToolExecutor", skeleton.parameters["className"])
        assertEquals("seo", skeleton.parameters["domain"])
        assertEquals("extractMeta", skeleton.parameters["toolMethod"])
    }

    @Test
    @DisplayName("extract replaces identifiers with placeholders")
    fun extractReplacesIdentifiers() {
        val skeleton = SkeletonExtractor.extract(sampleToolExecutor, "SeoToolExecutor.kt")

        assertTrue(skeleton.template.contains("package {basePackage}"), "package not parameterized")
        assertTrue(skeleton.template.contains("open class {className}"), "class not parameterized")
        assertTrue(skeleton.template.contains("override val domain = \"{domain}\""), "domain not parameterized")
        assertTrue(skeleton.template.contains("toolSpec[\"{toolMethod}\"]"), "method not parameterized")
        assertTrue(skeleton.template.contains("import ai.platon.pulsar.agentic.model.ToolSpec"),
            "unrelated imports must be preserved verbatim")
    }

    @Test
    @DisplayName("instantiate applies new values and derives packagePath")
    fun instantiateAppliesValues() {
        val skeleton = SkeletonExtractor.extract(sampleToolExecutor, "SeoToolExecutor.kt")
        val out = SkeletonExtractor.instantiate(skeleton, mapOf(
            "basePackage" to "ai.platon.pulsar.weather.tools",
            "className" to "WeatherToolExecutor",
            "domain" to "weather",
            "toolMethod" to "fetchWeather",
        ))

        assertTrue(out.contains("package ai.platon.pulsar.weather.tools"))
        assertTrue(out.contains("open class WeatherToolExecutor"))
        assertTrue(out.contains("override val domain = \"weather\""))
        assertTrue(out.contains("toolSpec[\"fetchWeather\"]"))
        // packagePath derived from basePackage
        assertFalse(out.contains("{packagePath}"), "packagePath placeholder must resolve")
    }

    @Test
    @DisplayName("instantiate derives packagePath from basePackage")
    fun instantiateDerivesPackagePath() {
        val skeleton = SkeletonExtractor.extract(sampleToolExecutor, "SeoToolExecutor.kt")
        val out = SkeletonExtractor.instantiate(skeleton, mapOf(
            "basePackage" to "ai.platon.pulsar.weather.tools",
            "className" to "WeatherToolExecutor",
            "domain" to "weather",
            "toolMethod" to "fetchWeather",
        ))
        // The package line must carry the full new package.
        assertTrue(out.contains("package ai.platon.pulsar.weather.tools"), "package not applied")
        assertFalse(out.contains("{basePackage}"), "basePackage placeholder must resolve")
    }

    @Test
    @DisplayName("extract of file without class leaves template unchanged")
    fun extractNoClass() {
        val content = "package a.b\n\nfun helper() = 1"
        val skeleton = SkeletonExtractor.extract(content, "Helper.kt")
        assertNull(skeleton.parameters["className"])
        assertEquals("package {basePackage}\n\nfun helper() = 1", skeleton.template)
    }

    @Test
    @DisplayName("extract handles domain-less service files")
    fun extractServiceFile() {
        val content = """
            package ai.platon.pulsar.seo.service

            open class SeoService {
                fun extractMeta(driver: Any): Any? = null
            }
        """.trimIndent()
        val skeleton = SkeletonExtractor.extract(content, "SeoService.kt")
        assertEquals("ai.platon.pulsar.seo.service", skeleton.parameters["basePackage"])
        assertEquals("SeoService", skeleton.parameters["className"])
        assertNull(skeleton.parameters["domain"])
        assertNull(skeleton.parameters["toolMethod"])
    }

    // ==================== multi-file (directory) extraction ====================

    private val pluginFiles: Map<String, String> = linkedMapOf(
        "browser4-plugins/browser4-seo/pom.xml" to """
            <project>
                <parent>
                    <artifactId>browser4-pdk</artifactId>
                    <version>4.13.4-SNAPSHOT</version>
                </parent>
                <artifactId>browser4-seo</artifactId>
                <version>4.13.4-SNAPSHOT</version>
            </project>
        """.trimIndent(),
        "browser4-plugins/browser4-seo/src/main/kotlin/ai/platon/pulsar/seo/tools/SeoToolExecutor.kt" to sampleToolExecutor,
        "browser4-plugins/browser4-seo/src/main/kotlin/ai/platon/pulsar/seo/config/SeoAutoConfiguration.kt" to """
            package ai.platon.pulsar.seo.config

            import ai.platon.pulsar.agentic.tools.ToolMount
            import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
            import ai.platon.pulsar.seo.tools.SeoToolExecutor

            open class SeoAutoConfiguration : ToolMount {
                override fun getToolExecutors(): List<ToolExecutor> {
                    return listOf(SeoToolExecutor())
                }
            }
        """.trimIndent(),
    )

    @Test
    @DisplayName("extractDir discovers the union of parameters across files")
    fun extractDirUnionParameters() {
        val set = SkeletonExtractor.extractDir(pluginFiles)
        assertEquals("ai.platon.pulsar.seo", set.parameters["basePackage"],
            "basePackage must be the common package prefix")
        assertEquals("SeoToolExecutor", set.parameters["className"], "className = first discovered class")
        assertEquals("seo", set.parameters["domain"])
        assertEquals("extractMeta", set.parameters["toolMethod"])
        assertEquals("browser4-seo", set.parameters["artifactId"],
            "artifactId must be the module's own artifact, not the parent BOM")
    }

    @Test
    @DisplayName("extractDir parameterizes cross-file class references")
    fun extractDirCrossFileReference() {
        val set = SkeletonExtractor.extractDir(pluginFiles)
        val autoConfig = set.files.keys.first { it.endsWith("SeoAutoConfiguration.kt") }
        val template = set.files[autoConfig]!!.template
        assertTrue(template.contains("import {basePackage}.tools.{SeoToolExecutor}"),
            "executor class reference in AutoConfig must be parameterized, got: $template")
        assertTrue(template.contains("open class {SeoAutoConfiguration}"),
            "AutoConfig's own class must be a value-named placeholder, got: $template")
        val executorFile = set.files.keys.first { it.endsWith("SeoToolExecutor.kt") }
        assertTrue(set.files[executorFile]!!.template.contains("open class {SeoToolExecutor}"),
            "executor's own class must be a value-named placeholder")
        val pom = set.files.keys.first { it.endsWith("pom.xml") }
        assertTrue(set.files[pom]!!.template.contains("<artifactId>{artifactId}</artifactId>"),
            "pom artifactId must be parameterized")
    }

    @Test
    @DisplayName("instantiate of a set renames classes consistently across files")
    fun instantiateSetRenamesConsistently() {
        val set = SkeletonExtractor.extractDir(pluginFiles)
        val out = SkeletonExtractor.instantiate(set, mapOf(
            "basePackage" to "ai.platon.pulsar.weather",
            "className" to "WeatherToolExecutor",
            "domain" to "weather",
            "toolMethod" to "fetchWeather",
            "artifactId" to "browser4-weather",
        ))

        val executorFile = out.keys.first { it.endsWith("SeoToolExecutor.kt") }
        val executor = out[executorFile]!!
        assertTrue(executor.contains("package ai.platon.pulsar.weather.tools"), executor)
        assertTrue(executor.contains("open class WeatherToolExecutor"), executor)
        assertTrue(executor.contains("override val domain = \"weather\""), executor)
        assertTrue(executor.contains("toolSpec[\"fetchWeather\"]"), executor)

        val autoConfig = out.keys.first { it.endsWith("SeoAutoConfiguration.kt") }
        assertTrue(out[autoConfig]!!.contains("package ai.platon.pulsar.weather.config"),
            "AutoConfig package must follow the base rename: ${out[autoConfig]}")
        assertTrue(out[autoConfig]!!.contains("import ai.platon.pulsar.weather.tools.WeatherToolExecutor"),
            "AutoConfig import must follow the rename: ${out[autoConfig]}")
        assertTrue(out[autoConfig]!!.contains("listOf(WeatherToolExecutor())"),
            "AutoConfig instantiation must follow the rename: ${out[autoConfig]}")

        val pom = out.keys.first { it.endsWith("pom.xml") }
        assertTrue(out[pom]!!.contains("<artifactId>browser4-weather</artifactId>"), out[pom])
    }

    @Test
    @DisplayName("a second class is renamed via its own value-named key")
    fun instantiateSetRenamesSecondClass() {
        val set = SkeletonExtractor.extractDir(pluginFiles)
        val out = SkeletonExtractor.instantiate(set, mapOf(
            "className" to "WeatherToolExecutor",
            "SeoAutoConfiguration" to "WeatherAutoConfiguration",
        ))
        val autoConfig = out.keys.first { it.endsWith("SeoAutoConfiguration.kt") }
        assertTrue(out[autoConfig]!!.contains("open class WeatherAutoConfiguration"),
            "AutoConfig must rename via its own key: ${out[autoConfig]}")
    }

    @Test
    @DisplayName("instantiate without renames resolves to the discovered values")
    fun instantiateSetNoRename() {
        val set = SkeletonExtractor.extractDir(pluginFiles)
        val out = SkeletonExtractor.instantiate(set, emptyMap())
        val placeholder = Regex("""\{[A-Za-z][A-Za-z0-9_]*\}""")
        val autoConfig = out.keys.first { it.endsWith("SeoAutoConfiguration.kt") }
        assertTrue(out[autoConfig]!!.contains("import ai.platon.pulsar.seo.tools.SeoToolExecutor"),
            "no-rename instantiate must keep the discovered names: ${out[autoConfig]}")
        assertFalse(placeholder.containsMatchIn(out[autoConfig]!!),
            "no unresolved placeholders expected: ${out[autoConfig]}")
        val executor = out.keys.first { it.endsWith("SeoToolExecutor.kt") }
        assertTrue(out[executor]!!.contains("open class SeoToolExecutor"))
        assertFalse(placeholder.containsMatchIn(out[executor]!!),
            "no unresolved placeholders expected: ${out[executor]}")
    }
}
