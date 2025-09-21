package ai.platon.pulsar.skeleton.crawl.fetch.driver

import ai.platon.pulsar.browser.common.BrowserSettings
import ai.platon.pulsar.browser.driver.chrome.NetworkResourceResponse
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.math.geometric.PointD
import ai.platon.pulsar.common.math.geometric.RectD
import ai.platon.pulsar.common.urls.Hyperlink
import ai.platon.pulsar.dom.nodes.GeoAnchor
import ai.platon.pulsar.external.ModelResponse
import ai.platon.pulsar.skeleton.ai.tta.InstructionResult
import com.google.common.annotations.Beta
import org.jsoup.Connection
import java.io.Closeable
import java.time.Duration

/**
 * Frame information for iframe management
 */
data class FrameInfo(
    val frameId: String,
    val url: String,
    val name: String? = null,
    val parentFrameId: String? = null,
    val isAccessible: Boolean = true,
    val loadState: String = "LOADED"
)

/**
 * [WebDriver] defines a concise interface to visit and manipulate webpages.
 *
 * The webpage is rendered to a Document Object Model (DOM) in a real browser, and the interface provides methods to
 * control the browser, select textContent and attributes of Elements, and interact with the webpage.
 *
 * All actions and behaviors are optimized to mimic real people as closely as possible, such as scrolling, clicking,
 * typing text, dragging and dropping, etc.
 *
 * The term `document` here refers to a Document Object Model (DOM) within a browser.
 *
 * The methods in this interface fall into three categories:
 *
 * * Control of the browser itself
 * * Selection of textContent and attributes of Elements
 * * Interact with the webpage
 *
 * Key methods:
 * * [navigateTo]: navigate to a URL.
 * * [currentUrl]: get the current URL displayed in the address bar.
 * * [scrollDown]: scroll down on a webpage to fully load the page. Most modern webpages support lazy loading
 * using ajax tech, where the page content only starts to load when it is scrolled into view.
 * * [pageSource]: retrieve the source code of the webpage.
 *
 * For each document, there are several properties that represent the URL of the document:
 * * `driver.currentUrl()`: Returns the URL displayed in the address bar, it can be either navigated or not.
 * * `driver.url()`, `document.URL`: Returns the URL of the document.
 * * `driver.documentURI()`, `document.documentURI`: Returns the URI of the document.
 * * `driver.baseURI()`, `document.baseURI`: Returns the base URI of the document.
 * * `document.location`: Represents the location (URL) of the current page and allows you to manipulate the URL.
 *
 * In the Document Object Model (DOM), the relationship between `document.URL`, `document.documentURI`,
 * `document.location`, and the URL displayed in the browser's address bar is as follows:
 * * `driver.currentUrl()`:
 *    - This ready-only property displayed in the browser's address bar is what users see and can edit directly.
 *    - This ready-only property can be either navigated or not.
 *    - When the page is loaded or when `document.location` is modified, the address bar is updated to reflect the new URL.
 *    - It is typically synchronized with `document.URL` and `document.location.href` (a property of `document.location`).
 * * `driver.url()`, `document.URL`:
 *    - This property returns the URL of the document as a string.
 *    - It is a read-only property and reflects the current URL of the document.
 *    - Changes to `document.location` will also update `document.URL`.
 * * `driver.documentURI()`, `document.documentURI`:
 *    - This property returns the URI of the document.
 *    - It is also a read-only property and typically contains the same value as `document.URL`.
 *    - However, `document.documentURI` is defined to be the URI that was provided to the parser, which could
 *      potentially differ from `document.URL` in certain cases, although in practice, this is rare.
 * * `driver.baseURI()`, `document.baseURI`:
 *    - This property returns the base URI of the document.
 *    - The base URI is used to resolve relative URLs within the document.
 *    - It is a read-only property and is typically the URL of the document, unless a `<base>` element is present
 *    in the document, in which case the value of the `href` attribute of the `<base>` element is used.
 *    - If no `<base>` element is present, the base URI is the same as `document.URL`.
 * * `document.location`:
 *    - This property represents the location (URL) of the current page and allows you to manipulate the URL.
 *    - It is a read-write property, which means you can change it to navigate to a different page or to manipulate
 *      query strings, fragments, etc.
 *    - Changes to `document.location` will cause the browser to navigate to the new URL, updating both `document.URL`
 *      and the URL displayed in the address bar.
 *
 * In summary, `document.URL` and `document.documentURI` are read-only properties that reflect the current URL of the
 * document, while `document.location` is a read-write property that not only reflects the current URL but also allows
 * you to navigate to a new one. The URL displayed in the address bar is a user-facing representation of the current
 * document's URL, which is usually in sync with `document.location`.
 *
 * In addition to the above properties, The method `driver.referrer()` returns the document's referrer.
 * The `document.referrer` property returns the URI of the page that linked to the current page. If the user navigated
 * directly to the page (e.g., via a bookmark), the value is an empty string. Inside an `<iframe>`, the referrer is
 * initially set to the same value as the `href` of the parent window's `Window.location`.
 *
 * The following example demonstrates how to use the WebDriver interface to visit a webpage and interact with it:
 *
 * ```kotlin
 *  fun visit() {
 *      val url = "https://twitter.com/home"
 *      val args = "-refresh"
 *      val options = session.options(args)
 *
 *      options.eventHandlers.browseEventHandlers.onDocumentSteady.addLast { page, driver ->
 *          interact(page, driver)
 *      }
 *
 *      session.load(url, options)
 *  }
 *
 *  // interact with the page
 *  private suspend fun interact(driver: WebDriver) {
 *      val selector = "input[placeholder*=搜索], input[placeholder*=Search]"
 *      driver.waitForSelector(selector)
 *      driver.fill(selector, "Facebook")
 *      driver.press(selector, "Space")
 *      "Email".forEach { driver.press(selector, "$it") }
 *      driver.press(selector, "Enter")
 *  }
 * ```
 *
 * @see [Document ](https://developer.mozilla.org/en-US/docs/Web/API/Document)
 * @see [Document Object Model (DOM)](https://developer.mozilla.org/en-US/docs/Web/API/Document_Object_Model)
 * @see [Document: URL property](https://developer.mozilla.org/en-US/docs/Web/API/Document/URL)
 * @see [Document: documentURI property](https://developer.mozilla.org/en-US/docs/Web/API/Document/documentURI)
 * @see [Document: baseURI property](https://developer.mozilla.org/en-US/docs/Web/API/Document/baseURI)
 * @see [Document: referrer property](https://developer.mozilla.org/en-US/docs/Web/API/Document/referrer)
 * @see [Document: location property](https://developer.mozilla.org/en-US/docs/Web/API/Document/location)
 *
 * @see BrowserSettings
 */
