package ai.platon.pulsar.my.config

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy
import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.my.service.WordcountService
import ai.platon.pulsar.my.tools.WordcountToolExecutor

@AutoConfiguration
@ConditionalOnProperty(name = ["wordcount.enabled"], havingValue = "true", matchIfMissing = true)
@Lazy
open class WordcountAutoConfiguration(private val applicationContext: ApplicationContext) : ToolMount {
    override fun getToolExecutors(): List<ToolExecutor> = try {
        listOf(applicationContext.getBean("wordcountToolExecutor") as ToolExecutor)
    } catch (e: Exception) {
        emptyList()
    }

    @Bean(name = ["wordcountConfig"])
    @ConditionalOnMissingBean(name = ["wordcountConfig"])
    open fun wordcountConfig(config: MutableConfig) = WordcountConfig.fromConfig(config)

    @Bean(name = ["wordcountService"])
    @ConditionalOnMissingBean(name = ["wordcountService"])
    open fun wordcountService() = WordcountService()

    @Bean(name = ["wordcountToolExecutor"])
    @ConditionalOnMissingBean(name = ["wordcountToolExecutor"])
    open fun wordcountToolExecutor(service: WordcountService) = WordcountToolExecutor(service)
}
