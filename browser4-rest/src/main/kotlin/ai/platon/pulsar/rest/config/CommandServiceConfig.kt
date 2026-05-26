package ai.platon.pulsar.rest.config

import ai.platon.pulsar.rest.api.service.ConversationService
import ai.platon.pulsar.rest.api.service.SessionManager
import ai.platon.pulsar.rest.tool.CommandRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CommandServiceConfig {

    @Bean
    fun commandNormalizer(conversationService: ConversationService): CommandNormalizer {
        return CommandNormalizer { plainCommand -> conversationService.normalizePlainCommand(plainCommand) }
    }

    @Bean(destroyMethod = "close")
    fun commandService(sessionManager: SessionManager, commandNormalizer: CommandNormalizer): CommandRunner {
        return CommandRunner(sessionManager, commandNormalizer)
    }
}
