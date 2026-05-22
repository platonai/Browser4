package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.tools.high.command.CommandNormalizer
import ai.platon.pulsar.agentic.tools.high.command.CommandService
import ai.platon.pulsar.rest.api.service.ConversationService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CommandServiceConfig {

    @Bean
    fun commandNormalizer(conversationService: ConversationService): CommandNormalizer {
        return CommandNormalizer { plainCommand -> conversationService.normalizePlainCommand(plainCommand) }
    }

    @Bean(destroyMethod = "close")
    fun commandService(session: AgenticSession, commandNormalizer: CommandNormalizer): CommandService {
        return CommandService(session, commandNormalizer)
    }
}
