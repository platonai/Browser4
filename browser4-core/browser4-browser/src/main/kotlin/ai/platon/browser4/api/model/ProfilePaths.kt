package ai.platon.browser4.api.model

import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.browser.BrowserFiles
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.browser.fingerprint.Fingerprint
import java.nio.file.Path

object ProfilePaths {
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
    val NEXT_SEQUENTIAL_CONTEXT_DIR: Path get() = BrowserFiles.computeNextSequentialContextDir()
    // A random context directory, if you need a random temporary context, use this one
    val RANDOM_TEMP_CONTEXT_DIR: Path get() = BrowserFiles.computeRandomTmpContextDir(browserType = BrowserType.PULSAR_CHROME)

    fun createNextSequential(fingerprint: Fingerprint): Path {
        return BrowserFiles.computeNextSequentialContextDir(fingerprint = fingerprint)
    }

    fun createRandom(browserType: BrowserType): Path {
        return BrowserFiles.computeRandomTmpContextDir(browserType = browserType)
    }
}