interface WebDriver : Closeable {
    /**
     * The driver id.
     * */
    val id: Int
    /**
     * The parent driver id.
     * */
    val parentSid: Int
    /**
     * The browser of the driver.
     * The browser defines methods and events to manipulate a real browser.
     * */
    val browser: Browser

    /**
     * Web pages for the page open from the current page, via window.open(), link click, form submission,
     * etc.
     *
     * TODO: NOT IMPLEMENTED
     * */
    val frames: List<WebDriver>

    /**
     * The driver from whom opens the current page.
     * */
    val opener: WebDriver?

    /**
     * Web pages for the page open from the current page, via window.open(), link click, form submission,
     * etc.
     * */
    val outgoingPages: Set<WebDriver>

    /**
     * The browser type.
     * BrowserType.PULSAR_CHROME is the only fully supported browser type currently.
     * BrowserType.PLAYWRIGHT_CHROME is a partially supported browser type since Playwright is not thread safe.
     * */
    val browserType: BrowserType

    /**
     * The current navigation entry.
     * */
    var navigateEntry: NavigateEntry

    /**
     * The navigation history.
     * */
    val navigateHistory: NavigateHistory

    /**
     * The associated data of the driver.
     * */
    val data: MutableMap<String, Any?>

    /**
     * The delay policy of the driver. The delay policy is a map of delay ranges in milliseconds for different actions.
     *
     * The delay policy is used to simulate human behaviors, such as typing, clicking, scrolling, etc.
     *
     * ```kotlin
     * delayPolicy["click"] == 500..1000
     * ```
     * */
    val delayPolicy: Map<String, IntRange>

    /**
     * The timeout policy of the driver. The timeout policy is a map of timeout durations for different actions.
     *
     * The timeout policy is used to set the maximum time to wait for an action to complete.
     *
     * ```kotlin
     * timeoutPolicy["click"] == Duration.ofSeconds(30)
     * ```
     * */
    val timeoutPolicy: Map<String, Duration>

    /**
     * Returns a JvmWebDriver to support other JVM languages, such as java, clojure, scala, and so on,
     * the other JVM languages might have difficulty to handle kotlin suspend methods.
     * @see JvmWebDriver
     * */
    fun jvm(): JvmWebDriver

    /**
     * Adds a script which would be evaluated in one of the following scenarios:
     *
     * * Whenever the page is navigated.
     *
     * The script is evaluated after the document was created but before any of
     * its scripts were run. This is useful to amend the JavaScript environment, e.g.
     * to seed [Math.random].
     *
     * This function should be invoked before a navigation, usually in an onWillNavigate
     * event handler.
     *
     * @param script Javascript source code to add.
     * */
    @Throws(WebDriverException::class)
    suspend fun addInitScript(script: String)

    /**
     * Blocks resource URLs from loading.
     *
     * @param urlPatterns URL patterns to block. Wildcards ('*') are allowed.
     */
    @Throws(WebDriverException::class)
    suspend fun addBlockedURLs(urlPatterns: List<String>)

    /**
     * Block resource URL loading with a certain probability.
     *
     * @param urlPatterns Regular expressions of URLs to block.
     */
    suspend fun addProbabilityBlockedURLs(urlPatterns: List<String>)

    /**
     * TODO: NOT IMPLEMENTED
     * */
    @Throws(WebDriverException::class)
    suspend fun setTimeouts(browserSettings: BrowserSettings)

    /**
     * Opens the specified URL in the web driver.
     *
     * This function navigates the web driver to the provided URL and waits for the navigation to complete.
     * It is a suspend function, meaning it can be used within coroutines for asynchronous execution.
     *
     * Example usage:
     * ```kotlin
     * driver.open("https://www.example.com")
     * ```
     *
     * @param url The URL to which the web driver should navigate. Must be a valid URL string.
     * @throws WebDriverException If an error occurs during navigation or waiting for the navigation to complete.
     */
    @Throws(WebDriverException::class)
    suspend fun open(url: String) {
        // Navigates the web driver to the specified URL.
        navigateTo(url)
        // Waits for the navigation to complete before proceeding.
        waitForNavigation()
    }

    /**
     * Navigates current page to the given URL.
     *
     * ```kotlin
     * driver.navigateTo("https://www.example.com")
     * driver.waitForNavigation()
     * ```
     *
     * @param url URL to navigate page to.
     */
    @Throws(WebDriverException::class)
    suspend fun navigateTo(url: String)

    /**
     * Navigates current page to the given URL.
     *
     * ```kotlin
     * val entry = NavigateEntry("https://www.example.com?timestamp=11712067353", pageUrl = "https://www.example.com")
     * driver.navigateTo(entry)
     * driver.waitForNavigation()
     * ```
     *
     * @param entry NavigateEntry to navigate page to.
     */
    @Throws(WebDriverException::class)
    suspend fun navigateTo(entry: NavigateEntry)

    /**
     * Returns a string representing the current URL that the browser is looking at. The current url is always
     * the main frame's `document.documentURI` if the browser succeed to return it, and is displayed in the browser's
     * address bar.
     *
     * If the browser failed to return a proper url, returns the passed in url to navigate, just like a real user enter
     * a url in the address bar but the browser failed to load the page.
     *
     * NOTE: the url can be non-standard, for example:
     *
     * about:blank
     * chrome://newtab
     * chrome://settings
     *
     * @return A string containing the URL of the document, or the passed in url to navigate.
     */
    @Throws(WebDriverException::class)
    suspend fun currentUrl(): String

    /**
     * The URL read-only property of the Document interface returns the document location as a string.
     *
     * This property equals to javascript `document.URL`.
     * The `document.URL` property returns the same value. The `document.documentURI` property can be used on
     * any document types, while the `document.URL` property can only be used on HTML documents.
     *
     * @see [Document: URL property](https://developer.mozilla.org/en-US/docs/Web/API/Document/URL)
     *
     * @return A string containing the URL of the document.
     */
    @Throws(WebDriverException::class)
    suspend fun url(): String

    /**
     * Returns the document location as a string.
     *
     * This property equals to javascript `document.documentURI`.
     *
     * The `document.URL` property which returns the same value. The `document.documentURI` property can be used on
     * any document types, while the `document.URL` property can only be used on HTML documents.
     *
     * @see [Document: documentURI property](https://developer.mozilla.org/en-US/docs/Web/API/Document/documentURI)
     *
     * @return The document's documentURI.
     * */
    suspend fun documentURI(): String

    /**
     * Returns the document's baseURI.
     *
     * The baseURI is a property of Node, it's the absolute base URL of the
     * document containing the node. A baseURI is used to resolve relative URLs.
     *
     * The base URL is determined as follows:
     * 1. By default, the base URL is the location of the document
     *    (as determined by window.location).
     * 2. If the document has an `<base>` element, its href attribute is used.
     *
     * @return The document's baseURI.
     * */
    suspend fun baseURI(): String

