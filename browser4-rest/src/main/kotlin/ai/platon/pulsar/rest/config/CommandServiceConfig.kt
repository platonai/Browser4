package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agent.tool.UserCommandExecutor
import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.rest.api.service.ConversationService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CommandServiceConfig {

    @Bean(destroyMethod = "close")
    fun sessionManager(agenticContext: AgenticContext): PulsarSessionManager {
        return PulsarSessionManager(agenticContext)
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
