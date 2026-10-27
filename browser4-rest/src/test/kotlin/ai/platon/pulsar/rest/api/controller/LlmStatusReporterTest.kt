package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.llm.Browser4ProviderRegistry
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.external.ChatModelFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LlmStatusReporter], the payload behind
 * `GET /api/doctor/llm-status`.
 *
 * The tests pin the behaviour that the DeepSeek-routing report needed: the
 * report must name the keys that are configured *and* where each one comes from,
 * must say which key wins the auto-detection race, and must name the model the
 * backend will actually request.
 */
class LlmStatusReporterTest {

    private val touchedProperties = listOf(
        "DEEPSEEK_API_KEY", "OPENAI_API_KEY", "OPENAI_MODEL_NAME", "OPENAI_BASE_URL", "llm.provider",
        "llm.provider.config.path",
        "browser4.test.llm.responseDir", "browser4.test.llm.responseFile",
    )

    @AfterEach
    fun cleanUp() {
        touchedProperties.forEach { System.clearProperty(it) }
        candidateKeyNames().forEach { System.clearProperty(it) }
    }

    /** The provider keys the report inspects, plus the generic legacy key. */
    private fun candidateKeyNames(): List<String> =
        (ChatModelFactory.SUPPORTED_API_KEY_NAMES + "LLM_API_KEY").distinct()

    /**
     * Blank every key that the process environment exports but [keep] excludes.
     *
     * Denying a provider only stops it from *winning* the auto-detection race: a
     * key that is present but denied still counts as "configured" (the historical
     * generosity callers rely on).  A developer machine that exports a real key
     * would therefore make "not configured" unobservable, so the exported keys are
     * pinned to an empty value in the system-property layer — the configuration
     * then reads a blank value instead of falling back to the environment.
     */
    private fun blankExportedKeys(vararg keep: String) {
        candidateKeyNames()
            .filterNot { it in keep }
            .filter { !System.getenv(it).isNullOrBlank() }
            .forEach { System.setProperty(it, "") }
    }

    /**
     * A configuration in which only [allowedKeys] can win, so that real keys
     * exported on a developer machine cannot change the outcome.
     */
    private fun isolatedConfig(vararg allowedKeys: String): MutableConfig =
        MutableConfig(false).apply {
            set(
                "llm.provider.deny.list",
                ChatModelFactory.SUPPORTED_API_KEY_NAMES
                    .filterNot { it in allowedKeys }
                    .joinToString(",")
            )
        }

    @Suppress("UNCHECKED_CAST")
    private fun configuredKeys(report: Map<String, Any?>): List<Map<String, Any?>> =
        report["configuredKeys"] as List<Map<String, Any?>>

    private fun keyEntry(report: Map<String, Any?>, key: String): Map<String, Any?>? =
        configuredKeys(report).firstOrNull { it["key"] == key }

    /**
     * `detectedVia` must describe the sources that are really in play: when every
     * configured key shares one source it must name it, otherwise it must say
     * "multiple" — never a single source that is not the whole truth (the bug was
     * a hard-coded "config_file" for environment-provided keys).
     */
    private fun assertDetectedViaIsHonest(report: Map<String, Any?>) {
        // The file-backed test LLM (enabled through the environment on a machine
        // that runs the e2e suite) legitimately wins over provider keys — but the
        // report must still agree that an LLM is configured.
        if (report["detectedVia"] == LlmStatusReporter.SOURCE_TEST_FILE_BACKED) {
            assertEquals(
                true,
                report["configured"],
                "a scripted model is a configured LLM; the honesty check must not go blind",
            )
            return
        }
        val sources = configuredKeys(report).map { it["source"] }.distinct()
        when (sources.size) {
            0 -> assertNull(report["detectedVia"])
            1 -> assertEquals(sources.first(), report["detectedVia"])
            else -> assertEquals(LlmStatusReporter.SOURCE_MULTIPLE, report["detectedVia"])
        }
    }