    /**
     * The referrer property returns the URI of the page that linked to this page.
     *
     * The value is an empty string if the user navigated to the page directly (not through a link, but, for example,
     * by using a bookmark).
     *
     * Inside an <iframe>, the referrer will initially be set to the same value as the href of the parent
     * window's Window.location.
     *
     * @return The document's referrer.
     * */
    @Throws(WebDriverException::class)
    suspend fun referrer(): String

    /**
     * Returns the source of the last loaded page. If the page has been modified after loading (for
     * example, by Javascript) there is no guarantee that the returned text is that of the modified
     * page.
     *
     * TODO: distinguish pageSource and outerHTML
     *
     * ```kotlin
     * val pageSource = driver.pageSource()
     * ```
     *
     * @return The source of the current page
     */
    @Throws(WebDriverException::class)
    suspend fun pageSource(): String?

    /**
     * Chat with the AI model about the specified element.
     *
     * @param prompt The prompt to chat with
     * @param selector The selector to find the element
     * @return The response from the model
     */
    suspend fun chat(prompt: String, selector: String): ModelResponse
    /**
     * Instructs the webdriver to perform a series of actions based on the given prompt.
     * This function converts the prompt into a sequence of webdriver actions, which are then executed.
     *
     * @param prompt The textual prompt that describes the actions to be performed by the webdriver.
     * @return The response from the model, though in this implementation, the return value is not explicitly used.
     */
    @Throws(WebDriverException::class)
    suspend fun instruct(prompt: String): InstructionResult

    /**
     * Returns the cookies of the current page.
     *
     * ```kotlin
     * val cookies = driver.getCookies()
     * ```
     *
     * @return The cookies of the current page.
     */
    @Throws(WebDriverException::class)
    suspend fun getCookies(): List<Map<String, String>>

    @Deprecated("Use deleteCookies(name, url, domain, path) instead." +
            "[deleteCookies] (3/5) | code: -32602, At least one of the url and domain needs to be specified",
        ReplaceWith("driver.deleteCookies(name, url, domain, path)"))
    @Throws(WebDriverException::class)
    suspend fun deleteCookies(name: String)

    /**
     * Deletes browser cookies with matching name and url or domain/path pair.
     *
     * ```kotlin
     * driver.deleteCookies("name", "https://www.example.com")
     * ```
     *
     * > NOTE: At least one of the url and domain needs to be specified
     *
     * @param name Name of the cookies to remove.
     * @param url If specified, deletes all the cookies with the given name where domain and path
     * match provided URL.
     * @param domain If specified, deletes only cookies with the exact domain.
     * @param path If specified, deletes only cookies with the exact path.
     */
    @Throws(WebDriverException::class)
    suspend fun deleteCookies(name: String, url: String? = null, domain: String? = null, path: String? = null)

    /**
     * Clears browser cookies.
     *
     * ```kotlin
     * driver.clearBrowserCookies()
     * ```
     *
     * TODO: consider only use driver.browser.clearCookies()
     * @see Browser.clearCookies
     * */
    @Throws(WebDriverException::class)
    suspend fun clearBrowserCookies()

    /**
     * Wait until the element identified by the selector becomes present in the DOM or timeout.
     *
     * ```kotlin
     * val remainingTime = driver.waitForSelector("h2.title")
     * ```
     *
     * @param selector The selector of the element to wait for.
     * @return The remaining time until timeout when the element becomes present.
     * */
    @Throws(WebDriverException::class)
    suspend fun waitForSelector(selector: String): Duration = waitForSelector(selector) {}

    /**
     * Wait until the element identified by the selector becomes present in the DOM or timeout.
     *
     * ```kotlin
     * val remainingTime = driver.waitForSelector("h2.title", 30000)
     * ```
     *
     * @param timeoutMillis The maximum time to wait for the element to become present.
     * @return The remaining time until timeout when the element becomes present.
     * */
    @Throws(WebDriverException::class)
    suspend fun waitForSelector(selector: String, timeoutMillis: Long): Long =
        waitForSelector(selector, timeoutMillis) {}

    /**
     * Wait for the element identified by the selector to become present in the DOM, or until timeout.
     *
     * ```kotlin
     * val remainingTime = driver.waitForSelector("h2.title", Duration.ofSeconds(30))
     * ```
     *
     * @param timeout The maximum time to wait for the element to become present.
     * @return The remaining time until timeout when the element becomes present.
     * */
    @Throws(WebDriverException::class)
    suspend fun waitForSelector(selector: String, timeout: Duration): Duration = waitForSelector(selector, timeout) {}

    /**
     * Wait for the element identified by the selector to become present in the DOM, or until timeout.
     * This method periodically checks for the existence of the element. If the element is not found during a check,
     * the action will be executed, such as scrolling the page down.
     *
     * ```kotlin
     * val remainingTime = driver.waitForSelector("h2.title") {
     *  driver.scrollDown()
     * }
     * ```
     *
     * @param action The action to execute when the element is not found.
     * */
    @Throws(WebDriverException::class)
    suspend fun waitForSelector(selector: String, action: suspend () -> Unit): Duration

    /**
     * Wait for the element identified by the selector to become present in the DOM, or until timeout.
     * This method periodically checks for the existence of the element. If the element is not found during a check,
     * the action will be executed, such as scrolling the page down.
     *
     * ```kotlin
     * val remainingTime = driver.waitForSelector("h2.title", 30000) {
     *  driver.scrollDown()
     * }
     * ```
     *
     * @param timeoutMillis The maximum time to wait for the element to become present.
     * @param action The action to execute when the element is not found.
     * @return The remaining time until timeout when the element becomes present.
     * */
    @Throws(WebDriverException::class)
    suspend fun waitForSelector(selector: String, timeoutMillis: Long, action: suspend () -> Unit): Long =
        waitForSelector(selector, Duration.ofMillis(timeoutMillis), action).toMillis()

    /**
     * Wait for the element identified by the selector to become present in the DOM, or until timeout.
     * This method periodically checks for the existence of the element. If the element is not found during a check,
     * the action will be executed, such as scrolling the page down.
     *
     * ```kotlin
     * val remainingTime = driver.waitForSelector("h2.title", Duration.ofSeconds(30)) {
     *  driver.scrollDown()
     * }
     * ```
     *
     * @param timeout The maximum time to wait for the element to become present.
     * @param action The action to execute when the element is not found.
     * @return The remaining time until timeout when the element becomes present.
     * */
    @Throws(WebDriverException::class)
    suspend fun waitForSelector(selector: String, timeout: Duration, action: suspend () -> Unit): Duration

