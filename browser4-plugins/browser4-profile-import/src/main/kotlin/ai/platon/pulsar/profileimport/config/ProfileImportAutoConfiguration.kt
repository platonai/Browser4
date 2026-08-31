package ai.platon.pulsar.profileimport.config

import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.profileimport.service.ProfileCopier
import ai.platon.pulsar.profileimport.service.ProfileImportService
import ai.platon.pulsar.profileimport.service.SafariDataReader
import ai.platon.pulsar.profileimport.service.SourceBrowserDetector
import ai.platon.pulsar.profileimport.tools.ProfileImportToolExecutor
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy
import org.springframework.context.event.EventListener
import java.nio.file.Path

/**
 * Auto-configuration for the browser4-profile-import plugin.
 *
 * Creates the [ProfileImportService] (source discovery + whole-profile copy +
 * Safari conversions) and mounts it through [ToolMount] so `PluginManager`
 * registers the `profile_import` tools for MCP/LLM agents.
 *
 * Disable with `profileimport.enabled=false`.
 */
@AutoConfiguration
@ConditionalOnProperty(name = ["profileimport.enabled"], havingValue = "true", matchIfMissing = true)
@Lazy
open class ProfileImportAutoConfiguration(
    private val applicationContext: ApplicationContext,
) : ToolMount {

    @Bean(name = ["sourceBrowserDetector"])
    @ConditionalOnMissingBean(name = ["sourceBrowserDetector"])
    open fun sourceBrowserDetector(): SourceBrowserDetector = SourceBrowserDetector()

    @Bean(name = ["profileCopier"])
    @ConditionalOnMissingBean(name = ["profileCopier"])
    open fun profileCopier(): ProfileCopier = ProfileCopier()

    @Bean(name = ["safariDataReader"])
    @ConditionalOnMissingBean(name = ["safariDataReader"])
    open fun safariDataReader(): SafariDataReader = SafariDataReader()

    @Bean(name = ["profileImportService"])
    @ConditionalOnMissingBean(name = ["profileImportService"])
    open fun profileImportService(
        sourceBrowserDetector: SourceBrowserDetector,
        profileCopier: ProfileCopier,
        safariDataReader: SafariDataReader,
    ): ProfileImportService {
        // Import snapshots land in <browser4.data.dir or home>/.browser4/imports,
        // overridable with profileimport.import.dir.
        val defaultRoot = Path.of(
            System.getProperty("browser4.data.dir", System.getProperty("user.home")),
            ".browser4", "imports"
        )
        val root = System.getProperty("profileimport.import.dir")?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it) } ?: defaultRoot
        val allowPasswords = System.getProperty("profileimport.allow.passwords", "false").toBoolean()
        return ProfileImportService(
            detector = sourceBrowserDetector,
            copier = profileCopier,
            safariReader = safariDataReader,
            importRoot = root,
            allowPasswords = allowPasswords,
        )
    }

    @Bean(name = ["profileImportToolExecutor"])
    @ConditionalOnMissingBean(name = ["profileImportToolExecutor"])
    open fun profileImportToolExecutor(profileImportService: ProfileImportService): ProfileImportToolExecutor {
        return ProfileImportToolExecutor(profileImportService)
    }

    override fun getToolExecutors(): List<ToolExecutor> {
        return try {
            listOf(applicationContext.getBean("profileImportToolExecutor") as ToolExecutor)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Bind the import service as the agent-side target for the
     * `profile_import` domain, so in-session LLM agents (RobustBrowserAgent)
     * can call the tools too. The MCP/CLI path does not need this — it passes
     * a neutral receiver — so a missing AgentToolManager bean is non-fatal.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun registerAgentToolTarget() {
        try {
            val toolManager = applicationContext.getBean(AgentToolManager::class.java)
            val service = applicationContext.getBean("profileImportService")
            if (!toolManager.hasCustomTarget("profile_import")) {
                toolManager.registerCustomTarget("profile_import", service)
            }
        } catch (e: Exception) {
            logger.warn("Could not register profile_import agent tool target: {}", e.message)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ProfileImportAutoConfiguration::class.java)
    }
}
