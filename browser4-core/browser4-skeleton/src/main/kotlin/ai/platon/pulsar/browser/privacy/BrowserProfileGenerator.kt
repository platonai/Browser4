package ai.platon.pulsar.browser.privacy

import ai.platon.pulsar.api.BrowserProfile
import ai.platon.pulsar.common.browser.BrowserFiles
import ai.platon.pulsar.common.browser.fingerprint.Fingerprint
import ai.platon.pulsar.common.config.CapabilityTypes.*
import ai.platon.pulsar.common.config.ImmutableConfig
import java.io.IOException
import java.nio.file.Files

interface BrowserProfileGenerator {
    var conf: ImmutableConfig

    @Throws(Exception::class)
    operator fun invoke(fingerprint: Fingerprint): BrowserProfile
}

open class DefaultBrowserProfileGenerator : BrowserProfileGenerator {
    override var conf: ImmutableConfig = ImmutableConfig()

    @Throws(Exception::class)
    override fun invoke(fingerprint: Fingerprint): BrowserProfile =
        BrowserProfile.createDefault(fingerprint.browserType)
}

open class SystemDefaultBrowserProfileGenerator : BrowserProfileGenerator {
    override var conf: ImmutableConfig = ImmutableConfig()

    @Throws(Exception::class)
    override fun invoke(fingerprint: Fingerprint) = BrowserProfile.createSystemDefault(fingerprint.browserType)
}

open class PrototypeBrowserProfileGenerator : BrowserProfileGenerator {
    override var conf: ImmutableConfig = ImmutableConfig()

    @Throws(Exception::class)
    override fun invoke(fingerprint: Fingerprint) = BrowserProfile.createDefault(fingerprint.browserType)
}

open class SequentialBrowserProfileGenerator(
    var group: String = "default"
) : BrowserProfileGenerator {
    // should be late initialized
    override var conf: ImmutableConfig = ImmutableConfig()

    private fun computeMaxProfileCount(): Int {
        // The number of allowed active privacy contexts

        // PRIVACY_CONTEXT_NUMBER is deprecated, use BROWSER_CONTEXT_NUMBER instead
//        val fallbackValue = conf.getInt(PRIVACY_CONTEXT_NUMBER, 2)
//        val browserContextNumber = conf.getInt(BROWSER_CONTEXT_NUMBER, fallbackValue)
        val browserContextNumber =
            conf.getWithFallback(BROWSER_CONTEXT_NUMBER, PRIVACY_CONTEXT_NUMBER)?.toIntOrNull() ?: 2

        // The minimum number of sequential browser profiles, the active privacy contexts is chosen from them
        val minProfiles = conf.getInt(MIN_SEQUENTIAL_BROWSER_PROFILE_NUMBER, 10)
        // The maximum number of sequential browser profiles, the active privacy contexts is chosen from them
        var maxProfiles = conf.getInt(MAX_SEQUENTIAL_PRIVACY_AGENT_NUMBER, minProfiles)
        maxProfiles = maxProfiles.coerceAtLeast(browserContextNumber).coerceAtLeast(minProfiles)

        return maxProfiles
    }

    @Throws(IOException::class)
    override fun invoke(fingerprint: Fingerprint): BrowserProfile {
        // The number of allowed active privacy contexts
        val maxProfiles = computeMaxProfileCount()

        val contextDir = BrowserFiles.computeNextSequentialContextDir(group, fingerprint, maxProfiles)
        // logger.info("Use sequential browser profile | $contextDir")

        require(Files.exists(contextDir)) { "The context dir does not exist: $contextDir" }

        val profile = BrowserProfile(contextDir, fingerprint)

        return profile
    }
}

/**
 * The random browser profile generator.
 *
 * If the prototype Chrome browser does not exist, it acts as "New Incognito window", or in Chinese, "打开无痕浏览器".
 * If the prototype Chrome browser exists, it copies the prototype Chrome browser's user data directory, and inherits
 * the prototype Chrome browser's settings.
 * */
open class RandomBrowserProfileGenerator : BrowserProfileGenerator {
    override var conf: ImmutableConfig = ImmutableConfig.DEFAULT

    @Throws(IOException::class)
    override fun invoke(fingerprint: Fingerprint): BrowserProfile =
        BrowserProfile(BrowserFiles.computeRandomTmpContextDir(), fingerprint)
}