    /**
     * Wait until the current url changes or timeout.
     *
     * ```kotlin
     * val url = "https://www.example.com"
     * driver.navigateTo(url)
     * var remainingTime = driver.waitForNavigation()
     * if (remainingTime > 0) {
     *   driver.click("a[href='/next']")
     *   remainingTime = driver.waitForNavigation(url)
     * }
     * ```
     * */
    @Throws(WebDriverException::class)
    suspend fun waitForNavigation(oldUrl: String = ""): Duration

    /**
     * Wait until the current url changes or timeout.
     *
     * ```kotlin
     * val url = "https://www.example.com"
     * driver.navigateTo(url)
     * var remainingTime = driver.waitForNavigation(1000)
     * if (remainingTime > 0) {
     *   driver.click("a[href='/next']")
     *   remainingTime = driver.waitForNavigation(url, 1000)
     * }
     * ```
     *
     * @param timeoutMillis The maximum time to wait for the url to change.
     * */
    @Throws(WebDriverException::class)
    suspend fun waitForNavigation(oldUrl: String = "", timeoutMillis: Long): Long =
        waitForNavigation(oldUrl, Duration.ofMillis(timeoutMillis)).toMillis()

    /**
     * Wait until the current url changes or timeout.
     *
     * ```kotlin
     * val timeout = Duration.ofSeconds(30)
     * val url = "https://www.example.com"
     * driver.navigateTo(url)
     * var remainingTime = driver.waitForNavigation(timeout)
     * if (remainingTime > 0) {
     *   driver.click("a[href='/next']")
     *   remainingTime = driver.waitForNavigation(url, timeout)
     * }
     * ```
     *
     * @param timeout The maximum time to wait for the url to change.
     * */
    @Throws(WebDriverException::class)
    suspend fun waitForNavigation(oldUrl: String = "", timeout: Duration): Duration

    /**
     * Await navigation to the specified URL page or timeout if necessary.
     *
     * ```kotlin
     * val newDriver = driver.waitForPage("https://www.example.com", Duration.ofSeconds(30))
     * ```
     *
     * @param url The URL to navigate to.
     * @return The remaining time until timeout when the predicate returns true.
     * */
    @Throws(WebDriverException::class)
    suspend fun waitForPage(url: String, timeout: Duration): WebDriver?

    /**
     * Wait until the predicate returns true.
     *
     * ```kotlin
     * val remainingTime = driver.waitUntil {
     *    driver.exists("h2.title")
     * }
     * ```
     *
     * @param predicate The predicate to check.
     * @return The remaining time until timeout when the predicate returns true.
     * */
    @Throws(WebDriverException::class)
    suspend fun waitUntil(predicate: suspend () -> Boolean): Duration

    /**
     * Wait until the predicate returns true.
     *
     * ```kotlin
     * val remainingTime = driver.waitUntil(10000) {
     *   driver.exists("h2.title")
     * }
     * ```
     *
     * @param timeoutMillis The maximum time to wait for the predicate to return true.
     * @param predicate The predicate to check.
     * @return The remaining time until timeout when the predicate returns true.
     * */
    @Throws(WebDriverException::class)
    suspend fun waitUntil(timeoutMillis: Long, predicate: suspend () -> Boolean): Long =
        waitUntil(Duration.ofMillis(timeoutMillis), predicate).toMillis()

    /**
     * Wait until the predicate returns true.
     *
     * ```kotlin
     * val remainingTime = driver.waitUntil(Duration.ofSeconds(10)) {
     *    driver.exists("h2.title")
     * }
     * ```
     *
     * @param timeout The maximum time to wait for the predicate to return true.
     * @param predicate The predicate to check.
     * @return The remaining time until timeout when the predicate returns true.
     * */
    @Throws(WebDriverException::class)
    suspend fun waitUntil(timeout: Duration, predicate: suspend () -> Boolean): Duration

    ///////////////////////////////////////////////////////////////////
    // Status checking
    //

    /**
     * Returns whether the element exists.
     *
     * ```kotlin
     * driver.exists("h2.title")
     * ```
     *
     * @param selector - The selector of the element to check.
     * @return Whether the element exists.
     * */
    @Throws(WebDriverException::class)
    suspend fun exists(selector: String): Boolean

    /**
     * Returns whether the element is hidden.
     *
     * ```kotlin
     * driver.isHidden("input[name='q']")
     * ```
     *
     * @param selector - The selector of the element to check.
     * @return Whether the element is hidden.
     * */
    @Throws(WebDriverException::class)
    suspend fun isHidden(selector: String): Boolean = !isVisible(selector)

    /**
     * Returns whether the element is visible.
     *
     * ```kotlin
     * driver.isVisible("input[name='q']")
     * ```
     *
     * @param selector - The selector of the element to check.
     * @return Whether the element is visible.
     * */
    @Throws(WebDriverException::class)
    suspend fun isVisible(selector: String): Boolean

    /**
     * Returns whether the element is visible.
     *
     * ```kotlin
     * driver.visible("input[name='q']")
     * ```
     *
     * @param selector - The selector of the element to check.
     * @return Whether the element is visible.
     * */
    @Throws(WebDriverException::class)
    suspend fun visible(selector: String): Boolean = isVisible(selector)

    /**
     * Returns whether the element is checked.
     *
     * ```kotlin
     * driver.isChecked("input[name='agree']")
     * ```
     *
     * @param selector - The selector of the element to check.
     * @return Whether the element is checked.
     * */
    @Throws(WebDriverException::class)
    suspend fun isChecked(selector: String): Boolean

    /////////////////////////////////////////////////
    // Interacts with the Webpage

    /**
     * Brings the browser window to the front.
     *
     * ```kotlin
     * driver.bringToFront()
     * ```
     */
    @Throws(WebDriverException::class)
    suspend fun bringToFront()

    /**
     * This method fetches an element with `selector` and focuses it. If there's no
     * element matching `selector`, nothing to do.
     *
     * ```kotlin
     * driver.focus("input[name='q']")
     * ```
     *
     * @param selector - A [selector](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_Selectors)
     * of an element to focus. If there are multiple elements satisfying the selector, the first will be focused.
     */
    @Throws(WebDriverException::class)
    suspend fun focus(selector: String)

    /**
     * This method emulates inserting text that doesn't come from a key press.
     *
     * ```kotlin
     * driver.type("input[name='q']", "Hello, World!")
     * ```
     *
     * @param selector - A [selector](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_Selectors)
     * of an element to focus. If there are multiple elements satisfying the
     * selector, the first will be focused.
     * @param text The text to insert.
     */
    @Throws(WebDriverException::class)
    suspend fun type(selector: String, text: String)