    @Test
    @DisplayName("the file-backed test LLM is reported as test_file_backed, not as 'no LLM'")
    fun fileBackedTestLlmIsReported() {
        // The e2e harness gates every mock-LLM scenario on exactly this value; a
        // null here made the whole family skip itself ("backend does not support
        // file-backed mock LLM responses").
        System.setProperty("browser4.test.llm.responseDir", "does-not-need-to-exist")

        val report = LlmStatusReporter.report(isolatedConfig())

        assertEquals(true, report["configured"], "a scripted model IS a configured LLM")
        assertEquals(LlmStatusReporter.SOURCE_TEST_FILE_BACKED, report["detectedVia"])

        System.clearProperty("browser4.test.llm.responseDir")
        // The mirror property is accepted too (JVM tests enable the mock without
        // touching the process environment).
        System.setProperty("browser4.test.llm.responseFile", "does-not-need-to-exist")
        assertEquals(
            LlmStatusReporter.SOURCE_TEST_FILE_BACKED,
            LlmStatusReporter.report(isolatedConfig())["detectedVia"],
        )
    }

    @Test
    @DisplayName("report should name the configured key, its source, and the winning key")
    fun reportShouldNameConfiguredKeysAndSelection() {
        System.setProperty("DEEPSEEK_API_KEY", "test-deepseek-key-12345")
        val conf = isolatedConfig("DEEPSEEK_API_KEY")

        val report = LlmStatusReporter.report(conf)

        assertEquals(true, report["configured"])
        assertDetectedViaIsHonest(report)

        val entry = keyEntry(report, "DEEPSEEK_API_KEY")
        assertNotNull(entry)
        assertEquals(LlmStatusReporter.SOURCE_SYSTEM_PROPERTY, entry!!["source"])

        assertEquals("DEEPSEEK_API_KEY", report["selectedKey"])
        assertEquals("auto-detection", report["selectedBy"])
        assertEquals(false, report["multipleProviders"])
        assertTrue((report["warning"] as String?) == null, "a single provider is not a warning")
    }

    @Test
    @DisplayName("report should expose the model the backend will actually request")
    fun reportShouldExposeTheActiveModel() {
        System.setProperty("DEEPSEEK_API_KEY", "test-deepseek-key-12345")
        val conf = isolatedConfig("DEEPSEEK_API_KEY")

        val report = LlmStatusReporter.report(conf)

        @Suppress("UNCHECKED_CAST")
        val activeModel = report["activeModel"] as Map<String, Any?>?
        assertNotNull(activeModel)
        assertEquals("deepseek-v4-flash", activeModel!!["model"])
        assertNotNull(activeModel["client"])
    }

    @Test
    @DisplayName("report should warn when several providers compete")
    fun reportShouldWarnWhenSeveralProvidersCompete() {
        // The scenario from the report: an OpenAI-compatible key was added while a
        // DeepSeek key was still configured, and DeepSeek precedes OpenAI.
        System.setProperty("DEEPSEEK_API_KEY", "test-deepseek-key-12345")
        System.setProperty("OPENAI_API_KEY", "test-openai-key-12345")
        val conf = isolatedConfig("DEEPSEEK_API_KEY", "OPENAI_API_KEY")

        val report = LlmStatusReporter.report(conf)

        assertEquals(true, report["multipleProviders"])
        assertEquals("DEEPSEEK_API_KEY", report["selectedKey"])
        val warning = report["warning"] as String?
        assertNotNull(warning)
        assertTrue(warning!!.contains("DEEPSEEK_API_KEY"), "warning must name the winning key: $warning")
        assertTrue(warning.contains("OPENAI_API_KEY"), "warning must name the ignored key: $warning")
        assertTrue(warning.contains("llm.provider"), "warning must name the escape hatch: $warning")
    }

