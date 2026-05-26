package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.common.SessionManager
import ai.platon.pulsar.rest.api.service.ConversationService
import ai.platon.pulsar.rest.tool.CommandRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CommandServiceConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnBean(AgenticContext::class)
    fun sessionManager(agenticContext: AgenticContext): SessionManager {
        return SessionManager(agenticContext)
    }

    @Bean
    fun commandNormalizer(conversationService: ConversationService): CommandNormalizer {
        return CommandNormalizer { plainCommand -> conversationService.normalizePlainCommand(plainCommand) }
    }

    @Bean(destroyMethod = "close")
    fun commandService(sessionManager: SessionManager, commandNormalizer: CommandNormalizer): CommandRunner {
        return CommandRunner(sessionManager, commandNormalizer)
    }
}