    /**
     * This method emulates inserting text that doesn't come from a key press.
     *
     * Unlike [type], this method clears the existing value before typing.
     *
     * ```kotlin
     * driver.fill("input[name='q']", "Hello, World!")
     * ```
     *
     * @param selector - A [selector](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_Selectors)
     * of an element to focus, and then fill text into it. If there are multiple elements satisfying the
     * selector, the first will be focused.
     * @param text The text to fill.
     */
    @Throws(WebDriverException::class)
    suspend fun fill(selector: String, text: String)

    /**
     * Shortcut for keyboard down and keyboard up.
     *
     * The key is specified as a string, which can be a single character, a key name, or a combination of both.
     * For example, 'a', 'A', 'KeyA', 'Enter', 'Shift+A', and 'Control+Shift+Tab' are all valid keys.
     *
     * ```kotlin
     * driver.press("input[name='q']", "Enter")
     * ```
     *
     * @param selector - A [selector](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_Selectors)
     * of an element to focus, and then press a key. If there are multiple elements satisfying the
     * selector, the first will be focused.
     * @param key - A key to press. The key can be a single character, a key name, or a combination of both.
     *      See [Code values for keyboard events](https://developer.mozilla.org/en-US/docs/Web/API/UI_Events/Keyboard_event_code_values)
     */
    @Throws(WebDriverException::class)
    suspend fun press(selector: String, key: String)

    /**
     * This method clicks an element with [selector] and focuses it. If there's no
     * element matching `selector`, nothing to do.
     *
     * ```kotlin
     * driver.click("button[type='submit']")
     * ```
     *
     * @param selector - A [selector](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_Selectors)
     * of an element to focus. If there are multiple elements satisfying the
     * selector, the first will be focused.
     * @param count The number of times to click.
     * */
    @Throws(WebDriverException::class)
    suspend fun click(selector: String, count: Int = 1)

    /**
     * This method clicks an element with [selector] whose text content matches [pattern], and then focuses it.
     * If there's no element matching [selector], or the element's text content doesn't match [pattern], nothing to do.
     *
     * ```kotlin
     * driver.clickTextMatches("button", "submit")
     * ```
     *
     * @param selector - A [selector](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_Selectors)
     * of an element to focus. If there are multiple elements satisfying the
     * selector, the first will be focused.
     * @param pattern The pattern to match the text content.
     * @param count The number of times to click.
     * */
    @Throws(WebDriverException::class)
    suspend fun clickTextMatches(selector: String, pattern: String, count: Int = 1)

    /**
     * This method clicks an element with [selector] whose attribute name is [attrName] and value matches [pattern],
     * and then focuses it. If there's no element matching [selector], or the element has no attribute [attrName],
     * or the element's attribute value doesn't match [pattern], nothing to do.
     *
     * ```kotlin
     * driver.clickAttributeMatches("button", "type", "submit")
     * ```
     *
     * @param selector - A [selector](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_Selectors)
     * of an element to focus. If there are multiple elements satisfying the
     * selector, the first will be focused.
     * @param attrName The attribute name to match.
     * @param pattern The pattern to match the text content.
     * @param count The number of times to click.
     * */
    @Throws(WebDriverException::class)
    suspend fun clickMatches(selector: String, attrName: String, pattern: String, count: Int = 1)

    /**
     * Clicks the nth anchor element in the DOM.
     *
     * This function searches for all anchor (`<a>`) elements within the specified root element,
     * and clicks the nth anchor element (0-based index). If the anchor element exists, it returns
     * the `href` attribute of the clicked anchor element. If the element does not exist, it returns null.
     *
     * ```kotlin
     * driver.clickNthAnchor(100, "body")
     * ```
     *
     * @param n The index of the anchor element to click (0-based).
     * @param rootSelector The CSS selector of the root element to search within (default is "body").
     * @return The href attribute of the clicked anchor element, or null if the element does not exist.
     * @throws WebDriverException If an error occurs while interacting with the WebDriver.
     */
    @Throws(WebDriverException::class)
    suspend fun clickNthAnchor(n: Int, rootSelector: String = "body"): String?

    /**
     * This method check an element with [selector]. If there's no element matching [selector], nothing to do.
     *
     * @param selector - A
     * [selector](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_Selectors)
     * of an element to check. If there are multiple elements satisfying the
     * selector, the first will be checked.
     * */
    @Throws(WebDriverException::class)
    suspend fun check(selector: String)

    /**
     * This method uncheck an element with [selector]. If there's no element matching [selector], nothing to do.
     *
     * @param selector - A
     * [selector](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_Selectors)
     * of an element to uncheck. If there are multiple elements satisfying the
     * selector, the first will be focused.
     * */
    @Throws(WebDriverException::class)
    suspend fun uncheck(selector: String)

    /**
     * This method fetches an element with [selector], scrolls it into view if needed. If there's no element matching
     * [selector], the method does nothing.
     *
     * ```kotlin
     * driver.scrollTo("h2.title")
     * ```
     *
     * @param selector - A selector to search for element to scroll to. If there are multiple elements satisfying
     * the [selector], the first will be selected.
     */
    @Throws(WebDriverException::class)
    suspend fun scrollTo(selector: String)

    /**
     * The current page frame scrolls down for [count] times.
     *
     * ```kotlin
     * driver.scrollDown(3)
     * ```
     *
     * @param count The times to scroll down.
     */
    @Throws(WebDriverException::class)
    suspend fun scrollDown(count: Int = 1)

    /**
     * The current page frame scrolls up for [count] times.
     *
     * ```kotlin
     * driver.scrollUp(3)
     * ```
     *
     * @param count The times to scroll up.
     */
    @Throws(WebDriverException::class)
    suspend fun scrollUp(count: Int = 1)

    /**
     * The current page frame scrolls to the top.
     *
     * ```kotlin
     * driver.scrollToTop()
     * ```
     */
    @Throws(WebDriverException::class)
    suspend fun scrollToTop()

    /**
     * The current page frame scrolls to the bottom.
     *
     * ```kotlin
     * driver.scrollToBottom()
     * ```
     */
    @Throws(WebDriverException::class)
    suspend fun scrollToBottom()

    /**
     * The current page frame scrolls to the middle.
     *
     * ```kotlin
     * driver.scrollToMiddle(0.2)
     * driver.scrollToMiddle(0.5)
     * driver.scrollToMiddle(0.8)
     * ```
     *
     * @param ratio The ratio of the page to scroll to, 0.0 means the top, 1.0 means the bottom.
     */
    @Throws(WebDriverException::class)
    suspend fun scrollToMiddle(ratio: Double)

    /**
     * The current page frame scrolls to the middle.
     *
     * ```kotlin
     * driver.scrollToScreen(0.0)
     * driver.scrollToScreen(0.5)
     * driver.scrollToScreen(1.5)
     * driver.scrollToScreen(3.0)
     * ```
     *
     * @param screenNumber The screen number of the page to scroll to (0-based).
     * 0.00 means at the top of the first screen, 1.50 means halfway through the second screen.
     */
    @Throws(WebDriverException::class)
    suspend fun scrollToScreen(screenNumber: Double)

