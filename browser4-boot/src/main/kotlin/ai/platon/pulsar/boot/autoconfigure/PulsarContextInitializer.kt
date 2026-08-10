package ai.platon.pulsar.boot.autoconfigure

import ai.platon.pulsar.common.Systems
import ai.platon.pulsar.external.ApiProtocol
import ai.platon.pulsar.external.ChatModelFactory
import ai.platon.pulsar.external.ProviderConfig
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.support.AbstractApplicationContext

class PulsarContextInitializer : ApplicationContextInitializer<AbstractApplicationContext> {
    override fun initialize(applicationContext: AbstractApplicationContext) {
        Systems.setPropertyIfAbsent("app.name", "browser4")
        registerOrcaRouterProvider()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PulsarContextInitializer::class.java)

        /**
         * Register OrcaRouter (https://www.orcarouter.ai) as a named OpenAI-compatible
         * LLM provider.
         *
         * OrcaRouter is an OpenAI-compatible routing gateway: a single ORCAROUTER_API_KEY
         * reaches many models (including free models via the `orcarouter/auto` smart-routing
         * alias) behind one endpoint, https://api.orcarouter.ai/v1. Registering it here makes
         * [ChatModelFactory] recognize ORCAROUTER_API_KEY just like OPENROUTER_API_KEY, so
         * Browser4's agentic layer can route LLM requests through OrcaRouter with no further
         * changes. The registration is safe (and a no-op) when no key is set.
         */
        fun registerOrcaRouterProvider() {
            runCatching {
                ChatModelFactory.registerProvider(
                    ProviderConfig(
                        apiKeyName = "ORCAROUTER_API_KEY",
                        modelNameKey = "ORCAROUTER_MODEL_NAME",
                        baseUrlKey = "ORCAROUTER_BASE_URL",
                        defaultModel = "orcarouter/auto",
                        defaultBaseUrl = "https://api.orcarouter.ai/v1",
                        providerName = "orcarouter",
                    )
                )
            }.onFailure { e ->
                logger.warn("Failed to register OrcaRouter provider (non-fatal): {}", e.message)
            }
        }
    }
}
