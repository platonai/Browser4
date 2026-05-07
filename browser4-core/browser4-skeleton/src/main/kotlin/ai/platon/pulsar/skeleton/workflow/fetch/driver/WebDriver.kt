package ai.platon.pulsar.skeleton.workflow.fetch.driver

import ai.platon.browser4.driver.chrome.NetworkResourceResponse
import ai.platon.browser4.driver.chrome.NodeRef
import ai.platon.browser4.driver.chrome.dom.model.NanoDOMTree
import ai.platon.browser4.driver.common.BrowserSettings
import ai.platon.pulsar.common.ExperimentalApi
import ai.platon.pulsar.common.ai.llm.MCP
import ai.platon.pulsar.common.serialize.json.Pson
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.common.browser.BrowserType
import ai.platon.pulsar.common.math.geometric.PointD
import ai.platon.pulsar.common.math.geometric.RectD
import ai.platon.pulsar.common.urls.Hyperlink
import ai.platon.pulsar.dom.nodes.GeoAnchor
import ai.platon.pulsar.external.ModelResponse
import com.google.common.annotations.Beta
import org.jsoup.Connection
import java.io.Closeable
import java.time.Duration

/**
 * [WebDriver] defines a concise interface to visit and manipulate webpages. @mcp
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
 * * [navigate]: navigate to a URL.
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
 *    - This read-only property displayed in the browser's address bar is what users see and can edit directly.
 *    - This read-only property can be either navigated or not.
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
 *      driver.press("Space", selector)
 *      "Email".forEach { driver.press("$it", selector) }
 *      driver.press("Enter", selector)
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
     * The driver id, starts with 1. The id is unique in process scope.
     * */
    val id: Int

    /**
     * The parent driver id. The id is unique in process scope.
     * */
    val parentSid: Int

    /**
     * The guid of the driver.
     * */
    val guid: String

    /**
     * The browser of the driver.
     * The browser defines methods and events to manipulate a real browser.
     * */
    val browser: AbstractBrowser

    /**
     * Web pages for the page open from the current page, via window.open(), link click, form submission, etc.
     *
     * TODO: NOT IMPLEMENTED
     * */
    val frames: List<WebDriver>

    /**
     * The driver from whom opens the current page.
     * */
    val opener: WebDriver?

    /**
     * Web pages for the page open from the current page, via window.open(), link click, form submission, etc.
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
     * Adds a script which would be evaluated whenever the page is navigated. @mcp
     *
     * The script is evaluated after the document was created but before any of
     * its scripts were run. This is useful to amend the JavaScript environment, e.g.
     * to seed [Math.random].
     *
     * This function should be invoked before a navigation, usually in an onWillNavigate
     * event handler.
     *
     * @param script JavaScript source code to add.
     * */
    @Throws(WebDriverException::class)
    suspend fun addInitScript(script: String)

    /**
     * Blocks resource URLs from loading. @mcp
     *
     * @param urlPatterns URL patterns to block. Wildcards ('*') are allowed.
     */
    @Throws(WebDriverException::class)
    suspend fun addBlockedURLs(urlPatterns: List<String>)

    /**
     * Opens the specified URL in the web driver. @mcp
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
    @MCP
    suspend fun open(url: String) {
        // Navigates the web driver to the specified URL.
        navigate(url)
        // Waits for the navigation to complete before proceeding.
        waitForNavigation()
    }

    /**
     * Navigates current page to the given URL. @mcp
     *
     * ```kotlin
     * driver.navigate("https://www.example.com")
     * driver.waitForNavigation()
     * ```
     *
     * @param url URL to navigate page to.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun navigate(url: String)

    @Deprecated("Use navigate(url: String) instead", ReplaceWith("navigate(url)"))
    @Throws(WebDriverException::class)
    suspend fun navigateTo(url: String) = navigate(url)

    /**
     * Navigates current page to the given URL. @mcp
     *
     * ```kotlin
     * val entry = NavigateEntry("https://www.example.com?timestamp=11712067353", pageUrl = "https://www.example.com")
     * driver.navigate(entry)
     * driver.waitForNavigation()
     * ```
     *
     * @param entry NavigateEntry to navigate page to.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun navigate(entry: NavigateEntry)

    /**
     * Reloads the current page. @mcp
     *
     * This method should trigger a reload of the current page, similar to how a user would refresh the page in a browser.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun reload()

    /**
     * Navigates the browser to the previous page in the navigation history. @mcp
     *
     * This method is expected to use the browser's navigation history to move back to the previous page.
     * It should handle any exceptions that may occur during the navigation process.
     *
     * @throws WebDriverException If an error occurs while navigating back.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun goBack()

    /**
     * Navigates the browser to the next page in the navigation history. @mcp
     *
     * This method is expected to use the browser's navigation history to move forward to the next page.
     * It should handle any exceptions that may occur during the navigation process.
     *
     * @throws WebDriverException If an error occurs while navigating forward.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun goForward()

    /**
     * Returns a string representing the current URL that the browser is looking at. @mcp
     *
     * The current url is always the main frame's `document.documentURI` if the browser succeed to return it, and is
     * displayed in the browser's address bar.
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
     * see [ai.platon.cdt.kt.protocol.types.page.NavigationEntry.userTypedURL]
     *
     * @return A string containing the URL of the document, or the passed in url to navigate.
     */
    @MCP
    suspend fun currentUrl(): String

    /**
     * The URL read-only property of the Document interface returns the document location as a string. @mcp
     *
     * This property equals to javascript `document.URL`.
     * The `document.URL` property returns the same value. The `document.documentURI` property can be used on
     * any document types, while the `document.URL` property can only be used on HTML documents.
     *
     * @see [Document: URL property](https://developer.mozilla.org/en-US/docs/Web/API/Document/URL)
     *
     * @return A string containing the URL of the document
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun url(): String

    /**
     * Returns the document location as a string. @mcp
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
    @MCP
    suspend fun documentURI(): String

    /**
     * Returns the document's baseURI. @mcp
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
    @MCP
    suspend fun baseURI(): String

    /**
     * The referrer property returns the URI of the page that linked to this page. @mcp
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
    @MCP
    suspend fun referrer(): String

    /**
     * Returns the source of the last loaded page. @mcp
     *
     * If the page has been modified after loading (for
     * example, by JavaScript) there is no guarantee that the returned text is that of the modified
     * page.
     *
     * PageSource and outerHTML:
     *
     * - pageSource: returns document HTML markup, will support non-HTML document
     * - outerHTML: returns document HTML markup, for HTML document only
     *
     * ```kotlin
     * val pageSource = driver.pageSource()
     * ```
     *
     * @return The source of the current page
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun pageSource(): String?

    /**
     * Returns the title of the current page. @mcp
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun title(): String

    /**
     * Retrieves a lightweight version of the DOM tree (NanoDOMTree). @mcp
     *
     * The NanoDOMTree is based on the accessibility tree and enhanced with DOM and document snapshot information.
     * It is designed to be a compact representation suitable for AI processing and analysis.
     *
     * @return A [NanoDOMTree] representing the current page state, or null if retrieval fails.
     * */
    @MCP
    suspend fun nanoDOMTree(): NanoDOMTree?

    /**
     * Interact with an AI model using the context of the element selected by [selector]. @mcp
     *
     * The AI model receives the element's content and structure as context along with the provided prompt.
     *
     * @param prompt The question or instruction for the AI model.
     * @param selector The selector of the element, multiple formats supported. Used as the context element.
     * @return A [ModelResponse] containing the model's answer.
     */
    @MCP
    suspend fun chat(prompt: String, selector: String): ModelResponse

    /**
     * Returns the cookies of the current page. @mcp
     *
     * ```kotlin
     * val cookies = driver.getCookies()
     * ```
     *
     * @return The cookies of the current page.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun getCookies(): List<Map<String, String>>

    /**
     * Deletes browser cookies with matching name and url or domain/path pair. @mcp
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
    @MCP
    suspend fun deleteCookies(name: String, url: String? = null, domain: String? = null, path: String? = null)

    /**
     * Clears browser cookies. @mcp
     *
     * ```kotlin
     * driver.clearBrowserCookies()
     * ```
     *
     * @see Browser.clearCookies
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun clearBrowserCookies()

    /**
     * Wait until the element identified by the selector becomes present in the DOM or timeout. @mcp
     *
     * ```kotlin
     * val remainingTime = driver.waitForSelector("h2.title")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported. The element is waited for until it becomes present.
     * @return The remaining time until timeout when the element becomes present.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun waitForSelector(selector: String): Duration = waitForSelector(selector) {}

    /**
     * Wait until the element identified by the selector becomes present in the DOM or timeout. @mcp
     *
     * ```kotlin
     * val remainingTime = driver.waitForSelector("h2.title", 30000)
     * ```
     *
     * @param selector The selector of the element, multiple formats supported. The element is waited for until it becomes present.
     * @param timeoutMillis The maximum time to wait for the element to become present.
     * @return The remaining time until timeout when the element becomes present.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun waitForSelector(selector: String, timeoutMillis: Long): Long =
        waitForSelector(selector, timeoutMillis) {}

    /**
     * Wait for the element identified by the selector to become present in the DOM, or until timeout. @mcp
     *
     * ```kotlin
     * val remainingTime = driver.waitForSelector("h2.title", Duration.ofSeconds(30))
     * ```
     *
     * @param selector The selector of the element, multiple formats supported. The element is waited for until it becomes present.
     * @param timeout The maximum time to wait for the element to become present.
     * @return The remaining time until timeout when the element becomes present.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun waitForSelector(selector: String, timeout: Duration): Duration = waitForSelector(selector, timeout) {}

    /**
     * Wait for the element identified by the selector to become present in the DOM, or until timeout. @mcp
     *
     * This method periodically checks for the existence of the element. If the element is not found during a check,
     * the action will be executed, such as scrolling the page down.
     *
     * ```kotlin
     * val remainingTime = driver.waitForSelector("h2.title") {
     *  driver.scrollDown()
     * }
     * ```
     *
     * @param selector The selector of the element, multiple formats supported. The element is waited for until it becomes present.
     * @param action The action to execute when the element is not found.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun waitForSelector(selector: String, action: suspend () -> Unit): Duration

    /**
     * Wait for the element identified by the selector to become present in the DOM, or until timeout. @mcp
     *
     * This method periodically checks for the existence of the element. If the element is not found during a check,
     * the action will be executed, such as scrolling the page down.
     *
     * ```kotlin
     * val remainingTime = driver.waitForSelector("h2.title", 30000) {
     *  driver.scrollDown()
     * }
     * ```
     *
     * @param selector The selector of the element, multiple formats supported. The element is waited for until it becomes present.
     * @param timeoutMillis The maximum time to wait for the element to become present.
     * @param action The action to execute when the element is not found.
     * @return The remaining time until timeout when the element becomes present.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun waitForSelector(selector: String, timeoutMillis: Long, action: suspend () -> Unit): Long =
        waitForSelector(selector, Duration.ofMillis(timeoutMillis), action).toMillis()

    /**
     * Wait for the element identified by the selector to become present in the DOM, or until timeout. @mcp
     *
     * This method periodically checks for the existence of the element. If the element is not found during a check,
     * the action will be executed, such as scrolling the page down.
     *
     * ```kotlin
     * val remainingTime = driver.waitForSelector("h2.title", Duration.ofSeconds(30)) {
     *  driver.scrollDown()
     * }
     * ```
     *
     * @param selector The selector of the element, multiple formats supported. The element is waited for until it becomes present.
     * @param timeout The maximum time to wait for the element to become present.
     * @param action The action to execute when the element is not found.
     * @return The remaining time until timeout when the element becomes present.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun waitForSelector(selector: String, timeout: Duration, action: suspend () -> Unit): Duration

    /**
     * Wait until the current url changes or timeout. @mcp
     *
     * ```kotlin
     * val url = "https://www.example.com"
     * driver.navigate(url)
     * var remainingTime = driver.waitForNavigation()
     * if (remainingTime > 0) {
     *   driver.click("a[href='/next']")
     *   remainingTime = driver.waitForNavigation(url)
     * }
     * ```
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun waitForNavigation(oldUrl: String = ""): Duration

    /**
     * Wait until the current url changes or timeout. @mcp
     *
     * ```kotlin
     * val url = "https://www.example.com"
     * driver.navigate(url)
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
    @MCP
    suspend fun waitForNavigation(oldUrl: String = "", timeoutMillis: Long): Long =
        waitForNavigation(oldUrl, Duration.ofMillis(timeoutMillis)).toMillis()

    /**
     * Wait until the current url changes or timeout. @mcp
     *
     * ```kotlin
     * val timeout = Duration.ofSeconds(30)
     * val url = "https://www.example.com"
     * driver.navigate(url)
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
    @MCP
    suspend fun waitForNavigation(oldUrl: String = "", timeout: Duration): Duration

    /**
     * Await navigation to the specified URL page or timeout if necessary. @mcp
     *
     * ```kotlin
     * val newDriver = driver.waitForPage("https://www.example.com", Duration.ofSeconds(30))
     * ```
     *
     * TODO: check if waitForPage and waitForNavigation can be merged into one method.
     *
     * @param url The URL to navigate to.
     * @return The remaining time until timeout when the predicate returns true.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun waitForPage(url: String, timeout: Duration): WebDriver?

    /**
     * Returns when the pageFunction returns a truthy value. @mcp
     *
     * @param pageFunction A JavaScript function to be evaluated in the page context.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun waitForFunction(pageFunction: String, timeout: Duration): WebDriver?

    /**
     * Wait until the predicate returns true. @mcp
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
     * Wait until the predicate returns true. @mcp
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
     * Wait until the predicate returns true. @mcp
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
     * Returns whether the element exists. @mcp
     *
     * ```kotlin
     * driver.exists("h2.title")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported. The element is checked for existence.
     * @return Whether the element exists.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun exists(selector: String): Boolean

    /**
     * Returns whether the element is hidden. @mcp
     *
     * ```kotlin
     * driver.isHidden("input[name='q']")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported. The element is checked for visibility state.
     * @return Whether the element is hidden.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun isHidden(selector: String): Boolean = !isVisible(selector)

    /**
     * Returns whether the element is visible. @mcp
     *
     * ```kotlin
     * driver.isVisible("input[name='q']")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported. The element is checked for visibility state.
     * @return Whether the element is visible.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun isVisible(selector: String): Boolean

    /**
     * Returns whether the element is checked. @mcp
     *
     * ```kotlin
     * driver.isChecked("input[name='agree']")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported. The element is checked for checked state.
     * @return Whether the element is checked.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun isChecked(selector: String): Boolean

    /////////////////////////////////////////////////
    // Interacts with the Webpage

    /**
     * Brings the browser window to the front. @mcp
     *
     * ```kotlin
     * driver.bringToFront()
     * ```
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun bringToFront()

    /**
     * This method hovers over the element. @mcp
     *
     * 1. Scroll the element into view if needed.
     * 2. Move mouse to hover over a random position near the center of the element.
     *
     * ```kotlin
     * driver.hover("h1")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun hover(selector: String)

    /**
     * This method fetches an element with `selector` and focuses it. @mcp
     *
     * If there's no element matching `selector`, nothing to do.
     *
     * ```kotlin
     * driver.focus("input[name='q']")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported. If there are multiple matching
     * elements, the first will be focused.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun focus(selector: String)

    /**
     * This method emulates inserting text that doesn't come from a key press. @mcp
     *
     * ```kotlin
     * driver.type("Hello, World!")
     * driver.type("Hello, World!", "input[name='q']")
     * ```
     *
     * When [selector] is omitted, text is typed into the currently focused element.
     * When [selector] is provided, the matching element is focused first and then receives the text.
     *
     * @param text The text to insert.
     * @param selector The selector of the element, multiple formats supported. Optional. If provided, the matching
     * element is focused before typing. If there are multiple matching elements, the first is used.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun type(text: String, selector: String? = null)

    /**
     * Shortcut for keyboard down and keyboard up. @mcp
     *
     * The key is specified as a string, which can be a single character, a key name, or a combination of both.
     * For example, 'a', 'A', 'KeyA', 'Enter', 'Shift+A', and 'Control+Shift+Tab' are all valid keys.
     *
     * ```kotlin
     * driver.press("Enter")
     * driver.press("Enter", "input[name='q']")
     * ```
     *
     * When [selector] is omitted, the key is pressed on the currently focused element.
     * When [selector] is provided, the matching element is focused first and then receives the key press.
     *
     * @param key A key to press. The key can be a single character, a key name, or a combination of both.
     *      See [Code values for keyboard events](https://developer.mozilla.org/en-US/docs/Web/API/UI_Events/Keyboard_event_code_values)
     * @param selector The selector of the element, multiple formats supported. Optional. If provided, the matching
     * element is focused before pressing the key. If there are multiple matching elements, the first is used.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun press(key: String, selector: String? = null)

    /**
     * This method emulates inserting text that doesn't come from a key press. @mcp
     *
     * Unlike [type], this method clears the existing value before typing.
     *
     * ```kotlin
     * driver.fill("input[name='q']", "Hello, World!")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported. The matching element is focused and
     * then filled with text. If there are multiple matching elements, the first will be focused.
     * @param text The text to fill.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun fill(selector: String, text: String)

    /**
     * Presses and holds a keyboard key on the currently focused element. @mcp
     *
     * ```kotlin
     * driver.keyDown("Shift")
     * driver.keyDown("Control")
     * ```
     *
     * @param key A key to press and hold. The key can be a single character, a key name, or a combination of both.
     *      See [Code values for keyboard events](https://developer.mozilla.org/en-US/docs/Web/API/UI_Events/Keyboard_event_code_values)
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun keyDown(key: String)

    /**
     * Releases a previously pressed keyboard key on the currently focused element. @mcp
     *
     * ```kotlin
     * driver.keyUp("Shift")
     * driver.keyUp("Control")
     * ```
     *
     * @param key A key to release. The key can be a single character, a key name, or a combination of both.
     *      See [Code values for keyboard events](https://developer.mozilla.org/en-US/docs/Web/API/UI_Events/Keyboard_event_code_values)
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun keyUp(key: String)

    /**
     * Focus on an element with [selector] and click it. @mcp
     *
     * If there's no element matching `selector`, nothing to do.
     *
     * ```kotlin
     * driver.click("button[type='submit']")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported. If there are multiple matching
     * elements, the first will be focused.
     * @param count The number of times to click.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun click(selector: String, count: Int = 1)

    /**
     * Focus on an element with [selector] and click it with [modifier] pressed. @mcp
     *
     * @param selector The selector of the element, multiple formats supported. The matching element is clicked.
     * @param modifier The keyboard modifier to press while clicking (e.g., "Shift", "Control", "Alt").
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun click(selector: String, modifier: String)

    /**
     * Focus on an element with [selector] and double-click it. @mcp
     *
     * @param selector The selector of the element, multiple formats supported. The matching element is double-clicked.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun dblclick(selector: String)

    /**
     * Focus on an element with [selector] and double-click it with [modifier] pressed. @mcp
     *
     * @param selector The selector of the element, multiple formats supported. The matching element is double-clicked.
     * @param modifier The keyboard modifier to press while double-clicking.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun dblclick(selector: String, modifier: String)

    /**
     * Resizes the viewport to the specified width and height. @mcp
     * */
    @MCP
    suspend fun resize(width: Int, height: Int)

    /**
     * Accepts the dialog. @mcp
     * */
    @MCP
    suspend fun dialogAccept(promptText: String? = null)

    /**
     * Dismisses the dialog. @mcp
     * */
    @MCP
    suspend fun dialogDismiss()

    /**
     * This method clicks an element with [selector] whose text content matches [pattern], and then focuses it. @mcp
     *
     * If there's no element matching [selector], or the element's text content doesn't match [pattern], nothing to do.
     *
     * ```kotlin
     * driver.clickTextMatches("button", "submit")
     * ```
     *
     * TODO: use playwright style selector which supports text matching, for example: `button:has-text("submit")`
     *
     * @param selector The selector of the element, multiple formats supported. If there are multiple matching
     * elements, the first will be focused.
     * @param pattern The pattern to match the text content.
     * @param count The number of times to click.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun clickTextMatches(selector: String, pattern: String, count: Int = 1)

    /**
     * This method clicks an element with [selector] whose attribute name is [attrName] and value matches [pattern], and then focuses it. @mcp
     *
     * If there's no element matching [selector], or the element has no attribute [attrName],
     * or the element's attribute value doesn't match [pattern], nothing to do.
     *
     * ```kotlin
     * driver.clickMatches("button", "type", "submit")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported. If there are multiple matching
     * elements, the first will be focused.
     * @param attrName The attribute name to match.
     * @param pattern The pattern to match the attribute value.
     * @param count The number of times to click.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun clickMatches(selector: String, attrName: String, pattern: String, count: Int = 1)

    /**
     * Selects one or more options in a <select> element. @mcp
     *
     * @param selector The selector of the element, multiple formats supported.
     * @param values The values or labels of the options to select
     * @return The list of selected option values
     */
    @MCP
    suspend fun selectOption(selector: String, values: List<String>): List<String>

    /**
     * Selects an option in a <select> element. @mcp
     *
     * @param selector The selector of the element, multiple formats supported.
     * @param value The value or label of the option to select
     * @return The selected option value, or null if not found
     */
    @MCP
    suspend fun selectOption(selector: String, value: String): String? {
        val selected = selectOption(selector, listOf(value))
        return selected.firstOrNull()
    }

    /**
     * This method check an element with [selector]. If there's no element matching [selector], nothing to do. @mcp
     *
     * @param selector The selector of the element, multiple formats supported. If there are multiple matching
     * elements, the first will be checked.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun check(selector: String)

    /**
     * This method uncheck an element with [selector]. If there's no element matching [selector], nothing to do. @mcp
     *
     * @param selector The selector of the element, multiple formats supported. If there are multiple matching
     * elements, the first will be unchecked.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun uncheck(selector: String)

    /**
     * This method fetches an element with [selector], scrolls it into view if needed. @mcp
     *
     * If there's no element matching [selector], the method does nothing.
     *
     * ```kotlin
     * driver.scrollTo("h2.title")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported. If there are multiple matching
     * elements, the first will be scrolled into view.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun scrollTo(selector: String): Double

    /**
     * The current page frame scrolls down for [count] times. @mcp
     *
     * ```kotlin
     * driver.scrollDown(3)
     * ```
     *
     * TODO: use mouseWheel instead
     *
     * @param count The times to scroll down.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun scrollDown(count: Int = 1): Double

    /**
     * The current page frame scrolls up for [count] times. @mcp
     *
     * ```kotlin
     * driver.scrollUp(3)
     * ```
     *
     * TODO: use mouseWheel instead
     *
     * @param count The times to scroll up.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun scrollUp(count: Int = 1): Double

    /**
     * Scrolls the current page frame vertically by the given number of pixels. @mcp
     *
     * Positive values scroll down, negative values scroll up. The target position is clamped to the
     * scrollable range [0, scrollHeight - viewportHeight].
     *
     * When [smooth] is true a deterministic, step‑wise scrolling algorithm is used (instead of relying
     * on the browser's native smooth scrolling) for higher stability: it issues incremental
     * `window.scrollTo()` calls and waits for the viewport to settle after each step. This reduces race
     * conditions with lazy‑loaded content or async layout changes and yields more predictable final
     * positions.
     *
     * Typical usage:
     * ```kotlin
     * // Scroll down 200px smoothly (default)
     * driver.scrollBy()
     * // Scroll up 500px immediately
     * driver.scrollBy(-500.0, smooth = false)
     * // Scroll down one viewport height, smooth
     * val y = driver.scrollBy(driver.viewportHeight(), smooth = true)
     * ```
     *
     * @param pixels The delta in pixels to scroll; positive scrolls down, negative scrolls up. Default 200.0.
     * @param smooth Whether to perform a deterministic smooth scrolling sequence. If false a single
     *               `window.scrollTo()` is executed.
     * @return The final vertical scroll offset (window.scrollY) after scrolling.
     * @throws WebDriverException If the underlying browser interaction fails.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun scrollBy(pixels: Double = 200.0, smooth: Boolean = true): Double

    /**
     * The current page frame scrolls to the top. @mcp
     *
     * ```kotlin
     * driver.scrollToTop()
     * ```
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun scrollToTop(): Double

    /**
     * The current page frame scrolls to the bottom. @mcp
     *
     * ```kotlin
     * driver.scrollToBottom()
     * ```
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun scrollToBottom(): Double

    /**
     * The current page frame scrolls to the middle. @mcp
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
    @MCP
    suspend fun scrollToMiddle(ratio: Double): Double

    /**
     * Scroll to the 2.5th viewport position. @mcp
     *
     * ```kotlin
     * driver.scrollToViewport(1.0)
     * driver.scrollToViewport(1.5)
     * driver.scrollToViewport(2.5)
     * driver.scrollToViewport(3.0)
     * ```
     *
     * @param n The viewport number of the page to scroll to (1-based).
     * 1.00 means at the top of the first screen, 2.50 means halfway through the second screen.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun scrollToViewport(n: Double, smooth: Boolean = true): Double

    /**
     * The mouse wheels down for [count] times. @mcp
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
    @Deprecated("Use mouseWheel(deltaX, deltaY) instead", replaceWith = ReplaceWith("mouseWheel(deltaX, deltaY)"))
    @Throws(WebDriverException::class)
    @MCP
    suspend fun mouseWheelDown(count: Int = 1, deltaX: Double = 0.0, deltaY: Double = 150.0, delayMillis: Long = 0)

    /**
     * The mouse wheels up for [count] times. @mcp
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
    @Deprecated("Use mouseWheel(deltaX, deltaY) instead", replaceWith = ReplaceWith("mouseWheel(deltaX, deltaY)"))
    @Throws(WebDriverException::class)
    @MCP
    suspend fun mouseWheelUp(count: Int = 1, deltaX: Double = 0.0, deltaY: Double = -150.0, delayMillis: Long = 0)

    /**
     * Scrolls the mouse wheel by the provided deltas. @mcp
     *
     * Positive [deltaY] scrolls down and negative [deltaY] scrolls up.
     *
     * ```kotlin
     * driver.mouseWheel(0.0, 150.0)
     * driver.mouseWheel(0.0, -150.0)
     * ```
     *
     * @param deltaX The distance to wheel horizontally.
     * @param deltaY The distance to wheel vertically.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun mouseWheel(deltaX: Double = 0.0, deltaY: Double = 150.0)

    /**
     * The mouse moves to the position specified by [x] and [y]. @mcp
     *
     * ```kotlin
     * driver.moveMouseTo(100.0, 200.0)
     * ```
     *
     * @param x The x coordinate to move to.
     * @param y The y coordinate to move to.
     */
    @Deprecated("Use mouseMove instead", replaceWith = ReplaceWith("mouseMove(x, y)"))
    @Throws(WebDriverException::class)
    @MCP
    suspend fun moveMouseTo(x: Double, y: Double)

    /**
     * Moves the mouse to the position specified by [x] and [y]. @mcp
     *
     * ```kotlin
     * driver.mouseMove(100.0, 200.0)
     * ```
     *
     * @param x The x coordinate to move to.
     * @param y The y coordinate to move to.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun mouseMove(x: Double, y: Double)

    /**
     * Presses a mouse button at the current mouse position. @mcp
     *
     * ```kotlin
     * driver.mouseDown()
     * driver.mouseDown("right")
     * ```
     *
     * @param button The mouse button to press. Supported values are `left`, `middle`, and `right`.
     * @param clickCount The click count associated with the press event.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun mouseDown(button: String = "left", clickCount: Int = 1)

//    @Throws(WebDriverException::class)
//    @MCP
//    suspend fun mouseDown(x: Double, y: Double, button: String = "left", modifier: String? = null)

    /**
     * Releases a mouse button at the current mouse position. @mcp
     *
     * ```kotlin
     * driver.mouseUp()
     * driver.mouseUp("right")
     * ```
     *
     * @param button The mouse button to release. Supported values are `left`, `middle`, and `right`.
     * @param clickCount The click count associated with the release event.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun mouseUp(button: String = "left", clickCount: Int = 1)

//    @Throws(WebDriverException::class)
//    @MCP
//    suspend fun mouseUp(x: Double, y: Double, button: String = "left", modifier: String? = null)

    /**
     * The mouse moves to the element with [selector]. @mcp
     *
     * ```kotlin
     * driver.moveMouseTo("h2.title")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported.
     * @param deltaX The distance to the left of the element.
     * @param deltaY The distance to the top of the element.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun moveMouseTo(selector: String, deltaX: Int, deltaY: Int = 0)

    /**
     * Performs a drag, dragenter, dragover, and drop in sequence. @mcp
     *
     * @param selector The selector of the element, multiple formats supported. The matching element is dragged from.
     * @param deltaX The distance to drag horizontally.
     * @param deltaY The distance to drag vertically.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun dragAndDrop(selector: String, deltaX: Int, deltaY: Int = 0)


    /**
     * Drags the element identified by [sourceSelector] onto the element identified by [targetSelector]. @mcp
     *
     * The implementation dispatches an HTML5 drag sequence between the source and target elements using a shared
     * `DataTransfer` payload so selector-to-selector drag flows can be asserted reliably by automation clients.
     *
     * @param sourceSelector The selector of the element, multiple formats supported. Identifies the drag source.
     * @param targetSelector The selector of the element, multiple formats supported. Identifies the drop target.
     */
    @MCP
    suspend fun drag(sourceSelector: String, targetSelector: String) {
        val encodedSource = Pson.toJson(sourceSelector)
        val encodedTarget = Pson.toJson(targetSelector)
        val script = """
            (() => {
                const source = document.querySelector($encodedSource);
                const target = document.querySelector($encodedTarget);
                if (!source || !target) {
                    return JSON.stringify({
                        ok: false,
                        error: !source && !target
                            ? 'Source and target elements were not found'
                            : !source
                                ? 'Source element was not found'
                                : 'Target element was not found'
                    });
                }
                if (typeof DataTransfer === 'undefined' || typeof DragEvent === 'undefined') {
                    return JSON.stringify({
                        ok: false,
                        error: 'HTML5 drag-and-drop APIs are not available in the current page context'
                    });
                }

                const sourceRect = source.getBoundingClientRect();
                const targetRect = target.getBoundingClientRect();
                const sourceX = Math.round(sourceRect.left + sourceRect.width / 2);
                const sourceY = Math.round(sourceRect.top + sourceRect.height / 2);
                const targetX = Math.round(targetRect.left + targetRect.width / 2);
                const targetY = Math.round(targetRect.top + targetRect.height / 2);
                const dataTransfer = new DataTransfer();

                const fire = (element, type, clientX, clientY) => {
                    const event = new DragEvent(type, {
                        bubbles: true,
                        cancelable: true,
                        composed: true,
                        dataTransfer,
                        clientX,
                        clientY
                    });
                    element.dispatchEvent(event);
                };

                fire(source, 'dragstart', sourceX, sourceY);
                fire(target, 'dragenter', targetX, targetY);
                fire(target, 'dragover', targetX, targetY);
                fire(target, 'drop', targetX, targetY);
                fire(source, 'dragend', targetX, targetY);

                return JSON.stringify({ ok: true });
            })()
        """.trimIndent()
        val result = evaluate(script) as? String
            ?: """{"ok":false,"error":"Failed to execute drag script"}"""
        val parsed = pulsarObjectMapper().readTree(result)
        if (!parsed.path("ok").asBoolean(false)) {
            val error = parsed.path("error").asText("Unknown drag failure")
            throw WebDriverException(
                "Failed to drag '$sourceSelector' to '$targetSelector': $error",
                driver = this
            )
        }
    }

    /**
     * Returns the document's HTML markup. @mcp
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
    @MCP
    suspend fun outerHTML(): String?

    /**
     * Returns the node's HTML markup, the node is located by [selector]. @mcp
     *
     * If the node does not exist, returns null.
     *
     * ```kotlin
     * val html = driver.outerHTML("h2.title")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported.
     * @return The HTML markup of the node.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun outerHTML(selector: String): String?

    /**
     * Returns the text content of the document or a specific element. @mcp
     *
     * If [selector] is null, returns the text content of the entire document (usually `document.body.innerText`).
     * If [selector] is provided, returns the text content of the first matching element.
     *
     * If the document or element does not exist, returns null.
     *
     * ```kotlin
     * val bodyText = driver.textContent()
     * val titleText = driver.textContent("h1.title")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported. If null, targets the document body.
     * @return The text content, or null if not found.
     * */
    @MCP
    suspend fun textContent(selector: String? = null): String?

    /**
     * Extracts multiple fields from the page using CSS selectors. @mcp
     *
     * @param fields A map where keys are field names and values are CSS selectors.
     * @return A map containing the extracted text content for each field. Values are null if the selector matches nothing.
     * */
    suspend fun extract(fields: Map<String, String>): Map<String, String?>

    /**
     * Use querySelectorAll to get all elements matched by [selector], and then return their NodeRefs. @mcp
     *
     * Note that the NodeRefs may become stale after certain operations, so they should be used immediately after selection.
     *
     * @param selector The selector of the element, multiple formats supported. Supported selector types include CSS
     * selector, XPath, and backend node id. The type is determined by the selector prefix, e.g. "css:div" for CSS
     * selector, "xpath://div" for XPath, and "e123" for backendNodeId. If no prefix is provided, CSS selector is
     * used by default.
     * @return a list of NodeRefs for the matched elements, or an empty list if no elements are matched or an error occurs.
     * */
    @Beta
    @Throws(WebDriverException::class)
    suspend fun querySelectorAll(selector: String): List<NodeRef>

    /**
     * Returns the node's text content, the node is located by [selector]. @mcp
     *
     * If the node does not exist, returns null.
     *
     * ```kotlin
     * val text = driver.selectFirstTextOrNull("h2.title")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported.
     * @return The text content of the node.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun selectFirstTextOrNull(selector: String): String?

    /**
     * Returns a list of text contents of all the elements matching the specified selector within the page. @mcp
     *
     * If no elements match the selector, returns an empty list.
     *
     * ```kotlin
     * val texts = driver.selectTextAll("h2")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported.
     * @return The text contents of the nodes.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun selectTextAll(selector: String): List<String>

    /**
     * Returns the node's attribute value, the node is located by [selector], the attribute is [attrName]. @mcp
     *
     * If the node does not exist, or the attribute does not exist, returns null.
     *
     * ```kotlin
     * val classes = driver.selectFirstAttributeOrNull("h2.title", "class")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported.
     * @param attrName The attribute name to retrieve.
     * @return The attribute value of the node.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun selectFirstAttributeOrNull(selector: String, attrName: String): String?

    /**
     * Returns the node's attribute values, the node is located by [selector]. @mcp
     *
     * If the node do not exist, or the attribute does not exist, returns an empty list.
     *
     * ```kotlin
     * val classes = driver.selectAttributes("h2.title", "class")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported.
     * @return The attribute pairs of the nodes.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun selectAttributes(selector: String): Map<String, String>

    /**
     * Returns the nodes' attribute values, the nodes are located by [selector], the attribute is [attrName]. @mcp
     *
     * If the nodes do not exist, or the attribute does not exist, returns an empty list.
     *
     * ```kotlin
     * val classes = driver.selectAttributeAll("h2.title", "class")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported.
     * @param attrName The attribute name to retrieve.
     * @param start The offset of the first node to select.
     * @param limit The maximum number of nodes to select.
     * @return The attribute values of the nodes.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun selectAttributeAll(selector: String, attrName: String, start: Int = 0, limit: Int = 10000): List<String>

    /**
     * Set the attribute of an element located by [selector]. @mcp
     *
     * ```kotlin
     * driver.setAttribute("h2.title", "class", "header")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported.
     * @param attrName The attribute name to set.
     * @param attrValue The attribute value to set.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun setAttribute(selector: String, attrName: String, attrValue: String)

    /**
     * Set the attribute of all elements matching [selector]. @mcp
     *
     * ```kotlin
     * driver.setAttributeAll("h2.title", "class", "header")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported.
     * @param attrName The attribute name to set.
     * @param attrValue The attribute value to set.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun setAttributeAll(selector: String, attrName: String, attrValue: String)


    /**
     * Returns the node's property value, the node is located by [selector], the property is [propName]. @mcp
     *
     * If the node does not exist, or the property does not exist, returns null.
     *
     * ```kotlin
     * val classes = driver.selectFirstPropertyOrNull("input#input", "value")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported.
     * @param propName The property name to retrieve.
     * @return The property value of the node.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun selectFirstPropertyValueOrNull(selector: String, propName: String): String?

    /**
     * Returns the nodes' property values, the nodes are located by [selector], the property is [propName]. @mcp
     *
     * If the nodes do not exist, or the property does not exist, returns an empty list.
     *
     * ```kotlin
     * val classes = driver.selectPropertyAll("input#input", "value")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported.
     * @param propName The property name to retrieve.
     * @param start The offset of the first node to select.
     * @param limit The maximum number of nodes to select.
     * @return The property values of the nodes.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun selectPropertyValueAll(
        selector: String,
        propName: String,
        start: Int = 0,
        limit: Int = 10000
    ): List<String>

    /**
     * Set the property of an element located by [selector]. @mcp
     *
     * ```kotlin
     * driver.setProperty("input#input", "value")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported.
     * @param propName The property name to set.
     * @param propValue The property value to set.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun setProperty(selector: String, propName: String, propValue: String)

    /**
     * Set the property of all elements matching [selector]. @mcp
     *
     * ```kotlin
     * driver.setPropertyAll("input#input", "value")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported.
     * @param propName The property name to set.
     * @param propValue The property value to set.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun setPropertyAll(selector: String, propName: String, propValue: String)

    /**
     * Find hyperlinks in elements matching [selector].
     *
     * ```kotlin
     * val hyperlinks = driver.selectHyperlinks("a.product-link")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported.
     * @param offset The offset of the first element to select.
     * @param limit The maximum number of elements to select.
     * @return The hyperlinks in the elements.
     * */
    @Throws(WebDriverException::class)
    suspend fun selectHyperlinks(selector: String, offset: Int = 1, limit: Int = Int.MAX_VALUE): List<Hyperlink>

    /**
     * Find anchor elements matching [selector].
     *
     * ```kotlin
     * val anchors = driver.selectAnchors("a.product-link")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported.
     * @param offset The offset of the first element to select.
     * @param limit The maximum number of elements to select.
     * @return The anchors.
     * */
    @Throws(WebDriverException::class)
    suspend fun selectAnchors(selector: String, offset: Int = 1, limit: Int = Int.MAX_VALUE): List<GeoAnchor>

    /**
     * Find image elements matching [selector].
     *
     * ```kotlin
     * val images = driver.selectImages("img.product-image")
     * ```
     *
     * @param selector The selector of the element, multiple formats supported.
     * @param offset The offset of the first element to select.
     * @param limit The maximum number of elements to select.
     * @return The image URLs.
     * */
    @Throws(WebDriverException::class)
    suspend fun selectImages(selector: String, offset: Int = 1, limit: Int = Int.MAX_VALUE): List<String>

    /**
     * Executes JavaScript in the context of the currently selected frame or window. @mcp
     *
     * If the result is not JavaScript object, it is not returned.
     *
     * If you want to execute a function, convert it to IIFE (Immediately Invoked Function Expression).
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
     * @param expression JavaScript expression to evaluate
     * @return Remote object value in case of primitive values or null.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun evaluate(expression: String): Any?

    /**
     * Executes JavaScript and returns the result, or [defaultValue] if the result is null or incompatible. @mcp
     *
     * @param expression The JavaScript expression to evaluate.
     * @param defaultValue The value to return if evaluation fails or returns null.
     * @return The evaluation result or [defaultValue].
     */
    @Throws(WebDriverException::class)
    suspend fun <T> evaluate(expression: String, defaultValue: T): T

    /**
     * returns detailed evaluation metadata (beta). @mcp
     *
     * @param expression The JavaScript expression to evaluate.
     * @return A [JsEvaluation] object containing the result and metadata.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun evaluateDetail(expression: String): JsEvaluation?

    /**
     * Executes JavaScript and returns the result as a JSON-serializable value. @mcp
     *
     * If the result is an object, it is serialized to a Map or List.
     * If the result is a primitive, it is returned as is.
     * If the result is null or undefined, null is returned.
     *
     * @param expression The JavaScript expression to evaluate.
     * @return The result as a JSON-compatible object (Map, List, String, Number, Boolean, or null).
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun evaluateValue(expression: String): Any?

    /**
     * Executes JavaScript and returns the result as a JSON-serializable value, or [defaultValue] if null/incompatible. @mcp
     *
     * @param expression The JavaScript expression to evaluate.
     * @param defaultValue The value to return if evaluation fails or returns null.
     * @return The evaluation result or [defaultValue].
     * */
    @Throws(WebDriverException::class)
    suspend fun <T> evaluateValue(expression: String, defaultValue: T): T

    /**
     * Returns detailed value evaluation metadata (beta). @mcp
     *
     * @param expression The JavaScript expression to evaluate.
     * @return A [JsEvaluation] object containing the result value and metadata.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun evaluateValueDetail(expression: String): JsEvaluation?

    /**
     * Executes JavaScript for the element located by [selector] and returns the result as a JSON-serializable value. @mcp
     *
     * @param selector The selector of the element, multiple formats supported.
     * @param functionDeclaration The JavaScript function declaration to execute against the matched element.
     * @return The result as a JSON-compatible object (Map, List, String, Number, Boolean, or null).
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun evaluateValue(selector: String, functionDeclaration: String): Any?

    /**
     * Returns detailed value evaluation metadata for the element located by [selector]. @mcp
     *
     * @param selector The selector of the element, multiple formats supported.
     * @param functionDeclaration The JavaScript function declaration to execute against the matched element.
     * @return A [JsEvaluation] object containing the result value and metadata.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun evaluateValueDetail(selector: String, functionDeclaration: String): JsEvaluation?

    /**
     * Capture a screenshot of the current viewport (or primary browsing context) after ensuring any pending layout. @mcp
     *
     * If the backend supports element-centric capture this may represent the full page; implementation specific.
     *
     * The target element (if any) is scrolled into view before capture.
     *
     * ```kotlin
     * val base64 = driver.screenshot()
     * val bytes = Base64.getDecoder().decode(base64)
     * ```
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun screenshot(fullPage: Boolean = false): String?

    /**
     * Scroll the element matched by [selector] into view (if needed) then take a screenshot of that element's bounding box. @mcp
     *
     * Returns a Base64 encoded image (implementation usually PNG/JPEG), PNG by default.
     *
     * @param selector The selector of the element, multiple formats supported.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun screenshot(selector: String): String?

    /**
     * Take a screenshot of the rectangle specified by [rect] in the current page coordinate space. @mcp
     *
     * Caller is responsible for ensuring the rectangle is visible or scrolled into view if the implementation requires it.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun screenshot(rect: RectD): String?

    @Deprecated("Use screenshot(fullPage = true) instead", ReplaceWith("screenshot(fullPage = true)"))
    suspend fun captureScreenshot(fullPage: Boolean = false): String? = screenshot(fullPage)

    @Deprecated("Use screenshot(selector) instead", ReplaceWith("screenshot(selector)"))
    suspend fun captureScreenshot(selector: String): String? = screenshot(selector)

    @Deprecated("Use screenshot(rect) instead", ReplaceWith("screenshot(rect)"))
    suspend fun captureScreenshot(rect: RectD): String? = screenshot(rect)

    @Throws(WebDriverException::class)
    @MCP
    suspend fun ariaSnapshot(): String

    /**
     * Return the ARIA snapshot (accessibility tree in YAML format) for the specified viewports. @mcp
     *
     * The [viewports] parameter controls which viewport(s) to include:
     * - `"all"` — return the full-page snapshot (default).
     * - `"3"` — return only viewport 3.
     * - `"1,3,5"` — return viewports 1, 3 and 5.
     * - `"2-4"` — return viewports 2, 3 and 4.
     *
     * Viewport indices are 1-based. The viewport height is determined by the browser's
     * current viewport dimensions.
     *
     * @param viewports A viewport specification string (e.g., `"3"`, `"1,3,5"`, `"2-4"`, `"all"`).
     * @return The ARIA snapshot YAML covering only the requested viewports.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun ariaSnapshot(viewports: String): String = ariaSnapshot()

    /**
     * Calculate the clickable point of an element located by [selector]. @mcp
     *
     * If the element does not exist, or is not clickable, returns null.
     *
     * @param selector The selector of the element, multiple formats supported. Used to calculate the clickable point.
     * @return The clickable point of the element.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun clickablePoint(selector: String): PointD?

    /**
     * Return the bounding box of an element located by [selector]. @mcp
     *
     * If the element does not exist, returns null.
     *
     * @param selector The selector of the element, multiple formats supported. Used to calculate the bounding box.
     * @return The bounding box of the element.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun boundingBox(selector: String): RectD?

    /**
     * Create a new Jsoup session with the last page's context, which means, the same headers and cookies. @mcp
     *
     * @return The Jsoup session.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun newJsoupSession(): Connection

    /**
     * Load the url as a resource with Jsoup rather than browser rendering, with the last page's context. @mcp
     *
     * This means, the same headers and cookies.
     *
     * @param url The URL to load.
     * @return The Jsoup response.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun loadJsoupResource(url: String): Connection.Response

    /**
     * Load the url as a resource without browser rendering, with the last page's context. @mcp
     *
     * This means, the same headers and cookies.
     *
     * @param url The URL to load.
     * @return The network resource response.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun loadResource(url: String): NetworkResourceResponse

    /**
     * Delay for a given amount of time. @mcp
     *
     * @param millis The amount of time to delay, in milliseconds.
     * */
    @MCP
    suspend fun delay(millis: Long = 1000) = kotlinx.coroutines.delay(millis)

    /**
     * Delay for a given amount of time. @mcp
     *
     * @param duration The amount of time to delay.
     * */
    @MCP
    suspend fun delay(duration: Duration) = kotlinx.coroutines.delay(duration.toMillis())

    /**
     * Delay for a given amount of time. @mcp
     *
     * @param duration The amount of time to delay.
     * */
    @MCP
    suspend fun delay(duration: kotlin.time.Duration) = kotlinx.coroutines.delay(duration.inWholeMilliseconds)

    /**
     * Upload files to the element located by [selector]. @mcp
     *
     * @param selector The selector of the element, multiple formats supported. It should resolve to the file input element.
     * @param paths The list of file paths to upload.
     */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun upload(selector: String, paths: List<String>)

    /**
     * Force the page pauses all navigations and PENDING resource fetches. @mcp
     *
     * If the page loading pauses, the user can still interact with the page,
     * and therefore resources can continue to load.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun pause()

    /**
     * Force the page stop all navigations and RELEASES all resources. @mcp
     *
     * Interaction with the stop page results in undefined behavior and the results should not be trusted.
     *
     * If a web driver stops, it can later be used to visit other pages.
     * */
    @Throws(WebDriverException::class)
    @MCP
    suspend fun stop()
}
