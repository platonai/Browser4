package ai.platon.browser4.boot.plugin

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy
import java.nio.file.Path

/**
 * Auto-configuration that creates the [PluginManager] and [PluginService] beans.
 *
 * - [PluginManager] runs after the application context is fully refreshed
 *   and wires all discovered [ai.platon.pulsar.skeleton.plugin.PluginMount] beans
 *   into their appropriate integration points.
 * - [PluginService] provides runtime plugin management: list, info, install, remove.
 */
@AutoConfiguration
@Lazy
class PluginAutoConfiguration {

    @Bean(name = ["pluginManager"])
    fun pluginManager(applicationContext: ApplicationContext): PluginManager {
        return PluginManager(applicationContext)
    }

    @Bean(name = ["pluginService"])
    fun pluginService(applicationContext: ApplicationContext): PluginService {
        return PluginService(applicationContext, Path.of("plugins"))
    }
}
