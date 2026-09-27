package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.external.ChatModelFactory
import ai.platon.pulsar.external.impl.CachedBrowserChatModel

/**
 * Builds the LLM status report served by `GET /api/doctor/llm-status`.
 *
 * The report answers two different questions, and keeps them apart on purpose:
 *
 * 1. *Is an LLM configured?* — decided by [ChatModelFactory], exactly as it is for
 *    every LLM-backed command.
 * 2. *Which provider will the requests actually go to?* — the winner of the
 *    built-in priority list, the model the client will request, and the
 *    configuration key that supplied the API key.
 *
 * Answering only the first question is what made "✓ LLM is configured" coexist
 * with a request that went to DeepSeek: any configured provider key wins over the
 * one the user just added when it sits higher in the priority list, and a stale
 * `deepseek.api.key` in the very same properties file does exactly that.  Reporting
 * the configured keys *with their source* names the culprit instead of hiding it.
 *
 * Kept free of Spring so that it can be unit tested directly.
 */
object LlmStatusReporter {

    /** A key that is set, and where it was set. */
    private data class ConfiguredKey(val key: String, val source: String, val usable: Boolean)

    /** Value of the `detectedVia` field when keys come from more than one kind of source. */
    const val SOURCE_MULTIPLE = "multiple"

    const val SOURCE_ENV = "env"
    const val SOURCE_SYSTEM_PROPERTY = "system_property"
    const val SOURCE_CONFIGURATION = "configuration"

    /** The generic legacy key, checked after every provider key — same order as the SDK. */
    private val LEGACY_API_KEY_NAMES = listOf("LLM_API_KEY")

    /** Provider key names in detection order, plus the generic legacy key. */
    private fun candidateKeyNames(): List<String> {
        val supported = runCatching { ChatModelFactory.SUPPORTED_API_KEY_NAMES }
            .getOrElse { emptyList() }
        return (supported + LEGACY_API_KEY_NAMES).distinct()
    }

    /**
     * Where [keyName] is set: a JVM system property, an environment variable, or a
     * configuration file.
     *
     * Both the provider spelling (`OPENAI_API_KEY`) and the property spelling
     * (`openai.api.key`) are honored, because the SDK accepts either.
     */
    private fun sourceOf(keyName: String): String = when {
        !System.getProperty(keyName).isNullOrBlank() -> SOURCE_SYSTEM_PROPERTY
        !System.getenv(keyName).isNullOrBlank() -> SOURCE_ENV
        else -> SOURCE_CONFIGURATION
    }

    /** The provider keys that are set, in detection order. */
    private fun configuredKeys(conf: ImmutableConfig, keyNames: List<String>): List<ConfiguredKey> {
        return keyNames.mapNotNull { key ->
            val value = runCatching { conf[key] }.getOrNull() ?: return@mapNotNull null
            ConfiguredKey(key, sourceOf(key), value.isNotBlank())
        }
    }

    /**
     * The model the SDK would request, read back from the client it builds.
     *
     * This is the ground truth for "where do my requests go?": it distinguishes
     * `deepseek-v4-flash` (the DeepSeek default that won the race) from the model
     * configured for another provider.  Failures are reported as `null` rather
     * than failing the whole status report.
     */
    private fun activeModel(conf: ImmutableConfig): Map<String, Any?>? = runCatching {
        val model = ChatModelFactory.getOrCreateOrNull(conf) ?: return@runCatching null
        val langchainModel = (model as? CachedBrowserChatModel)?.langchainModel ?: return@runCatching null
        val modelName = runCatching { langchainModel.defaultRequestParameters().modelName() }.getOrNull()
            ?: return@runCatching null
        mapOf("model" to modelName, "client" to langchainModel.javaClass.simpleName)
    }.getOrNull()

    /** Whether the SDK skips [keyName] because its provider is on the deny list. */
    private fun isDenied(keyName: String, conf: ImmutableConfig): Boolean =
        runCatching { ChatModelFactory.isProviderDenied(keyName, conf) }.getOrDefault(false)

