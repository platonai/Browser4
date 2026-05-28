package ai.platon.pulsar.browser

import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.browser.BrowserFiles
import ai.platon.pulsar.driver.common.BrowserSettings
import ai.platon.pulsar.common.browser.BrowserProfileMode
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.browser.fingerprint.Fingerprint
import ai.platon.pulsar.common.browser.fingerprint.FingerprintLoader
import ai.platon.pulsar.common.config.CapabilityTypes.BROWSER_CONTEXT_NUMBER
import ai.platon.pulsar.common.config.CapabilityTypes.PRIVACY_AGENT_GENERATOR_CLASS
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.logging.ThrottlingLogger
import java.nio.file.Path

/**
 * A browser profile defines a unique agent to visit websites.
 *
 * Page visits through different browser profiles should not be detected
 * as the same person, even if the visits are from the same host.
 * */
data class BrowserProfile(
    val contextDir: Path,
    var fingerprint: Fingerprint
) : Comparable<BrowserProfile> {
    val id = ProfileId(contextDir, fingerprint.browserType)
    val ident get() = id.ident
    val display get() = id.display
    val browserType get() = fingerprint.browserType
    val isSystemDefault get() = id.isSystemDefault
    val isDefault get() = id.isDefault
    val isPrototype get() = id.isPrototype
    val isGroup get() = id.isGroup
    val isTemporary get() = id.isTemporary
    val isPermanent get() = id.isPermanent

    constructor(contextDir: Path) : this(contextDir, BrowserType.PULSAR_CHROME)

    constructor(contextDir: Path, browserType: BrowserType) : this(contextDir, Fingerprint(browserType))

    /**
     * The BrowserProfile equality.
     * Note: do not use the default equality function
     * */
    override fun equals(other: Any?) = other is BrowserProfile && other.id == this.id

    override fun hashCode() = id.hashCode()

    override fun compareTo(other: BrowserProfile) = id.compareTo(other.id)

//    override fun toString() = /** AUTO GENERATED **/

    companion object {
        private val logger = getLogger(this)
        private val throttlingLogger = ThrottlingLogger(logger)

        // The prefix for all temporary privacy contexts. System context, prototype context and default context are not
        // required to start with the prefix.
        const val CONTEXT_DIR_PREFIX = "cx."

        // NOTE: Chrome DevTools remote debugging requires a non-default data directory. Specify this using --user-data-dir.
        val SYSTEM_DEFAULT_BROWSER_CONTEXT_DIR_PLACEHOLDER: Path = AppPaths.SYSTEM_DEFAULT_BROWSER_CONTEXT_DIR_PLACEHOLDER

        // The default context directory, if you need a permanent and isolate context, use this one.
        // NOTE: the user-default context is not a default context.
        val DEFAULT_CONTEXT_DIR: Path = AppPaths.CONTEXT_DEFAULT_DIR

        // The prototype context directory, all privacy contexts copies browser data from the prototype.
        // A typical prototype data dir is: ~/.browser4/browser/chrome/prototype/google-chrome/
        val PROTOTYPE_DATA_DIR: Path = AppPaths.CHROME_DATA_DIR_PROTOTYPE
        // A context dir is the dir which contains the browser data dir, and supports different browsers.
        // For example: ~/.browser4/browser/chrome/prototype/
        val PROTOTYPE_CONTEXT_DIR: Path = AppPaths.CHROME_DATA_DIR_PROTOTYPE.parent

        // A random context directory, if you need a random temporary context, use this one
        val NEXT_SEQUENTIAL_CONTEXT_DIR get() = BrowserFiles.computeNextSequentialContextDir()
        // A random context directory, if you need a random temporary context, use this one
        val RANDOM_TEMP_CONTEXT_DIR get() = BrowserFiles.computeRandomTmpContextDir(browserType = BrowserType.PULSAR_CHROME)

        /**
         * The random browser profile opens browser with a random data dir.
         * */
        val RANDOM_TEMP get() = createRandomTemp()

        fun create(contextDir: Path) = create(BrowserType.PULSAR_CHROME, contextDir)

        fun create(browserType: BrowserType, contextDir: Path): BrowserProfile {
            val fingerprint: Fingerprint = FingerprintLoader(browserType, contextDir).loadOrGenerateFingerprint()
            return BrowserProfile(contextDir, fingerprint)
        }

        fun createSystemDefault(): BrowserProfile {
            return createSystemDefault(BrowserType.DEFAULT)
        }

        fun createSystemDefault(browserType: BrowserType): BrowserProfile {
            throttlingLogger.info("You are creating a SYSTEM_DEFAULT browser context, force set max browser number to be 1")
            throttlingLogger.info("Chrome DevTools remote debugging requires a non-default data directory. Specify this using --user-data-dir.")

            BrowserSettings.withBrowserContextMode(BrowserProfileMode.SYSTEM_DEFAULT, browserType)
            require(System.getProperty(BROWSER_CONTEXT_NUMBER).toIntOrNull() == 1)
            require(System.getProperty(PRIVACY_AGENT_GENERATOR_CLASS).contains("SystemDefaultBrowserProfileGenerator"))
            return create(browserType, SYSTEM_DEFAULT_BROWSER_CONTEXT_DIR_PLACEHOLDER)
        }

        fun createDefault(): BrowserProfile {
            return createDefault(BrowserType.DEFAULT)
        }

        fun createDefault(browserType: BrowserType): BrowserProfile {
            throttlingLogger.info("You are creating a DEFAULT browser context, force set max browser number to 1")

            BrowserSettings.withBrowserContextMode(BrowserProfileMode.DEFAULT, browserType)
            require(System.getProperty(BROWSER_CONTEXT_NUMBER).toIntOrNull() == 1)
            require(System.getProperty(PRIVACY_AGENT_GENERATOR_CLASS).contains("DefaultBrowserProfileGenerator"))
            return create(browserType, DEFAULT_CONTEXT_DIR)
        }

        fun createPrototype(): BrowserProfile {
            return createPrototype(BrowserType.DEFAULT)
        }

        fun createPrototype(browserType: BrowserType): BrowserProfile {
            throttlingLogger.info("You are creating a PROTOTYPE browser context, force set max browser number to be 1")

            BrowserSettings.withBrowserContextMode(BrowserProfileMode.PROTOTYPE, browserType)
            require(System.getProperty(BROWSER_CONTEXT_NUMBER).toIntOrNull() == 1)
            require(System.getProperty(PRIVACY_AGENT_GENERATOR_CLASS).contains("PrototypeBrowserProfileGenerator"))
            return create(browserType, PROTOTYPE_CONTEXT_DIR)
        }

        fun createNextSequential() = createNextSequential(BrowserType.PULSAR_CHROME)

        fun createNextSequential(browserType: BrowserType): BrowserProfile {
            BrowserSettings.withBrowserContextMode(BrowserProfileMode.SEQUENTIAL, browserType)
            return create(browserType, NEXT_SEQUENTIAL_CONTEXT_DIR)
        }

        fun createRandomTemp() = createRandomTemp(BrowserType.PULSAR_CHROME)

        fun createRandomTemp(browserType: BrowserType): BrowserProfile {
            BrowserSettings.withBrowserContextMode(BrowserProfileMode.TEMPORARY, browserType)
            return create(browserType, createRandom(browserType))
        }
    }
}
