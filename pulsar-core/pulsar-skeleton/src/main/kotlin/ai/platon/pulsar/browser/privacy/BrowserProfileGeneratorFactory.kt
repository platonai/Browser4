package ai.platon.pulsar.browser.privacy

import ai.platon.pulsar.browser.common.BrowserSettings
import ai.platon.pulsar.common.SParser
import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.config.ImmutableConfig
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class BrowserProfileGeneratorFactory(val conf: ImmutableConfig) {
    companion object {
        private val generators = ConcurrentHashMap<String, BrowserProfileGenerator>()

        val BROWSER_CONTEXT_MODE_TO_AGENTS = mapOf(
            BrowserProfileMode.PROTOTYPE  to PrototypeBrowserProfileGenerator::class,
            BrowserProfileMode.SEQUENTIAL to SequentialBrowserProfileGenerator::class,
            BrowserProfileMode.TEMPORARY  to RandomBrowserProfileGenerator::class,
            BrowserProfileMode.SYSTEM_DEFAULT to SystemDefaultBrowserProfileGenerator::class,
            BrowserProfileMode.DEFAULT to DefaultBrowserProfileGenerator::class
        )

        fun getBrowserProfileGeneratorClass(mode: BrowserProfileMode): KClass<out BrowserProfileGenerator> {
            return when (mode) {
                BrowserProfileMode.PROTOTYPE -> PrototypeBrowserProfileGenerator::class
                BrowserProfileMode.SEQUENTIAL -> SequentialBrowserProfileGenerator::class
                BrowserProfileMode.TEMPORARY -> RandomBrowserProfileGenerator::class
                BrowserProfileMode.SYSTEM_DEFAULT -> SystemDefaultBrowserProfileGenerator::class
                else -> DefaultBrowserProfileGenerator::class
            }
        }
    }

    private val logger = LoggerFactory.getLogger(BrowserProfileGeneratorFactory::class.java)

    val generator: BrowserProfileGenerator
        get() {
            BrowserSettings.overrideBrowserContextMode(conf)

            // When the generator class is set, use it
            val className = conf[CapabilityTypes.PRIVACY_AGENT_GENERATOR_CLASS] ?: DefaultBrowserProfileGenerator::class.java.name
            return getOrCreate(className)
        }

    private fun getOrCreate(className: String): BrowserProfileGenerator {
        synchronized(generators) {
            return getOrCreate0(className)
        }
    }

    private fun getOrCreate0(className: String): BrowserProfileGenerator {
        var gen = generators[className]
        if (gen != null) {
            return gen
        }

        gen = forName(conf, className)

        generators[gen::class.java.name] = gen
        generators[className] = gen

        logger.info("Created browser profile generator {} | {}", gen::class.java.simpleName, gen::class.java.name)

        return gen
    }

    /**
     * Get the value of the `name` property as a `Class`.
     * If the property is not set, or the class is not found, use the default class.
     * The default class is `DefaultPageEvent`.
     *
     * Set the class:
     * `System.setProperty(CapabilityTypes.PRIVACY_AGENT_GENERATOR_CLASS, "ai.platon.pulsar.browser.privacy.DefaultBrowserProfileGenerator")`
     * */
    private fun forName(conf: ImmutableConfig, className: String): BrowserProfileGenerator {
        val defaultClazz = DefaultBrowserProfileGenerator::class.java
        val clazz = try {
            SParser(className).getClass(defaultClazz)
        } catch (e: Exception) {
            logger.warn(
                "No configured browser profile generator {}, use default ({})",
                className, defaultClazz.simpleName
            )
            defaultClazz
        }

        val gen = clazz.constructors.first { it.parameters.isEmpty() }.newInstance() as BrowserProfileGenerator
        gen.conf = conf
        return gen
    }
}
