/**
 * Copyright (c) Vincent Zhang, ivincent.zhang@gmail.com, Platon.AI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.skeleton.workflow.fetch.privacy

import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.browser.BrowserFiles
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.browser.fingerprint.Fingerprint
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.workflow.fetch.FetchResult
import ai.platon.pulsar.skeleton.workflow.fetch.FetchTask
import ai.platon.pulsar.skeleton.workflow.fetch.driver.WebDriver
import java.nio.file.Path
import java.time.Duration

/**
 * A privacy context is a unique context of a browser profile to the target website,
 * it will be closed once it is leaked.
 *
 * One of the biggest difficulties in web scraping tasks is the bot stealth.
 *
 * For web scraping tasks, the website should have no idea whether a visit is
 * from a human being or a bot. Once a page visit is suspected by the website,
 * which we call a privacy leak, the privacy context has to be dropped,
 * and Browser4 will visit the page in another privacy context.
 * */
interface PrivacyContext: AutoCloseable {
    /**
     * The privacy context id.
     * */
    val id: ProfileId
    /**
     * The associated browser profile.
     * */
    val profile: BrowserProfile
    /**
     * Check whether the privacy context is at full capacity. If the privacy context is
     * indeed at full capacity, it should not be used for processing new tasks,
     * and the underlying services may potentially refuse to provide service.
     *
     * A privacy context is running at full capacity when the underlying webdriver pool is
     * full capacity, so the webdriver pool can not provide a webdriver for new tasks.
     *
     * Note that if a driver pool is retired or closed, it's not full capacity.
     *
     * @return True if the privacy context is running at full capacity, false otherwise.
     * */
    val isFullCapacity: Boolean
    /**
     * Check whether the privacy context is running under loaded.
     * */
    val isUnderLoaded: Boolean
    /**
     * Check whether the privacy context is idle.
     * */
    val isIdle: Boolean
    /**
     * Check whether the privacy context is retired.
     *
     * This property indicates whether the privacy context has been marked as retired.
     * It returns the value of the `retired` field, which is a boolean indicating the retirement status.
     *
     * @return `true` if the privacy context is retired, `false` otherwise.
     */
    val isRetired: Boolean
    /**
     * Check whether the privacy context is leaked.
     *
     * This property indicates whether the privacy context has been marked as leaked.
     * It returns the value of the `leaked` field, which is a boolean indicating the leakage status.
     *
     * @return `true` if the privacy context is leaked, `false` otherwise.
     */
    val isLeaked: Boolean
    /**
     * Check whether the privacy context is good.
     *
     * A good privacy context has to meet the following requirements:
     * 1. the fetch speed is good
     */
    val isGood: Boolean
    /**
     * Check whether the privacy context is active.
     *
     * TODO: check and distinct from [isReady] in use cases.
     *
     * An active privacy context has to meet the following requirements:
     * 1. not closed
     * 2. not leaked
     * 3. not retired
     *
     * Note: this flag does not guarantee consistency, and can change immediately after it's read
     * */
    val isActive: Boolean
    /**
     * Check whether the privacy context is closed.
     * */
    val isClosed: Boolean
    /**
     * A ready privacy context is ready to serve tasks.
     *
     * A ready privacy context has to meet the following requirements:
     * 1. not closed
     * 2. not leaked
     * 3. [requirement removed] not idle
     * 4. not retired
     * 5. if there is a proxy, the proxy has to be ready
     * 6. the associated driver pool promises to provide an available driver, ether one of the following:
     *    1. it has slots to create new drivers
     *    2. it has standby drivers
     *
     * Note: this flag does not guarantee consistency, and can change immediately after it's read
     * */
    val isReady: Boolean
    /**
     * The failure rate of the privacy context.
     * */
    val failureRate: Float
    /**
     * Check whether the failure rate is high.
     * */
    val isHighFailureRate: Boolean
    /**
     * The idle time of the privacy context.
     * */
    val idleTime: Duration
    /**
     * The elapsed time of the privacy context.
     * */
    val elapsedTime: Duration
    /**
     * A readable privacy context display.
     * */
    val display: String
    /**
     * Get the readable privacy context state.
     * */
    val state: Map<String, Any?>
    /**
     * Get the readable privacy context state.
     * */
    val readableState: String
    /**
     * Build the privacy context status string.
     * */
    fun buildStatusString(): String
    /**
     * The promised workers (free web drivers) count.
     *
     * The implementation has to tell the caller how many workers (free web drivers)
     * it can provide.
     *
     * The number of workers can change immediately after reading,
     * so the caller only has promises but no guarantees.
     *
     * @return the number of workers promised.
     * */
    fun promisedWebDriverCount(): Int
    /**
     * Check if the privacy context promises at least one worker to provide.
     * */
    fun hasWebDriverPromise(): Boolean
    /**
     * Open a page in the privacy context.
     *
     * @param url The URL to open.
     * @return The fetch result.
     * */
    suspend fun open(url: String): FetchResult
    /**
     * Open a page in the privacy context.
     *
     * @param url The URL to open.
     * @param fetchFun The fetch function to use.
     * @return The fetch result.
     * */
    suspend fun open(url: String, fetchFun: suspend (FetchTask, WebDriver) -> FetchResult): FetchResult
    /**
     * Open a page in the privacy context.
     *
     * @param url The URL to open.
     * @param options The load options to use.
     * @return The fetch result.
     * */
    suspend fun open(url: String, options: LoadOptions): FetchResult
    /**
     * Run a task in the privacy context.
     *
     * @param task The task to run.
     * @param fetchFun The fetch function to use.
     * @return The fetch result.
     * */
    suspend fun run(task: FetchTask, fetchFun: suspend (FetchTask, WebDriver) -> FetchResult): FetchResult
    /**
     * Run a task in the privacy context.
     *
     * @param task The task to run.
     * @param fetchFun The fetch function to use.
     * @return The fetch result.
     * */
    @Throws(Exception::class)
    suspend fun doRun(task: FetchTask, fetchFun: suspend (FetchTask, WebDriver) -> FetchResult): FetchResult
    /**
     * Dismiss the privacy context.
     *
     * This method is used to dismiss the privacy context.
     * It should be called when the privacy context is no longer needed.
     * */
    fun dismiss()
    /**
     * Maintain the privacy context.
     *
     * This method is used to maintain the privacy context.
     * It should be called periodically to keep the privacy context alive.
     * */
    fun maintain()
    /**
     * Build the privacy context report.
     *
     * This method is used to build the privacy context report.
     * It should be called periodically to keep the privacy context alive.
     * */
    fun buildReport(): String

    companion object {
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

        fun createNextSequential(fingerprint: Fingerprint): Path {
            return BrowserFiles.computeNextSequentialContextDir(fingerprint = fingerprint)
        }

        fun createRandom(browserType: BrowserType): Path {
            return BrowserFiles.computeRandomTmpContextDir(browserType = browserType)
        }
    }
}
