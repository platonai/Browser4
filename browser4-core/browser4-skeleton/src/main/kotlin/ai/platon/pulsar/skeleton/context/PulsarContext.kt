package ai.platon.pulsar.skeleton.context

import ai.platon.pulsar.common.CheckState
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.urls.UrlAware
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.external.ModelResponse
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.gora.generated.GWebPage
import ai.platon.pulsar.skeleton.PulsarSettings
import ai.platon.pulsar.skeleton.TaskLoops
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.common.urls.NormURL
import ai.platon.pulsar.skeleton.crawl.fetch.driver.Browser
import ai.platon.pulsar.skeleton.session.PulsarSession
import ai.platon.pulsar.skeleton.workflow.common.GlobalCache
import ai.platon.pulsar.skeleton.workflow.fetch.driver.BrowserLaunchException
import ai.platon.pulsar.skeleton.workflow.fetch.driver.BrowserManager
import ai.platon.pulsar.skeleton.workflow.fetch.driver.WebDriver
import ai.platon.pulsar.skeleton.workflow.filter.ChainedUrlNormalizer
import com.google.common.annotations.Beta
import org.springframework.beans.BeansException
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import kotlin.reflect.KClass

/**
 * [PulsarContext] consists of a set of highly customizable components that provide
 * the core set of interfaces of the system and is used to create [PulsarSession].
 */
interface PulsarContext : java.lang.AutoCloseable {

    /**
     * The context id
     * */
    val id: Int

    /**
     * Flag that indicates whether this context is currently active.
     * */
    val isActive: Boolean

    /**
     * An immutable config loaded from the config file at startup, and never changes
     * */
    val configuration: ImmutableConfig

    /**
     * The url normalizer
     * */
    val urlNormalizer: ChainedUrlNormalizer

    /**
     * The global cache
     * */
    val globalCache: GlobalCache

    /**
     * The browser manager.
     * */
    val browserManager: BrowserManager

    /**
     * The main loops
     * */
    val taskLoops: TaskLoops

    /**
     * Get a bean with the specified class, throws [BeansException] if the bean doesn't exist
     * */
    @Throws(BeansException::class)
    fun <T : Any> getBean(requiredType: KClass<T>): T

    /**
     * Get a bean with the specified class, returns null if the bean doesn't exist
     * */
    fun <T : Any> getBeanOrNull(requiredType: KClass<T>): T?

    /**
     * Create a pulsar session
     * */
    fun createSession(): PulsarSession

    /**
     * Create a pulsar session
     * */
    fun getOrCreateSession(): PulsarSession

    /**
     * Create a pulsar session
     * */
    fun createSession(settings: PulsarSettings): PulsarSession

    /**
     * Create a pulsar session
     * */
    fun getOrCreateSession(settings: PulsarSettings): PulsarSession

    /**
     * Close a pulsar session
     * */
    fun closeSession(session: PulsarSession)

    /**
     * Register closable objects, the objects will be closed when the context closes.
     * It's safe to register the same object multiple times, the object will be closed only once.
     *
     * @param closable The object to close
     * @param priority The priority of the object, the higher the priority, the earlier the object is closed
     * */
    fun registerClosable(closable: java.lang.AutoCloseable, priority: Int = 0)

    /**
     * Launch the system default browser, the system default browser is your daily used browser.
     * */
    @Throws(BrowserLaunchException::class)
    fun launchSystemDefaultBrowser(): Browser =
        browserManager.launchSystemDefaultBrowser().also { registerClosable(it) }

    /**
     * Launch the default browser, notice, the default browser is not the one you used daily.
     * */
    @Throws(BrowserLaunchException::class)
    fun launchDefaultBrowser(): Browser = browserManager.launchDefaultBrowser().also { registerClosable(it) }

    /**
     * Launch the prototype browser, the prototype browser is a browser instance with default settings.
     * */
    @Throws(BrowserLaunchException::class)
    fun launchPrototypeBrowser(): Browser = browserManager.launchPrototypeBrowser().also { registerClosable(it) }

    /**
     * Launch a random temporary browser, the browser's user data dir is a random temporary dir.
     * */
    @Throws(BrowserLaunchException::class)
    fun launchRandomTempBrowser(): Browser = browserManager.launchRandomTempBrowser().also { registerClosable(it) }

    /**
     * Normalize a url, the url can be in one of the following forms:
     * 1. a normal url
     * 2. a configured url
     * 3. a base64 encoded url
     * 4. a base64 encoded configured url
     *
     * A url can be configured by appending arguments to the url, and it also can be used with a LoadOptions,
     * If both tailing arguments and LoadOptions are present, the LoadOptions overrides the tailing arguments,
     * but default values in LoadOptions are ignored.
     *
     * @param url The url to normalize
     * @param options The LoadOptions applied to the url
     * @param toItemOption If the LoadOptions is converted to item load options
     * @return The normalized url or NIL if the input url is invalid
     * */
    fun normalize(url: String, options: LoadOptions, toItemOption: Boolean = false): NormURL

