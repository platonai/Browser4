package ai.platon.pulsar.skeleton.workflow.fetch.privacy

import ai.platon.pulsar.common.browser.fingerprint.Fingerprint
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.skeleton.workflow.fetch.FetchResult
import ai.platon.pulsar.skeleton.workflow.fetch.FetchTask
import ai.platon.pulsar.skeleton.workflow.fetch.WebDriverFetcher
import ai.platon.pulsar.skeleton.browser.driver.WebDriver

/**
 * Manages the lifecycle of privacy contexts, including permanent and temporary contexts.
 * Permanent contexts have a long lifecycle and are never deleted, while temporary contexts
 * are short-lived and discarded if a privacy leak is detected.
 */
interface PrivacyManager : AutoCloseable {
    /**
     * Indicates whether the privacy manager is active.
     */
    val isActive: Boolean

    /**
     * Indicates whether the privacy manager is closed.
     */
    val isClosed: Boolean

    /**
     * The immutable configuration used by the privacy manager.
     */
    val conf: ImmutableConfig

    /**
     * Builds a status string summarizing the current state of active contexts.
     *
     * @return A string representation of the active contexts' status.
     */
    fun buildStatusString(): String

    /**
     * Performs maintenance tasks on the privacy manager.
     *
     * @param force If true, forces maintenance tasks to run.
     */
    fun maintain(force: Boolean = false)

    /**
     * Resets the privacy environment by closing all privacy contexts.
     *
     * @param reason The reason for resetting the privacy environment.
     */
    fun reset(reason: String = "")

    /**
     * Runs a fetch task within a privacy context.
     *
     * The privacy context is selected from the active privacy context pool,
     * and it is supposed to have at least one ready web driver to run the task.
     * If the chosen context is not ready to serve, the task will be canceled.
     *
     * @param task The fetch task to execute.
     * @param fetchFun The function to execute the fetch task.
     * @return The result of the fetch task.
     */
    suspend fun run(task: FetchTask, fetchFun: suspend (FetchTask, WebDriver) -> FetchResult): FetchResult

    /**
     * Attempts to get the next ready privacy context for a given page, fingerprint, and task.
     *
     * @param page The web page associated with the context.
     * @param fingerprint The fingerprint used to identify the context.
     * @param task The fetch task associated with the context.
     * @return The next ready privacy context.
     */
    fun tryGetNextReadyPrivacyContext(page: WebPage, fingerprint: Fingerprint, task: FetchTask): PrivacyContext

    /**
     * Attempts to get the next ready privacy context for a given fingerprint.
     *
     * @param fingerprint The fingerprint used to identify the context.
     * @return The next ready privacy context.
     */
    fun tryGetNextReadyPrivacyContext(fingerprint: Fingerprint): PrivacyContext

    /**
     * Attempts to get the next under-loaded privacy context for a given page, fingerprint, and task.
     *
     * @param page The web page associated with the context.
     * @param fingerprint The fingerprint used to identify the context.
     * @param task The fetch task associated with the context.
     * @return The next under-loaded privacy context, or null if none is available.
     */
    fun tryGetNextUnderLoadedPrivacyContext(page: WebPage, fingerprint: Fingerprint, task: FetchTask): PrivacyContext?

    /**
     * Gets or creates a privacy context for the given browser profile.
     *
     * @param profile The browser profile associated with the context.
     * @return The privacy context.
     */
    fun getOrCreate(profile: BrowserProfile): PrivacyContext

    /**
     * Creates an unmanaged privacy context for the given browser profile.
     *
     * @param profile The browser profile associated with the context.
     * @return The unmanaged privacy context.
     */
    fun createUnmanagedContext(profile: BrowserProfile): PrivacyContext

    /**
     * Creates an unmanaged privacy context for the given browser profile and fetcher.
     *
     * @param profile The browser profile associated with the context.
     * @param fetcher The web driver fetcher used to create the context.
     * @return The unmanaged privacy context.
     */
    fun createUnmanagedContext(profile: BrowserProfile, fetcher: WebDriverFetcher): PrivacyContext

    /**
     * Closes a given privacy context, moving it from the active list to the zombie list.
     *
     * @param privacyContext The privacy context to close.
     */
    fun close(privacyContext: PrivacyContext)
}
