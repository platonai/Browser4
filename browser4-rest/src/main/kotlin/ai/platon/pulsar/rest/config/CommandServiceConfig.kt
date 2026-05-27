package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.common.PulsarSessionManager
import ai.platon.pulsar.rest.api.service.ConversationService
import ai.platon.pulsar.agent.tool.UserCommandExecutor
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CommandServiceConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnBean(AgenticContext::class)
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