    /**
     * Normalize a url, the url can be in one of the following forms:
     * 1. a normal url
     * 2. a configured url
     * 3. a base64 encoded url
     * 4. a base64 encoded configured url
     *
     * A url can be configured by appending arguments to the url, and it also can be used with a LoadOptions,
     * If both tailing arguments and LoadOptions are present, the LoadOptions overrides the tailing arguments,
     * but default values in LoadOptions are ignored.
     *
     * @param url The url to normalize
     * @param options The LoadOptions applied to the url
     * @param toItemOption If the LoadOptions is converted to item load options
     * @return The normalized url or null if the input url is invalid
     * */
    fun normalizeOrNull(url: String?, options: LoadOptions, toItemOption: Boolean = false): NormURL?

    /**
     * Normalize urls, remove invalid ones
     *
     * @param urls The urls to normalize
     * @param options The LoadOptions applied to each url
     * @param toItemOption If the LoadOptions is converted to item load options
     * @return All normalized urls, all invalid input urls are removed
     * */
    fun normalize(urls: Iterable<String>, options: LoadOptions, toItemOption: Boolean = false): List<NormURL>

    /**
     * Normalize a url.
     *
     * If both url arguments and LoadOptions are present, the LoadOptions overrides the tailing arguments,
     * but default values in LoadOptions are ignored.
     *
     * @param url The url to normalize
     * @param options The LoadOptions applied to the url
     * @param toItemOption If the LoadOptions is converted to item load options
     * @return The normalized url or NIL if the input url is invalid
     * */
    fun normalize(url: UrlAware, options: LoadOptions, toItemOption: Boolean = false): NormURL

    /**
     * Normalize a url, the url can be in one of the following forms:
     * 1. a normal url
     * 2. a configured url
     * 3. a base64 encoded url
     * 4. a base64 encoded configured url
     *
     * A url can be configured by appending arguments to the url, and it also can be used with a LoadOptions,
     * If both tailing arguments and LoadOptions are present, the LoadOptions overrides the tailing arguments,
     * but default values in LoadOptions are ignored.
     *
     * @param url The url to normalize
     * @param options The LoadOptions applied to the url
     * @param toItemOption If the LoadOptions is converted to item load options
     * @return The normalized url or null if the input url is invalid
     * */
    fun normalizeOrNull(url: UrlAware?, options: LoadOptions, toItemOption: Boolean = false): NormURL?

    /**
     * Normalize urls, remove invalid ones
     *
     * @param urls The urls to normalize
     * @param options The LoadOptions applied to each url
     * @param toItemOption If the LoadOptions is converted to item load options
     * @return All normalized urls, all invalid input urls are removed
     * */
    fun normalize(urls: Collection<UrlAware>, options: LoadOptions, toItemOption: Boolean = false): List<NormURL>

    /**
     * Get a webpage from the storage
     *
     * @param url The url of the page to retrieve
     * @return The web page, if the page doesn't exist, a NIL page is return
     */
    fun get(url: String): WebPage

    /**
     * Get a page from storage.
     *
     * @param url The url
     * @param fields The fields to load from local storage
     * @return The webpage in storage if exists, otherwise returns a NIL page
     */
    fun get(url: String, vararg fields: String): WebPage

    /**
     * Get a webpage from the storage
     *
     * @param url The url of the page to retrieve
     * @return The web page or null
     */
    fun getOrNull(url: String): WebPage?

    /**
     * Get a page from storage.
     *
     * @param url The url
     * @param fields The fields to load from local storage
     * @return The page in storage if exists, otherwise returns null
     */
    fun getOrNull(url: String, vararg fields: String): WebPage?

    /**
     * Get the content of the page from the storage
     *
     * @param url The url of the page to retrieve
     * @return The page content or null
     */
    fun getContent(url: String): ByteBuffer?

    /**
     * Get the content of the page from the storage
     *
     * @param url The url of the page to retrieve
     * @return The page content in string format or null
     */
    @Beta
    fun getContentAsString(url: String): String?

    /**
     * Get a webpage from the storage
     *
     * @param url The url of the page to check
     * @return True if the page exists in the storage
     */
    fun exists(url: String): Boolean

    /**
     * Check the fetch state of a webpage
     *
     * @param page The webpage to check
     * @return The fetch state of the webpage
     */
    fun fetchState(page: WebPage, options: LoadOptions): CheckState

    /**
     * Scan webpages in the storage whose url start with [urlPrefix]
     *
     * @param urlPrefix The url prefix
     * @return The iterator of the webpages whose url start with [urlPrefix]
     */
    fun scan(urlPrefix: String): Iterator<WebPage>

    fun scan(urlPrefix: String, fields: Iterable<GWebPage.Field>): Iterator<WebPage>

    fun scan(urlPrefix: String, fields: Array<String>): Iterator<WebPage>

    /**
     * Open a webpage with options and a web driver
     *
     * @param url The url to open
     * @param options The options
     * @param driver The web driver
     * @return The web page
     * */
    suspend fun open(url: String, driver: WebDriver, options: LoadOptions): WebPage