    /**
     * The mouse wheels down for [count] times.
     *
     * ```kotlin
     * driver.mouseWheelDown(3)
     * ```
     *
     * @param count The times to wheel down.
     * @param deltaX The distance to wheel horizontally.
     * @param deltaY The distance to wheel vertically.
     * @param delayMillis The delay time in milliseconds.
     */
    @Throws(WebDriverException::class)
    suspend fun mouseWheelDown(count: Int = 1, deltaX: Double = 0.0, deltaY: Double = 150.0, delayMillis: Long = 0)

    /**
     * The mouse wheels up for [count] times.
     *
     * ```kotlin
     * driver.mouseWheelUp(3)
     * ```
     *
     * @param count The times to wheel up.
     * @param deltaX The distance to wheel horizontally.
     * @param deltaY The distance to wheel vertically.
     * @param delayMillis The delay time in milliseconds.
     */
    @Throws(WebDriverException::class)
    suspend fun mouseWheelUp(count: Int = 1, deltaX: Double = 0.0, deltaY: Double = -150.0, delayMillis: Long = 0)

    /**
     * The mouse moves to the position specified by [x] and [y].
     *
     * ```kotlin
     * driver.moveMouseTo(100.0, 200.0)
     * ```
     *
     * @param x The x coordinate to move to.
     * @param y The y coordinate to move to.
     */
    @Throws(WebDriverException::class)
    suspend fun moveMouseTo(x: Double, y: Double)

    /**
     * The mouse moves to the element with [selector].
     *
     * ```kotlin
     * driver.moveMouseTo("h2.title")
     * ```
     *
     * @param deltaX The distance to the left of the element.
     * @param deltaY The distance to the top of the element.
     */
    @Throws(WebDriverException::class)
    suspend fun moveMouseTo(selector: String, deltaX: Int, deltaY: Int = 0)

    /**
     * Performs a drag, dragenter, dragover, and drop in sequence.
     *
     * @param selector - selector of the element to drag from.
     * @param deltaX The distance to drag horizontally.
     * @param deltaY The distance to drag vertically.
     */
    @Throws(WebDriverException::class)
    suspend fun dragAndDrop(selector: String, deltaX: Int, deltaY: Int = 0)

    /**
     * Returns the document's HTML markup.
     *
     * ```kotlin
     * val html = driver.outerHTML()
     * ```
     *
     * If the document does not exist, returns null.
     *
     * @return The HTML markup of the document.
     * */
    @Throws(WebDriverException::class)
    suspend fun outerHTML(): String?

    /**
     * Returns the node's HTML markup, the node is located by [selector].
     *
     * If the node does not exist, returns null.
     *
     * ```kotlin
     * val html = driver.outerHTML("h2.title")
     * ```
     *
     * @param selector The selector to locate the node.
     * @return The HTML markup of the node.
     * */
    @Throws(WebDriverException::class)
    suspend fun outerHTML(selector: String): String?

    /**
     * Returns the node's text content, the node is located by [selector].
     *
     * If the node does not exist, returns null.
     *
     * ```kotlin
     * val text = driver.selectFirstTextOrNull("h2.title")
     * ```
     *
     * @param selector The selector to locate the node.
     * @return The text content of the node.
     * */
    @Throws(WebDriverException::class)
    suspend fun selectFirstTextOrNull(selector: String): String?

    /**
     * Returns a list of text contents of all the elements matching the specified selector within the page.
     *
     * If no elements match the selector, returns an empty list.
     *
     * ```kotlin
     * val texts = driver.selectTextAll("h2")
     * ```
     *
     * @param selector The selector to locate the nodes.
     * @return The text contents of the nodes.
     * */
    @Throws(WebDriverException::class)
    suspend fun selectTextAll(selector: String): List<String>

    /**
     * Returns the node's attribute value, the node is located by [selector], the attribute is [attrName].
     *
     * If the node does not exist, or the attribute does not exist, returns null.
     *
     * ```kotlin
     * val classes = driver.selectFirstAttributeOrNull("h2.title", "class")
     * ```
     *
     * @param selector The selector to locate the node.
     * @param attrName The attribute name to retrieve.
     * @return The attribute value of the node.
     * */
    @Throws(WebDriverException::class)
    suspend fun selectFirstAttributeOrNull(selector: String, attrName: String): String?

    /**
     * Returns the node's attribute values, the node is located by [selector].
     *
     * If the node do not exist, or the attribute does not exist, returns an empty list.
     *
     * ```kotlin
     * val classes = driver.selectAttributes("h2.title", "class")
     * ```
     *
     * @param selector The selector to locate the nodes.
     * @return The attribute pairs of the nodes.
     * */
    @Throws(WebDriverException::class)
    suspend fun selectAttributes(selector: String): Map<String, String>

    /**
     * Returns the nodes' attribute values, the nodes are located by [selector], the attribute is [attrName].
     *
     * If the nodes do not exist, or the attribute does not exist, returns an empty list.
     *
     * ```kotlin
     * val classes = driver.selectAttributeAll("h2.title", "class")
     * ```
     *
     * @param selector The selector to locate the nodes.
     * @param attrName The attribute name to retrieve.
     * @param start The offset of the first node to select.
     * @param limit The maximum number of nodes to select.
     * @return The attribute values of the nodes.
     * */
    @Throws(WebDriverException::class)
    suspend fun selectAttributeAll(selector: String, attrName: String, start: Int = 0, limit: Int = 10000): List<String>

    /**
     * Set the attribute of an element located by [selector].
     *
     * ```kotlin
     * driver.setAttribute("h2.title", "class", "header")
     * ```
     *
     * @param selector The CSS query to select an element.
     * @param attrName The attribute name to set.
     * @param attrValue The attribute value to set.
     * */
    @Throws(WebDriverException::class)
    suspend fun setAttribute(selector: String, attrName: String, attrValue: String)

    /**
     * Set the attribute of all elements matching the CSS query.
     *
     * ```kotlin
     * driver.setAttributeAll("h2.title", "class", "header")
     * ```
     *
     * @param selector The CSS query to select elements.
     * @param attrName The attribute name to set.
     * @param attrValue The attribute value to set.
     * */
    @Throws(WebDriverException::class)
    suspend fun setAttributeAll(selector: String, attrName: String, attrValue: String)








