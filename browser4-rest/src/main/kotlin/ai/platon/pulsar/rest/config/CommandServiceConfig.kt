package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agent.tool.UserCommandExecutor
import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.rest.api.service.ConversationService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CommandServiceConfig {

    @Bean(destroyMethod = "close")
    fun sessionManager(agenticContext: AgenticContext): PulsarSessionManager {
        // Persist the display-name → session-id mapping so named sessions
        // keep their identity across backend restarts.  Lives in the app data
        // root: `~/.browser4/session-registry.json` for a production install,
        // `<workspace state>/app-data/session-registry.json` inside a checkout.
        // The registry used to be pinned to `~/.browser4` unconditionally, so
        // every development backend resolved `DEFAULT` (and every name) to the
        // same session UUID — the second half of the cross-checkout sharing
        // that let one backend drive another one's browser.
        val registryFile = AppPaths.DATA_DIR.resolve("session-registry.json")
        return PulsarSessionManager(agenticContext, registryFile = registryFile)
    }

    @Bean
    fun commandNormalizer(conversationService: ConversationService): CommandNormalizer {
        return CommandNormalizer { plainCommand -> conversationService.normalizePlainCommand(plainCommand) }
    }

    @Bean(destroyMethod = "close")
    fun commandService(sessionManager: PulsarSessionManager, commandNormalizer: CommandNormalizer): UserCommandExecutor {
        return UserCommandExecutor(sessionManager, commandNormalizer)
    }
}
