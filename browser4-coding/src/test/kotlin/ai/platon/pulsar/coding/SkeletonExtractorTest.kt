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
}
