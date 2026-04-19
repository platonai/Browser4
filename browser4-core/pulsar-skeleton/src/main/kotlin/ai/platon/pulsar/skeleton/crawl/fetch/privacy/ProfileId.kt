package ai.platon.pulsar.skeleton.crawl.fetch.privacy

import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.browser.BrowserType
import java.nio.file.Path

data class ProfileId(
    val contextDir: Path,
    val browserType: BrowserType
): Comparable<ProfileId> {

    val ident = contextDir.last().toString()

    val display = when {
        isSystemDefault -> "system.default"
        isDefault -> "default"
        isPrototype -> "prototype"
        ident.length <= 5 -> ident
        else -> ident.substringAfter(PrivacyContext.CONTEXT_DIR_PREFIX)
    }
    /**
     * If true, the browser profile opens browser just like a real user does every day.
     * */
    val isSystemDefault get() = this.contextDir == AppPaths.SYSTEM_DEFAULT_BROWSER_CONTEXT_DIR_PLACEHOLDER
    /**
     * If true, the browser profile opens browser with the default data dir, the default data dir will not be removed
     * after the browser closes.
     * */
    val isDefault get() = this.contextDir == PrivacyContext.DEFAULT_CONTEXT_DIR
    /**
     * If true, the browser profile opens browser with the prototype data dir.
     * Every change to the browser will be kept in the prototype data dir, and every temporary browser profile
     * uses a copy of the prototype data dir.
     * */
    val isPrototype get() = this.contextDir == PrivacyContext.PROTOTYPE_CONTEXT_DIR
    /**
     * If true, the browser profile opens browser with one of a set of pre-created data dirs, the pre-created data dirs will
     * not be removed after the browser closes.
     * */
    val isGroup get() = this.contextDir.startsWith(AppPaths.CONTEXT_GROUP_BASE_DIR)
    /**
     * Check if this browser is permanent.
     *
     * If a browser is temporary:
     * - it will be closed when the browser is idle
     * - the user data will be deleted after the browser is closed
     * */
    val isTemporary get() = this.contextDir.startsWith(AppPaths.CONTEXT_TMP_DIR)
    /**
     * Check if this browser is permanent.
     *
     * If a browser is permanent:
     * - it will not be closed when the browser is idle
     * - the user data will be kept after the browser is closed
     * */
    val isPermanent get() = isSystemDefault || isDefault || isPrototype

    /**
     * The PrivacyAgent equality.
     * Note: do not use the default equality function
     * */
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        return other is ProfileId
                && other.contextDir == contextDir
                && other.browserType.name == browserType.name
    }

    override fun hashCode(): Int {
        return 31 * contextDir.hashCode() + browserType.name.hashCode()
    }

    override fun compareTo(other: ProfileId): Int {
        val b = contextDir.compareTo(other.contextDir)
        if (b != 0) {
            return b
        }

        return browserType.name.compareTo(other.browserType.name)
    }
}

