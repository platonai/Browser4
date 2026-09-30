package ai.platon.pulsar.boot.skill

import ai.platon.pulsar.agentic.common.AgentPaths
import ai.platon.pulsar.agentic.skills.SkillBootstrap
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy

/**
 * Auto-configuration that creates the [SkillService] bean.
 *
 * [SkillService] provides runtime skill management: list, info, install, uninstall, reload.
 *
 * [SkillBootstrap] is registered explicitly: it lives in the `ai.platon.pulsar.agentic.skills`
 * package, which is outside the component-scan base packages of the applications, and it must
 * be eagerly initialized at startup (with `@Lazy(false)`) — applications such as the standalone
 * run with `spring.main.lazy-initialization=true`, which would otherwise defer (and effectively
 * skip) the startup skill load because nothing injects the bootstrap bean.
 */
@AutoConfiguration
class SkillAutoConfiguration {

    @Bean(name = ["skillService"])
    @Lazy
    fun skillService(applicationContext: ApplicationContext): SkillService {
        return SkillService(applicationContext, AgentPaths.SKILLS_DIR)
    }

    @Bean(name = ["skillBootstrap"])
    @Lazy(false)
    fun skillBootstrap(): SkillBootstrap {
        return SkillBootstrap()
    }
}