    /**
     * Build the status report for [conf].
     *
     * @param conf The configuration the backend actually uses for LLM calls.
     * @return A JSON-serializable report.  The fields `configured`, `detectedVia`,
     *         `foundEnvVars`, `foundProperties`, `keyPrefixes` and `message` are
     *         unchanged for existing clients; `configuredKeys`, `emptyKeys`,
     *         `selectedKey`, `selectedBy`, `multipleProviders`, `warning` and
     *         `activeModel` are additions.
     */
    fun report(conf: ImmutableConfig): Map<String, Any?> {
        val keyNames = candidateKeyNames()
        val present = configuredKeys(conf, keyNames)
        val usable = present.filter { it.usable }
        val emptyKeys = present.filterNot { it.usable }.map { it.key }

        // Denied providers never win the auto-detection race, so they must not be
        // reported as the selected key either.
        val competing = usable.filterNot { isDenied(it.key, conf) }

        val factorySaysConfigured = runCatching { ChatModelFactory.isModelConfigured(conf, verbose = false) }
            .getOrElse { competing.isNotEmpty() }

        // Keep the historical generosity: a key present in the backend environment
        // counts as configured even when the factory cannot build a client with it.
        val configured = factorySaysConfigured || usable.isNotEmpty()

        val explicitProvider = runCatching { conf["llm.provider"] }.getOrNull()?.takeIf { it.isNotBlank() }

        // SUPPORTED_API_KEY_NAMES is ordered by detection priority, so the first
        // usable key is the one that wins the auto-detection race.
        val selectedKey = if (explicitProvider == null) competing.firstOrNull()?.key else null
        val detectedVia = present.map { it.source }.distinct().let { sources ->
            when {
                sources.isEmpty() -> null
                sources.size == 1 -> sources.first()
                else -> SOURCE_MULTIPLE
            }
        }

        val message = if (configured) {
            null
        } else {
            "LLM is not configured, you can only use non-LLM commands. " +
                "X-SQL is still available. " +
                "It is highly recommended to set OPENROUTER_API_KEY or other LLM keys to enable LLM features."
        }

        return linkedMapOf(
            "configured" to configured,
            "detectedVia" to detectedVia,
            "foundEnvVars" to present.filter { it.source == SOURCE_ENV }.map { it.key },
            "foundProperties" to present.filter { it.source == SOURCE_SYSTEM_PROPERTY }.map { it.key },
            "configuredKeys" to present.map { mapOf("key" to it.key, "source" to it.source) },
            "emptyKeys" to emptyKeys,
            "selectedKey" to selectedKey,
            "selectedBy" to (explicitProvider?.let { "llm.provider=$it" } ?: "auto-detection"),
            "multipleProviders" to (competing.size > 1),
            "warning" to warning(competing, emptyKeys, explicitProvider),
            "activeModel" to if (configured) activeModel(conf) else null,
            "keyPrefixes" to listOf("OPENROUTER", "DEEPSEEK", "VOLCENGINE", "OPENAI"),
            "message" to message,
        )
    }

    /** A human-readable explanation of the routing that is about to happen, or `null`. */
    private fun warning(
        usable: List<ConfiguredKey>,
        emptyKeys: List<String>,
        explicitProvider: String?,
    ): String? {
        val issues = mutableListOf<String>()

        if (emptyKeys.isNotEmpty()) {
            issues += "These provider keys are set but empty, and are ignored: " +
                emptyKeys.joinToString(", ") + ". Remove the unused lines."
        }

        if (usable.size > 1 && explicitProvider == null) {
            issues += "Several LLM providers are configured: " + usable.joinToString(", ") { it.key } +
                ". The first key in the built-in priority list wins (" + usable.first().key +
                "), so set llm.provider to choose explicitly, add the unused provider to " +
                "llm.provider.deny.list, or remove its key."
        }

        return issues.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }
}
