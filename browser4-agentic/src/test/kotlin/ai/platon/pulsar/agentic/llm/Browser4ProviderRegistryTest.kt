package ai.platon.pulsar.agentic.llm

import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.external.ChatModelFactory
import ai.platon.pulsar.external.ProviderConfigLoader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [Browser4ProviderRegistry], the mechanism that keeps the LLM
 * provider order under Browser4's control instead of following the base library.
 *
 * The provider order decides *where a request goes* when several API keys are
 * configured, so it is a product decision: an upgrade of `browser4-base` must not
 * be able to change it silently.
 */
class Browser4ProviderRegistryTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("b4-provider-registry")
        System.clearProperty(PROPERTY)
        ChatModelFactory.resetProviders()
    }

    @AfterEach
    fun tearDown() {
        System.clearProperty(PROPERTY)
        ChatModelFactory.resetProviders()
        tempDir.toFile().deleteRecursively()
    }

    /** A configuration whose selection cannot be influenced by keys of this machine. */
    private fun isolatedConfig(vararg allowedKeys: String): MutableConfig =
        MutableConfig(false).apply {
            set(
                "llm.provider.deny.list",
                ProviderConfigLoader.loadDefault().providers
                    .map { it.apiKeyName }
                    .filterNot { it in allowedKeys }
                    .joinToString(",")
            )
        }

    @Test
    @DisplayName("install should materialize the packaged registry and point the engine at it")
    fun installShouldMaterializeThePackagedRegistry() {
        val target = Browser4ProviderRegistry.install(tempDir)

        assertNotNull(target, "the packaged registry must be installable")
        assertTrue(Files.exists(target!!), "install must write the registry file")
        assertEquals(
            target.toAbsolutePath().toString(),
            System.getProperty(PROPERTY),
            "the engine must be pointed at the installed copy"
        )
        assertEquals(
            "providers.json",
            target.fileName.toString(),
            "the file name must stay the one the engine's loader expects"
        )

        // The materialized copy is the packaged resource, not an empty placeholder.
        val installed = Files.readAllBytes(target).toString(Charsets.UTF_8)
        assertTrue(installed.contains("\"openrouter\""), installed.take(120))
        assertTrue(installed.contains("\"stepfun\""), "the full provider catalog must be preserved")
    }

    @Test
    @DisplayName("install should not overwrite an explicitly configured registry")
    fun installShouldRespectAnExplicitOverride() {
        val explicit = tempDir.resolve("my-registry.json")
        System.setProperty(PROPERTY, explicit.toString())

        val installed = Browser4ProviderRegistry.install(tempDir)

        assertNull(installed, "an explicit override must win over Browser4's packaged copy")
        assertFalse(Files.exists(tempDir.resolve(Browser4ProviderRegistry.FILE_NAME)))
        assertEquals(explicit.toString(), System.getProperty(PROPERTY))
    }

    @Test
    @DisplayName("Browser4's registry should decide which of two configured keys wins")
    fun browser4RegistryShouldDecideTheWinner() {
        // Groq precedes OpenAI in the base library's built-in order.  Browser4's
        // registry puts OpenAI first, so this outcome is only possible when the
        // installed registry — not the base copy — is what the engine reads.
        Browser4ProviderRegistry.install(tempDir)
        val conf = isolatedConfig("OPENAI_API_KEY", "GROQ_API_KEY").apply {
            set("OPENAI_API_KEY", "test-openai-key-12345")
            set("GROQ_API_KEY", "test-groq-key-12345")
        }

        val selection = ChatModelFactory.describeActiveProvider(conf)

        assertNotNull(selection)
        assertEquals("openai", selection!!.provider, "Browser4 decides the provider priority")
        assertEquals("OPENAI_API_KEY", selection.apiKeyName)
    }

    @Test
    @DisplayName("Browser4's registry should cover every provider the base library knows")
    fun browser4RegistryShouldCoverEveryBuiltInProvider() {
        // The external registry *replaces* the built-in one.  A provider added to
        // browser4-base therefore disappears from Browser4 unless it is added here,
        // which is why the two catalogs are compared instead of only the order.
        Browser4ProviderRegistry.install(tempDir)
        val conf = MutableConfig(false)

        val base = ProviderConfigLoader.loadDefault()
        val browser4 = ProviderConfigLoader.load(conf)

        assertEquals(
            base.providers.map { it.providerName }.toSet(),
            browser4.providers.map { it.providerName }.toSet(),
            "browser4-base changed its provider catalog; update config/llm/providers.json"
        )
        assertEquals(
            base.aliases.keys,
            browser4.aliases.keys,
            "browser4-base changed its API key aliases; update config/llm/providers.json"
        )
        assertEquals(
            base.canonicalAliases.keys,
            browser4.canonicalAliases.keys,
            "browser4-base changed its provider name aliases; update config/llm/providers.json"
        )
    }

    private companion object {
        const val PROPERTY = "llm.provider.config.path"
    }
}
