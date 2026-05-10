package ai.platon.pulsar.skeleton.workflow.fetch.privacy

import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.browser.fingerprint.Fingerprint
import ai.platon.pulsar.common.proxy.ProxyEntry
import java.nio.file.Path

/**
 * The unique browser id.
 *
 * Every browser instance have a unique fingerprint and a context directory.
 *
 * The fingerprint is used to identify the browser, and the context directory is used to store the browser's data.
 *
 * @property contextDir The directory to store the browser's data.
 * @property fingerprint The fingerprint to identify the browser.
 * */
data class BrowserId(
    val contextDir: Path,
    val fingerprint: Fingerprint
): Comparable<BrowserId> {
    /**
     * The browser type of the browser.
     * */
    val browserType: BrowserType get() = fingerprint.browserType
    /**
     * The browser profile of the browser.
     * */
    val profile = BrowserProfile(contextDir, fingerprint)
    /**
     * The creation time of the browser.
     * */
    val createTime = System.currentTimeMillis()
    /**
     * The user data directory of the browser.
     * */
    val userDataDir: Path
        get() = when {
            profile.isSystemDefault -> AppPaths.SYSTEM_DEFAULT_BROWSER_DATA_DIR_PLACEHOLDER
            profile.isPrototype -> PrivacyContext.PROTOTYPE_DATA_DIR
            else -> contextDir.resolve(browserType.name)
        }
    /**
     * A human-readable short display of the context.
     * For example,
     * 1. prototype
     * 2. 07171ChsOE207
     * */
    val display get() = contextDir.last().toString().substringAfter(PrivacyContext.CONTEXT_DIR_PREFIX)
    /**
     * The constructor of the browser id.
     *
     * @param profile The browser profile of the browser.
     * */
    constructor(profile: BrowserProfile): this(profile.contextDir, profile.fingerprint)
    /**
     * The constructor of the browser id.
     *
     * @param contextDir The context directory of the browser.
     * @param browserType The browser type of the browser.
     * */
    constructor(contextDir: Path, browserType: BrowserType): this(contextDir, Fingerprint(browserType))

    fun hasProxy() = fingerprint.hasProxy()
    fun setProxy(schema: String, hostPort: String, username: String?, password: String?) {
        fingerprint.setProxy(schema, hostPort, username, password)
    }
    fun setProxy(proxy: ProxyEntry) = fingerprint.setProxy(proxy)

    fun unsetProxy() = fingerprint.unsetProxy()

    override fun equals(other: Any?): Boolean {
        return other is BrowserId && other.profile == profile
    }

    override fun hashCode() = profile.hashCode()

    override fun compareTo(other: BrowserId) = profile.compareTo(other.profile)

    override fun toString(): String {
        return "{$fingerprint, $contextDir}"
    }

    companion object {
        /**
         * Represent the real user's default browser.
         * */
        val SYSTEM_DEFAULT get() = createSystemDefault()
        /**
         * Represent the default browser.
         * */
        val DEFAULT get() = createDefault()
        /**
         * Represent the prototype browser.
         * */
        val PROTOTYPE get() = createPrototype()
        /**
         * Represent a browser with a sequential context dir.
         * */
        val NEXT_SEQUENTIAL get() = createNextSequential()
        /**
         * Create a browser with random context dir.
         * */
        val RANDOM_TEMP get() = createRandomTemp()

        fun createDefault() = BrowserId(BrowserProfile.createDefault())

        fun createDefault(browserType: BrowserType) = BrowserId(BrowserProfile.createDefault(browserType))

        fun createSystemDefault() = BrowserId(BrowserProfile.createSystemDefault())

        fun createSystemDefault(browserType: BrowserType) = BrowserId(BrowserProfile.createSystemDefault(browserType))

        fun createPrototype() = BrowserId(BrowserProfile.createPrototype())

        fun createPrototype(browserType: BrowserType) = BrowserId(BrowserProfile.createPrototype(browserType))

        fun createRandomTemp() = BrowserId(BrowserProfile.createRandomTemp())

        fun createRandomTemp(browserType: BrowserType) = BrowserId(BrowserProfile.createRandomTemp(browserType))

        fun createNextSequential() = BrowserId(BrowserProfile.createNextSequential())

        fun createNextSequential(browserType: BrowserType) = BrowserId(BrowserProfile.createNextSequential(browserType))
    }
}