    /**
     * Returns the node's property value, the node is located by [selector], the property is [attrName].
     *
     * If the node does not exist, or the property does not exist, returns null.
     *
     * ```kotlin
     * val classes = driver.selectFirstPropertyOrNull("input#input", "value")
     * ```
     *
     * @param selector The selector to locate the node.
     * @param propName The property name to retrieve.
     * @return The property value of the node.
     * */
    @Throws(WebDriverException::class)
    suspend fun selectFirstPropertyValueOrNull(selector: String, propName: String): String?

    /**
     * Returns the nodes' property values, the nodes are located by [selector], the property is [propName].
     *
     * If the nodes do not exist, or the property does not exist, returns an empty list.
     *
     * ```kotlin
     * val classes = driver.selectPropertyAll("input#input", "value")
     * ```
     *
     * @param selector The selector to locate the nodes.
     * @param propName The property name to retrieve.
     * @param start The offset of the first node to select.
     * @param limit The maximum number of nodes to select.
     * @return The property values of the nodes.
     * */
    @Throws(WebDriverException::class)
    suspend fun selectPropertyValueAll(selector: String, propName: String, start: Int = 0, limit: Int = 10000): List<String>

    /**
     * Set the property of an element located by [selector].
     *
     * ```kotlin
     * driver.setProperty("input#input", "value")
     * ```
     *
     * @param selector The CSS query to select an element.
     * @param propName The property name to set.
     * @param propValue The property value to set.
     * */
    @Throws(WebDriverException::class)
    suspend fun setProperty(selector: String, propName: String, propValue: String)

    /**
     * Set the property of all elements matching the CSS query.
     *
     * ```kotlin
     * driver.setPropertyAll("input#input", "value")
     * ```
     *
     * @param selector The CSS query to select elements.
     * @param propName The property name to set.
     * @param propValue The property value to set.
     * */
    @Throws(WebDriverException::class)
    suspend fun setPropertyAll(selector: String, propName: String, propValue: String)










    /**
     * Find hyperlinks in elements matching the CSS query.
     *
     * ```kotlin
     * val hyperlinks = driver.selectHyperlinks("a.product-link")
     * ```
     *
     * @param selector The CSS query to select elements.
     * @param offset The offset of the first element to select.
     * @param limit The maximum number of elements to select.
     * @return The hyperlinks in the elements.
     * */
    @Throws(WebDriverException::class)
    suspend fun selectHyperlinks(selector: String, offset: Int = 1, limit: Int = Int.MAX_VALUE): List<Hyperlink>

    /**
     * Find anchor elements matching the CSS query.
     *
     * ```kotlin
     * val anchors = driver.selectAnchors("a.product-link")
     * ```
     *
     * @param selector The CSS query to select elements.
     * @param offset The offset of the first element to select.
     * @param limit The maximum number of elements to select.
     * @return The anchors.
     * */
    @Throws(WebDriverException::class)
    suspend fun selectAnchors(selector: String, offset: Int = 1, limit: Int = Int.MAX_VALUE): List<GeoAnchor>

    /**
     * Find image elements matching the CSS query.
     *
     * ```kotlin
     * val images = driver.selectImages("img.product-image")
     * ```
     *
     * @param selector The CSS query to select elements.
     * @param offset The offset of the first element to select.
     * @param limit The maximum number of elements to select.
     * @return The image URLs.
     * */
    @Throws(WebDriverException::class)
    suspend fun selectImages(selector: String, offset: Int = 1, limit: Int = Int.MAX_VALUE): List<String>

    /**
     * Executes JavaScript in the context of the currently selected frame or window. If the result is not Javascript object,
     * it is not returned.
     *
     * ```kotlin
     * val title = driver.evaluate("document.title")
     * ```
     *
     * Multi-line JavaScript code:
     *
     * ```kotlin
     * val code = """
     * () => {
     *   const a = 10;
     *   const b = 20;
     *   return a * b;
     * }
     * """.trimIndent()
     *
     * val result = driver.evaluate(code)
     * ```
     *
     * ### 🔍 Notes:
     * * **Wrap the code in an IIFE (Immediately Invoked Function Expression)** to return a value.
     * * **Escape line breaks** with `\n`.
     *
     * @param expression Javascript expression to evaluate
     * @return Remote object value in case of primitive values or null.
     * */
    @Throws(WebDriverException::class)
    suspend fun evaluate(expression: String): Any?

    /**
     * Executes JavaScript in the context of the currently selected frame or window. If the result is not Javascript object,
     * it is not returned.
     *
     * ```kotlin
     * val title = driver.evaluate("document.title", "Untitled")
     * ```
     *
     * @param expression Javascript expression to evaluate
     * @return Remote object value in case of primitive values or null.
     * */
    @Throws(WebDriverException::class)
    suspend fun <T> evaluate(expression: String, defaultValue: T): T

    /**
     * Executes JavaScript in the context of the currently selected frame or window. If the result is not Javascript object,
     * it is not returned.
     *
     * ```kotlin
     * val title = driver.evaluate("document.title")
     * ```
     *
     * @param expression Javascript expression to evaluate
     * @return expression result
     * */
    @Beta
    @Throws(WebDriverException::class)
    suspend fun evaluateDetail(expression: String): JsEvaluation?

    /**
     * Executes JavaScript in the context of the currently selected frame or window. If the result is a Javascript object,
     * it is returned as a JSON object.
     *
     * ```kotlin
     * val title = driver.evaluate("document.title")
     * ```
     *
     * To execute multi-line JavaScript code:
     *
     * ```kotlin
     * val code = """
     * () => {
     *   const a = 10;
     *   const b = 20;
     *   return a * b;
     * }
     * """.trimIndent()
     *
     * val result = driver.evaluate(code)
     * ```
     *
     * ### 🔍 Notes:
     * * **Wrap the code in an IIFE (Immediately Invoked Function Expression)** to return a value.
     * * **Escape line breaks** with `\n`.
     *
     * @param expression Javascript expression to evaluate
     * @return Remote object value in case of primitive values or JSON values (if it was requested).
     * */
    @Beta
    @Throws(WebDriverException::class)
    suspend fun evaluateValue(expression: String): Any?

    /**
     * Executes JavaScript in the context of the currently selected frame or window. If the result is a Javascript object,
     * it is returned as a JSON object.
     *
     * ```kotlin
     * val title = driver.evaluate("document.title", "Untitled")
     * ```
     *
     * @param expression Javascript expression to evaluate
     * @return Remote object value in case of primitive values or JSON values (if it was requested).
     * */
    @Beta
    @Throws(WebDriverException::class)
    suspend fun <T> evaluateValue(expression: String, defaultValue: T): T

    /**
     * Executes JavaScript in the context of the currently selected frame or window. If the result is a Javascript object,
     * it is returned as a JSON object.
     *
     * ```kotlin
     * val title = driver.evaluate("document.title")
     * ```
     *
     * @param expression Javascript expression to evaluate
     * @return expression result
     * */
    @Beta
    @Throws(WebDriverException::class)
    suspend fun evaluateValueDetail(expression: String): JsEvaluation?