    /**
     * Attach a webpage to the given webdriver. This method is very like open(), but no url navigation.
     *
     * ```kotlin
     * val url = context.normalize(driver.currentUrl())
     * val page = context.attach(url, driver)
     * ```
     *
     * @param normURL The NormURL of the page.
     * @param driver The webdriver to attach to.
     */
    suspend fun capture(normURL: NormURL, driver: WebDriver): WebPage

    /**
     * Load a url with specified options, see [LoadOptions] for all options
     *
     * @param url     The url followed by options
     * @param options The options
     * @return The WebPage. If there is no web page at local storage nor remote location, [WebPageImpl.NIL] is returned
     */
    fun load(url: String, options: LoadOptions): WebPage

    /**
     * Load a url with specified options, see [LoadOptions] for all options
     *
     * @param url     The url followed by options
     * @param options The options
     * @return The WebPage. If there is no web page at local storage nor remote location, [WebPageImpl.NIL] is returned
     */
    fun load(url: URL, options: LoadOptions): WebPage

    /**
     * Load a url, options can be specified following the url, see [LoadOptions] for all options
     *
     * @param url The url followed by options
     * @return The WebPage. If there is no web page at local storage nor remote location, [WebPageImpl.NIL] is returned
     */
    fun load(url: NormURL): WebPage

    /**
     * Load a url, options can be specified following the url, see [LoadOptions] for all options
     *
     * @param url The url followed by options
     * @return The WebPage. If there is no web page at local storage nor remote location, [WebPageImpl.NIL] is returned
     */
    suspend fun loadDeferred(url: NormURL): WebPage

    /**
     * Load a batch of urls with the specified options.
     *
     * If the option indicates prefer parallel, urls are fetched in a parallel manner whenever applicable.
     * If the batch is too large, only a random part of the urls is fetched immediately, all the rest urls are put into
     * a pending fetch list and will be fetched in background later.
     *
     * If a page exists neither in local storage nor at the given remote location, [WebPageImpl.NIL] is returned
     *
     * @param urls    The urls to load
     * @param options The options
     * @return Pages for all urls.
     */
    fun loadAll(urls: Iterable<String>, options: LoadOptions): Collection<WebPage>

    /**
     * Load a batch of urls with the specified options.
     *
     * If the option indicates prefer parallel, urls are fetched in a parallel manner whenever applicable.
     * If the batch is too large, only a random part of the urls is fetched immediately, all the rest urls are put into
     * a pending fetch list and will be fetched in background later.
     *
     * If a page exists neither in local storage nor at the given remote location, [WebPageImpl.NIL] is returned
     *
     * @param urls    The urls to load
     * @return Pages for all urls.
     */
    fun loadAll(urls: Iterable<NormURL>): Collection<WebPage>

    /**
     * Load a url asynchronously, the url is added to the task queue, and will be executed asynchronously
     * */
    fun loadAsync(url: NormURL): CompletableFuture<WebPage>

    /**
     * Load a batch of urls asynchronously, the urls are added to the task queue, and will be executed asynchronously
     * */
    fun loadAllAsync(urls: Iterable<NormURL>): List<CompletableFuture<WebPage>>

    /**
     * Submit a url, the url will be added to the task queue, and will be executed asynchronously.
     *
     * The url should be standard or degenerate, otherwise it will be discarded.
     *
     * @param url The url to submit, which will be added to the task queue
     * @return The [PulsarContext] itself to enabled chained operations
     * */
    fun submit(url: UrlAware): PulsarContext

    /**
     * Submit a batch of urls, the urls are added to the task queue, and will be executed asynchronously.
     *
     * The urls should be standard or degenerate, otherwise they will be discarded.
     *
     * @param urls The urls to submit, which will be added to the task queue
     * @return The [PulsarContext] itself to enabled chained operations
     * */
    fun submitAll(urls: Iterable<UrlAware>): PulsarContext

    /**
     * Parse the WebPage using ParseComponent
     */
    fun parse(page: WebPage): FeaturedDocument?

    /**
     * Chat with the AI model.
     *
     * @param prompt The prompt to chat
     */
    suspend fun chat(prompt: String): ModelResponse

    /**
     * Chat with the AI model.
     *
     * @param userMessage The user message to chat
     * @param systemMessage The system message to chat
     * */
    suspend fun chat(userMessage: String, systemMessage: String): ModelResponse

    /**
     * Persist the webpage into the storage immediately.
     * By default, the backend storage is the local file system, if mongodb is detected,
     * the mongodb will be used as the backend storage.
     */
    fun persist(page: WebPage)

    /**
     * Delete the webpage from the backend storage
     */
    fun delete(url: String)

    /**
     * Delete the webpage from the backend storage
     */
    fun delete(page: WebPage)

    /**
     * Flush the backend storage
     */
    fun flush()

    /**
     * Wait until all tasks are done.
     * */
    @Throws(InterruptedException::class)
    fun await()

    /**
     * Register shutdown hook
     * */
    fun registerShutdownHook()
}
