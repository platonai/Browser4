package ai.platon.browser4.boot.skill

import ai.platon.pulsar.agentic.common.AgentPaths
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy

/**
 * Auto-configuration that creates the [SkillService] bean.
 *
 * [SkillService] provides runtime skill management: list, info, install, uninstall, reload.
 */
@AutoConfiguration
@Lazy
class SkillAutoConfiguration {

    @Bean(name = ["skillService"])
    fun skillService(applicationContext: ApplicationContext): SkillService {
        return SkillService(applicationContext, AgentPaths.SKILLS_DIR)
    }
}
