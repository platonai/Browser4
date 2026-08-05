package ai.platon.pulsar.browser.privacy

import ai.platon.pulsar.api.model.BrowserSettings
import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.config.ImmutableConfig
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

class BrowserProfileGeneratorFactory(val conf: ImmutableConfig) {
    companion object {

        val BROWSER_CONTEXT_MODE_TO_AGENTS = mapOf(
            BrowserProfileMode.PROTOTYPE to PrototypeBrowserProfileGenerator::class,
            BrowserProfileMode.SEQUENTIAL to SequentialBrowserProfileGenerator::class,
            BrowserProfileMode.TEMPORARY to RandomBrowserProfileGenerator::class,
            BrowserProfileMode.SYSTEM_DEFAULT to SystemDefaultBrowserProfileGenerator::class,
            BrowserProfileMode.DEFAULT to DefaultBrowserProfileGenerator::class
        )

        fun getBrowserProfileKClass(mode: BrowserProfileMode): KClass<out BrowserProfileGenerator> {
            return when (mode) {
                BrowserProfileMode.PROTOTYPE -> PrototypeBrowserProfileGenerator::class
                BrowserProfileMode.SEQUENTIAL -> SequentialBrowserProfileGenerator::class
                BrowserProfileMode.TEMPORARY -> RandomBrowserProfileGenerator::class
                BrowserProfileMode.SYSTEM_DEFAULT -> SystemDefaultBrowserProfileGenerator::class
                else -> DefaultBrowserProfileGenerator::class
            }
        }

        fun getBrowserProfile(mode: BrowserProfileMode, conf: ImmutableConfig): BrowserProfileGenerator {
            return when (mode) {
                BrowserProfileMode.PROTOTYPE -> PrototypeBrowserProfileGenerator()
                BrowserProfileMode.SEQUENTIAL -> SequentialBrowserProfileGenerator()
                BrowserProfileMode.TEMPORARY -> RandomBrowserProfileGenerator()
                BrowserProfileMode.SYSTEM_DEFAULT -> SystemDefaultBrowserProfileGenerator()
                else -> DefaultBrowserProfileGenerator()
            }.also { it.conf = conf }
        }
    }

    private val generators = ConcurrentHashMap<String, BrowserProfileGenerator>()

    val generator: BrowserProfileGenerator
        get() {
            BrowserSettings.overrideBrowserContextMode(conf)

            val profileMode = BrowserProfileMode.fromString(conf[CapabilityTypes.BROWSER_PROFILE_MODE])
            val name = getBrowserProfileKClass(profileMode).java.name
            return generators.computeIfAbsent(name) { getBrowserProfile(profileMode, conf) }
        }
}