    @Test
    @DisplayName("report should flag keys that are set but empty")
    fun reportShouldFlagEmptyKeys() {
        // `openai.api.key=` in a properties file resolves to a blank value.
        System.setProperty("DEEPSEEK_API_KEY", "test-deepseek-key-12345")
        System.setProperty("OPENAI_API_KEY", "")
        val conf = isolatedConfig("DEEPSEEK_API_KEY", "OPENAI_API_KEY")

        val report = LlmStatusReporter.report(conf)

        assertEquals(listOf("OPENAI_API_KEY"), report["emptyKeys"])
        assertEquals("DEEPSEEK_API_KEY", report["selectedKey"])
        val warning = report["warning"] as String?
        assertNotNull(warning)
        assertTrue(warning!!.contains("empty"), "warning must explain the empty key: $warning")
    }

    @Test
    @DisplayName("report should report the explicit llm.provider selection")
    fun reportShouldReportExplicitSelection() {
        System.setProperty("OPENAI_API_KEY", "test-openai-key-12345")
        val conf = isolatedConfig("OPENAI_API_KEY")
        conf.set("llm.provider", "openai")

        val report = LlmStatusReporter.report(conf)

        assertEquals(true, report["configured"])
        assertEquals("llm.provider=openai", report["selectedBy"])
        assertNull(report["selectedKey"])
    }

    @Test
    @DisplayName("report should follow Browser4's own registry instead of the base library's order")
    fun reportShouldFollowBrowser4RegistryOrder() {
        // Browser4 ships its own provider registry (config/llm/providers.json) and
        // puts OpenAI before Groq; the base library's built-in list puts Groq first.
        // A report built from the built-in order would name a "selected key" the
        // engine never uses, which is exactly the misreport this test pins down.
        val configDir = java.nio.file.Files.createTempDirectory("b4-status-registry")
        try {
            val registry = Browser4ProviderRegistry.install(configDir)
            assertNotNull(registry, "the packaged registry must be installable")

            System.setProperty("OPENAI_API_KEY", "test-openai-key-12345")
            System.setProperty("GROQ_API_KEY", "test-groq-key-12345")
            val conf = isolatedConfig("OPENAI_API_KEY", "GROQ_API_KEY")

            val report = LlmStatusReporter.report(conf)

            assertEquals("OPENAI_API_KEY", report["selectedKey"])
            val warning = report["warning"] as String?
            assertNotNull(warning)
            assertTrue(
                warning!!.contains("OPENAI_API_KEY"),
                "the warning must name the key that actually wins: $warning"
            )
        } finally {
            System.clearProperty("llm.provider.config.path")
            configDir.toFile().deleteRecursively()
        }
    }

    @Test
    @DisplayName("report should say not configured when no key is usable")
    fun reportShouldSayNotConfigured() {
        System.setProperty("OPENAI_API_KEY", "")
        // A key exported by the developer's shell is *present*, and a present key
        // counts as configured even when its provider is denied — pin it blank so
        // that "nothing is usable" is what the report actually sees.
        blankExportedKeys("OPENAI_API_KEY")
        val conf = isolatedConfig("OPENAI_API_KEY")

        val report = LlmStatusReporter.report(conf)

        assertEquals(false, report["configured"])
        assertNotNull(report["message"])
        assertNull(report["activeModel"])
        assertFalse((report["warning"] as String?).isNullOrEmpty(), "the empty key must be explained")
    }

    @Test
    @DisplayName("report should name the writable configuration file")
    fun reportShouldNameTheWritableConfigFile() {
        val report = LlmStatusReporter.report(isolatedConfig())

        @Suppress("UNCHECKED_CAST")
        val configFile = report["configFile"] as Map<String, Any?>?
        assertNotNull(configFile)
        assertNotNull(configFile!!["enabledExists"], "existence must be reported, not assumed")

        val display = configFile["enabledPathDisplay"] as String
        assertTrue(
            display.replace('\\', '/').endsWith("config/conf-enabled/${LlmConfigTemplate.FILE_NAME}"),
            "the report must name the file the loader reads, actual: $display"
        )
    }
}