    /**
     * This method scrolls element into view if needed, and then ake a screenshot of the element.
     *
     * ```kotlin
     * val screenshot = driver.captureScreenshot()
     * val bytes = Base64.getDecoder().decode(screenshot)
     * val path = AppPaths.TMP_DIR.resolve("screenshot.jpg")
     * AppFiles.saveTo(bytes, path, true)
     * ```
     *
     * @return The screenshot of the element in base64 format.
     */
    @Throws(WebDriverException::class)
    suspend fun captureScreenshot(): String?

    /**
     * This method scrolls element into view if needed, and then ake a screenshot of the element.
     *
     * ```kotlin
     * val screenshot = driver.captureScreenshot("h2.title")
     * val bytes = Base64.getDecoder().decode(screenshot)
     * val path = AppPaths.TMP_DIR.resolve("screenshot.jpg")
     * AppFiles.saveTo(bytes, path, true)
     * ```
     *
     * @param selector The selector of the element to capture.
     * @return The screenshot of the element in base64 format.
     */
    @Throws(WebDriverException::class)
    suspend fun captureScreenshot(selector: String): String?

    /**
     * This method scrolls element into view if needed, and then ake a screenshot of the element.
     *
     * @param rect The rectangle of the element to capture.
     * @return The screenshot of the element in base64 format.
     */
    @Throws(WebDriverException::class)
    suspend fun captureScreenshot(rect: RectD): String?

    /**
     * Calculate the clickable point of an element located by [selector].
     * If the element does not exist, or is not clickable, returns null.
     *
     * @param selector The selector of the element to calculate the clickable point.
     * @return The clickable point of the element.
     * */
    @Throws(WebDriverException::class)
    suspend fun clickablePoint(selector: String): PointD?

    /**
     * Return the bounding box of an element located by [selector].
     * If the element does not exist, returns null.
     *
     * @param selector The selector of the element to calculate the bounding box.
     * @return The bounding box of the element.
     * */
    @Throws(WebDriverException::class)
    suspend fun boundingBox(selector: String): RectD?

    /**
     * Create a new Jsoup session with the last page's context, which means, the same headers and cookies.
     *
     * @return The Jsoup session.
     * */
    @Throws(WebDriverException::class)
    suspend fun newJsoupSession(): Connection

    /**
     * Load the url as a resource with Jsoup rather than browser rendering, with the last page's context,
     * which means, the same headers and cookies.
     *
     * @param url The URL to load.
     * @return The Jsoup response.
     * */
    @Throws(WebDriverException::class)
    suspend fun loadJsoupResource(url: String): Connection.Response

    /**
     * Load the url as a resource without browser rendering, with the last page's context, which means, the same headers
     * and cookies.
     *
     * @param url The URL to load.
     * @return The network resource response.
     * */
    @Throws(WebDriverException::class)
    suspend fun loadResource(url: String): NetworkResourceResponse

    /**
     * Delay for a given amount of time.
     *
     * @param millis The amount of time to delay, in milliseconds.
     * */
    suspend fun delay(millis: Long) = kotlinx.coroutines.delay(millis)

    /**
     * Delay for a given amount of time.
     *
     * @param duration The amount of time to delay.
     * */
    suspend fun delay(duration: java.time.Duration) = kotlinx.coroutines.delay(duration.toMillis())

    /**
     * Delay for a given amount of time.
     *
     * @param duration The amount of time to delay.
     * */
    suspend fun delay(duration: kotlin.time.Duration) = kotlinx.coroutines.delay(duration.inWholeMilliseconds)

    /**
     * Force the page pauses all navigations and PENDING resource fetches.
     * If the page loading pauses, the user can still interact with the page,
     * and therefore resources can continue to load.
     * */
    @Throws(WebDriverException::class)
    suspend fun pause()

    /**
     * Force the page stop all navigations and RELEASES all resources. Interaction with the
     * stop page results in undefined behavior and the results should not be trusted.
     *
     * If a web driver stops, it can later be used to visit other pages.
     * */
    @Throws(WebDriverException::class)
    suspend fun stop()

    // Iframe management methods

    /**
     * Switch to iframe by index.
     *
     * @param index The index of the iframe to switch to (0-based)
     * @return true if the switch was successful, false otherwise
     * @throws WebDriverException if an error occurs during frame switching
     */
    @Throws(WebDriverException::class)
    suspend fun switchToFrame(index: Int): Boolean

    /**
     * Switch to iframe by name or ID.
     *
     * @param nameOrId The name or ID attribute of the iframe to switch to
     * @return true if the switch was successful, false otherwise
     * @throws WebDriverException if an error occurs during frame switching
     */
    @Throws(WebDriverException::class)
    suspend fun switchToFrame(nameOrId: String): Boolean

    /**
     * Switch to parent frame.
     *
     * @return true if the switch was successful, false otherwise
     * @throws WebDriverException if an error occurs during frame switching
     */
    @Throws(WebDriverException::class)
    suspend fun switchToParentFrame(): Boolean

    /**
     * Switch to default content (main frame).
     *
     * @return true if the switch was successful, false otherwise
     * @throws WebDriverException if an error occurs during frame switching
     */
    @Throws(WebDriverException::class)
    suspend fun switchToDefaultContent(): Boolean

    /**
     * Get all available frames.
     *
     * @return List of frame information for all available iframes
     * @throws WebDriverException if an error occurs while retrieving frames
     */
    @Throws(WebDriverException::class)
    suspend fun getFrames(): List<FrameInfo>

    /**
     * Get current frame information.
     *
     * @return Frame information for the currently active frame, or null if in main frame
     * @throws WebDriverException if an error occurs while retrieving frame info
     */
    @Throws(WebDriverException::class)
    suspend fun getCurrentFrame(): FrameInfo?

    /**
     * Check if currently in an iframe.
     *
     * @return true if the driver is currently focused on an iframe, false if in main frame
     */
    fun isInFrame(): Boolean

    /**
     * Execute JavaScript in current frame context.
     *
     * @param expression JavaScript expression to evaluate
     * @return The result of the JavaScript evaluation
     * @throws WebDriverException if an error occurs during evaluation
     */
    @Throws(WebDriverException::class)
    suspend fun evaluateInFrame(expression: String): Any?

    /**
     * Wait for frame to load.
     *
     * @param frameId The ID of the frame to wait for
     * @param timeout Maximum time to wait for the frame to load
     * @return true if the frame loaded successfully, false if timeout occurred
     * @throws WebDriverException if an error occurs while waiting
     */
    @Throws(WebDriverException::class)
    suspend fun waitForFrameLoad(frameId: String, timeout: Duration): Boolean
}
