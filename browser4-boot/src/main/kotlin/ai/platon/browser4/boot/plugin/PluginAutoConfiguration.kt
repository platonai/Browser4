package ai.platon.browser4.boot.plugin

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy

/**
 * Auto-configuration that creates the [PluginManager] bean.
 *
 * The PluginManager runs after the application context is fully refreshed
 * and wires all discovered [ai.platon.pulsar.skeleton.plugin.PluginMount] beans
 * into their appropriate integration points.
 */
@AutoConfiguration
@Lazy
class PluginAutoConfiguration {

    @Bean(name = ["pluginManager"])
    fun pluginManager(applicationContext: ApplicationContext): PluginManager {
        return PluginManager(applicationContext)
    }
}
