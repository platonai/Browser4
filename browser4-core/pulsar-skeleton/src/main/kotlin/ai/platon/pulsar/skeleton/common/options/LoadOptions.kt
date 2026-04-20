package ai.platon.pulsar.skeleton.common.options

import ai.platon.browser4.driver.common.InteractSettings
import ai.platon.pulsar.common.DateTimes
import ai.platon.pulsar.common.Priority13
import ai.platon.pulsar.common.browser.InteractLevel
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.config.Params
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.dom.select.appendSelectorIfMissing
import ai.platon.pulsar.persist.metadata.FetchMode
import ai.platon.pulsar.skeleton.common.ApiPublic
import ai.platon.pulsar.skeleton.event.PageEventHandlers
import ai.platon.pulsar.skeleton.event.impl.PageEventHandlersFactory
import com.beust.jcommander.Parameter
import com.google.common.annotations.Beta
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.jvm.kotlinProperty

/**
 * Load options define the parameters controlling how web pages are fetched, processed, and stored.
 *
 * These options can be specified as command-line style parameters and parsed into a [LoadOptions] object.
 * They control aspects such as caching behavior, page interaction, content requirements, and more.
 *
 * Examples:
 *
 * ```kotlin
 * // Parse a string into a LoadOptions object
 * val options = session.options('-expires 1d -itemExpires 1d -ignoreFailure -parse -storeContent')
 *
 * // Fetch after 1 day since last fetch
 * session.load('https://www.jd.com', '-expires 1d')
 *
 * // Force immediate fetch regardless of cache
 * session.load('https://www.jd.com', '-refresh')
 *
 * // Don't fetch after specified deadline
 * session.load('https://www.jd.com', '-deadline 2022-04-15T18:36:54.941Z')
 *
 * // Enable parsing phase
 * session.load('https://www.jd.com', '-parse')
 *
 * // Store page content in storage
 * session.load('https://www.jd.com', '-storeContent')
 * ```
 */
open class LoadOptions(
    argv: Array<String>,
    var conf: VolatileConfig,
    var rawEvent: PageEventHandlers? = null,
    var rawItemEvent: PageEventHandlers? = null,
    var referrer: String? = null,
) : PulsarOptions(argv) {


    /**
     * Represents the type of content being crawled, such as an article, product, or hotel.
     * This is used for classifying and applying specialized processing to different content types.
     */
    @ApiPublic
    @Parameter(
        names = ["-e", "-entity", "--entity"],
        description = "Specifies the entity type of the page. This is optional."
    )
    var entity = ""

    /**
     * A label to categorize tasks into logical groups.
     * Useful for organizing related crawl tasks and enabling easier filtering or querying of results.
     */
    @ApiPublic
    @Parameter(
        names = ["-l", "-label", "--label"],
        description = "An optional label to group tasks logically."
    )
    var label = ""

    /**
     * A unique identifier for distinguishing between separate tasks.
     * This helps in tracking and managing individual crawl operations within the system.
     */
    @ApiPublic
    @Parameter(
        names = ["-taskId", "--task-id"],
        description = "An optional task ID to differentiate tasks."
    )
    var taskId = ""

    /**
     * A timestamp used to group related tasks into a batch.
     * Initialized to Instant.EPOCH for consistency between parsing and string conversion operations.
     * This is useful for identifying tasks that belong to the same execution window.
     */
    @ApiPublic
    @Parameter(
        names = ["-taskTime", "--task-time"], converter = InstantConverter::class,
        description = "An optional timestamp to denote a batch of tasks."
    )
    var taskTime = Instant.EPOCH

    /**
     * Absolute deadline after which the task should be discarded.
     *
     * If the current time exceeds this deadline, the task will be immediately abandoned.
     * Used to prevent processing of stale or irrelevant tasks.
     */
    @ApiPublic
    @Parameter(
        names = ["-deadline", "--deadline"], converter = InstantConverter::class,
        description = "The task's deadline indicates the time by which it should be completed. If this deadline is surpassed, " +
            " the task must be promptly discarded."
    )
    var deadline = DateTimes.doomsday

    /**
     * Authentication token for authorized access to protected resources.
     * Can be used to access restricted content or APIs requiring authentication.
     */
    @ApiPublic
    @Parameter(
        names = ["-authToken", "--auth-token"],
        description = "The auth token, can be used for authorization purpose."
    )
    var authToken = ""

    /**
     * When enabled, ensures the crawler operates in a non-destructive mode.
     *
     * Prevents modifications to the target page during interactions, making the crawl
     * operation completely passive without side effects on the target site.
     */
    @ApiPublic
    @Parameter(
        names = ["-readonly"],
        description = "Specify whether the load execution is read-only or not. " +
            "When a load execution is read-only, it ensures that the webpage loaded remains unchanged by the execution."
    )
    var readonly = false

    /**
     * When true, fetches the URL as a basic resource without browser rendering.
     *
     * Useful for simple file downloads, APIs, or static content where browser
     * rendering and JavaScript execution aren't necessary.
     */
    @ApiPublic
    @Parameter(
        names = ["-resource", "-isResource"],
        description = "If true, fetch the url as a resource without browser rendering."
    )
    var isResource = false

    /**
     * Determines task execution priority in the crawl queue.
     *
     * Lower numerical values indicate higher priority (consistent with [java.util.concurrent.PriorityBlockingQueue]).
     * Values outside the valid range defined by [Priority13] will be adjusted to the nearest valid value.
     *
     * Priority can be specified in multiple ways:
     * 1. In the URL: `http://example.com -priority -2000`
     * 2. In args: `Hyperlink("http://example.com", "", args = "-priority -2000")`
     * 3. In LoadOptions: `session.load("http://example.com", options.apply { priority = -2000 })`
     * 4. In UrlAware: `Hyperlink("http://example.com", "", priority = -2000)`
     *
     * When normalizing URLs, priorities are resolved in this order:
     * 1. Priority in the URL
     * 2. Priority in the args
     * 3. Priority in the options
     *
     * @see Priority13
     * @see Priority13.NORMAL
     */
    @ApiPublic
    @Parameter(
        names = ["-p", "-priority"],
        description = "Represents the priority of a task, determining the order of execution"
    )
    var priority = 0

    /**
     * Duration after which cached content is considered stale and should be refetched.
     *
     * Controls the crawler's caching behavior by specifying how long fetched content remains valid.
     * When the duration since last fetch exceeds this value, the page will be fetched again.
     *
     * Supports two time format standards:
     * - ISO-8601 duration format: PnDTnHnMn.nS
     * - Hadoop time duration format: 100s, 1m, 1h, 1d (units: ns, us, ms, s, m, h, d)
     */
    @ApiPublic
    @Parameter(
        names = ["-i", "-expire", "-expires", "--expire"], converter = DurationConverter::class,
        description = "The expiry duration. " +
            "If the expiry time is exceeded, the page should be fetched from the Internet."
    )
    var expires = LoadOptionDefaults.expires

    /**
     * Absolute timestamp after which cached content should be refetched.
     *
     * Provides an explicit expiration point rather than a relative duration.
     * When current time exceeds this value, the page will be fetched again regardless of when it was last fetched.
     *
     * Supports two time format standards:
     * - ISO-8601 duration format: PnDTnHnMn.nS
     * - Hadoop time duration format: 100s, 1m, 1h, 1d (units: ns, us, ms, s, m, h, d)
     */
    @ApiPublic
    @Parameter(
        names = ["-expireAt", "--expire-at"], converter = InstantConverter::class,
        description = "The expiry time point. " +
            "If the expiry time is exceeded, the page should be fetched from the Internet."
    )
    var expireAt = LoadOptionDefaults.expireAt

    /**
     * CSS selector used to identify and extract links from portal/index pages.
     *
     * Specifies which elements containing links should be extracted for subsequent crawling.
     * If not specified, default link extraction behavior will be used.
     */
    @ApiPublic
    @Parameter(
        names = ["-ol", "-outLink", "-outLinkSelector", "--out-link-selector", "-outlink", "-outlinkSelector", "--outlink-selector"],
        description = "The selector to extract links in portal pages."
    )
    var outLinkSelector = ""

    /**
     * Regular expression pattern to filter extracted outlinks.
     *
     * Only links matching this pattern will be followed after extraction.
     * Default pattern (.+) matches all URLs.
     */
    @ApiPublic
    @Parameter(
        names = ["-olp", "-outLinkPattern", "--out-link-pattern"],
        description = "The pattern to select out links in the portal page"
    )
    var outLinkPattern = ".+"

    /**
     * The index of the iframe to focus on during page interaction.
     *
     * Used to target specific iframes within complex pages for content extraction.
     * TODO: This feature is planned but not fully implemented yet.
     */
    @ApiPublic
    @Parameter(names = ["-ifr", "-iframe", "--iframe"], description = "The iframe id to switch to")
    @Beta
    var iframe = 0

    /**
     * Maximum number of outlinks to extract and follow from a single page.
     *
     * Limits the number of child URLs to be processed from portal/index pages
     * to prevent excessive crawling depth or breadth.
     */
    @ApiPublic
    @Parameter(
        names = ["-tl", "-topLinks", "--top-links"],
        description = "Specify how many links to extract for out pages."
    )
    var topLinks = 20

    /**
     * CSS selector for an element that must contain non-blank text before proceeding.
     *
     * The crawler will wait until the specified element contains text or until a timeout occurs.
     * Useful for ensuring that dynamic content has properly loaded before processing the page.
     */
    @ApiPublic
    @Parameter(
        names = ["-wnb", "-waitNonBlank", "--wait-non-blank"],
        description = "The selector specified element should have a non-blank text"
    )
    @Beta
    var waitNonBlank: String = ""

    /**
     * CSS selector for an element that must contain non-blank text for valid retrieval.
     *
     * If the specified element's text is empty or blank, the page fetch will be considered
     * unsuccessful and retried. Used to validate that critical content is present.
     */
    @ApiPublic
    @Parameter(
        names = ["-rnb", "-requireNotBlank"],
        description = "The selector specified element should have a non-blank text"
    )
    @Beta
    var requireNotBlank: String = ""

    /**
     * Minimum acceptable page size in bytes to consider the fetch successful.
     *
     * Pages smaller than this threshold will be treated as incomplete and refetched.
     * Helps detect truncated downloads or error pages that are abnormally small.
     */
    @ApiPublic
    @Parameter(
        names = ["-rs", "-requireSize", "--require-size"],
        description = "The minimum page size expected"
    )
    var requireSize = 0

    /**
     * Minimum number of images required for a page to be considered complete.
     *
     * Pages with fewer images than this threshold will be refetched.
     * Useful for ensuring media-rich content has properly loaded.
     */
    @ApiPublic
    @Parameter(
        names = ["-ri", "-requireImages", "--require-images"],
        description = "The minimum number of images expected in the page"
    )
    var requireImages = 0

    /**
     * Minimum number of anchor (link) elements required for a page to be considered complete.
     *
     * Pages with fewer links than this threshold will be refetched.
     * Useful for validating that navigational elements have properly loaded.
     */
    @ApiPublic
    @Parameter(
        names = ["-ra", "-requireAnchors", "--require-anchors"],
        description = "The minimum number of anchors expected in the page"
    )
    var requireAnchors = 0

    /**
     * Specifies the mechanism used to fetch web content.
     *
     * Currently, only BROWSER mode is fully supported, which uses a headless browser
     * for rendering and JavaScript execution during content retrieval.
     */
    @Parameter(
        names = ["-fm", "-fetchMode", "--fetch-mode"], converter = FetchModeConverter::class,
        description = "The fetch mode"
    )
    var fetchMode = FetchMode.BROWSER

    /**
     * Specifies which browser engine to use for rendering pages.
     *
     * Google Chrome is the default and recommended browser engine.
     * NOTE: Session-scope browser selection is not yet fully supported.
     */
    @Parameter(
        names = ["-b", "-browser", "--browser"], converter = BrowserTypeConverter::class,
        description = "Specify which browser to use, google chrome is the default"
    )
    var browser = LoadOptionDefaults.browser

    /**
     * Number of times to scroll down the page after initial load.
     *
     * Controls how aggressively the crawler attempts to load additional content
     * by simulating scroll events. Higher values trigger more dynamic content loading.
     */
    @Parameter(
        names = ["-sc", "-autoScrollCount", "--auto-scroll-count", "-scrollCount", "--scroll-count"],
        description = "The count to scroll down after a page being opened in a browser"
    )
    var autoScrollCount = InteractSettings.DEFAULT.autoScrollCount

    /**
     * Time interval between successive scroll actions.
     *
     * Controls the pace of scrolling to allow time for content to load between scrolls.
     * Shorter intervals may miss content that takes longer to load, while longer intervals
     * increase overall crawl time.
     */
    @Parameter(
        names = ["-si", "-scrollInterval", "--scroll-interval"], converter = DurationConverter::class,
        description = "The interval to scroll down after a page being opened in a browser"
    )
    var scrollInterval = InteractSettings.DEFAULT.scrollInterval

    /**
     * Maximum time allowed for injected JavaScript execution before timeout.
     *
     * Limits how long custom scripts can run during page interaction to prevent
     * hanging on problematic scripts.
     */
    @Parameter(
        names = ["-stt", "-scriptTimeout", "--script-timeout"], converter = DurationConverter::class,
        description = "The maximum time to perform javascript injected into the browser"
    )
    var scriptTimeout = InteractSettings.DEFAULT.scriptTimeout

    /**
     * Maximum time to wait for page loading to complete.
     *
     * Controls how long the crawler waits for a page to fully load before considering
     * it ready for processing or timing out if loading takes too long.
     */
    @Parameter(
        names = ["-plt", "-pageLoadTimeout", "--page-load-timeout"], converter = DurationConverter::class,
        description = "The maximum time to wait for a page to finish"
    )
    var pageLoadTimeout = InteractSettings.DEFAULT.pageLoadTimeout

    /**
     * Browser to use specifically for item detail pages (as opposed to index pages).
     *
     * Allows using different browser configurations for detail pages vs. index pages.
     * NOTE: Session-scope browser selection is not yet fully supported.
     */
    @Parameter(
        names = ["-ib", "-itemBrowser", "--item-browser"], converter = BrowserTypeConverter::class,
        description = "The browser used to visit the item pages"
    )
    var itemBrowser = LoadOptionDefaults.browser

    /**
     * Cache expiration duration specifically for item detail pages.
     *
     * Controls how long item detail pages remain valid in the cache before requiring refresh.
     * Works identically to [expires] but applies only to item pages, not index pages.
     */
    @ApiPublic
    @Parameter(
        names = ["-ii", "-itemExpire", "-itemExpires", "--item-expires"], converter = DurationConverter::class,
        description = "The same as expires, but only works for item pages"
    )
    var itemExpires = ChronoUnit.DECADES.duration

    /**
     * Absolute timestamp after which item detail pages should be refetched.
     *
     * Works identically to [expireAt] but applies only to item pages, not index pages.
     * Provides a fixed point in time after which cached item pages are considered invalid.
     */
    @ApiPublic
    @Parameter(
        names = ["-itemExpireAt", "--item-expire-at"], converter = InstantConverter::class,
        description = "If an item page is expired, it should be fetched from the web again"
    )
    var itemExpireAt = DateTimes.doomsday

    /**
     * Number of scroll actions for item detail pages.
     *
     * Works identically to [autoScrollCount] but applies only to item pages.
     * Controls how thoroughly dynamic content is loaded on item detail pages.
     */
    @Parameter(
        names = ["-isc", "-itemScrollCount", "--item-scroll-count"],
        description = "The same as scrollCount, but only works for item pages"
    )
    var itemScrollCount = autoScrollCount

    /**
     * Time interval between scrolls for item detail pages.
     *
     * Works identically to [scrollInterval] but applies only to item pages.
     * Controls the pace of content loading on item detail pages.
     */
    @Parameter(
        names = ["-isi", "-itemScrollInterval", "--item-scroll-interval"], converter = DurationConverter::class,
        description = "The same as scrollInterval, but only works for item pages"
    )
    var itemScrollInterval = scrollInterval

    /**
     * JavaScript execution timeout for item detail pages.
     *
     * Works identically to [scriptTimeout] but applies only to item pages.
     * Controls how long scripts can run when processing item detail pages.
     */
    @Parameter(
        names = ["-ist", "-itemScriptTimeout", "--item-script-timeout"], converter = DurationConverter::class,
        description = "The same as scriptTimeout, but only works for item pages"
    )
    var itemScriptTimeout = scriptTimeout

    /**
     * Page load timeout for item detail pages.
     *
     * Works identically to [pageLoadTimeout] but applies only to item pages.
     * Controls maximum wait time for item detail pages to load.
     */
    @Parameter(
        names = ["-iplt", "-itemPageLoadTimeout", "--item-page-load-timeout"], converter = DurationConverter::class,
        description = "The same as pageLoadTimeout, but only works for item pages"
    )
    var itemPageLoadTimeout = pageLoadTimeout

    /**
     * CSS selector for non-blank text validation on item detail pages.
     *
     * Works identically to [waitNonBlank] but applies only to item pages.
     * Makes the crawler wait until the specified element contains text.
     */
    @ApiPublic
    @Parameter(
        names = ["-iwnb", "-itemWaitNonBlank", "--item-wait-non-blank"],
        description = "The selector specified element should have a non-blank text"
    )
    var itemWaitNonBlank: String = ""

    /**
     * CSS selector for content validation on item detail pages.
     *
     * Works identically to [requireNotBlank] but applies only to item pages.
     * Pages without text in this selector will be considered invalid and refetched.
     */
    @ApiPublic
    @Parameter(
        names = ["-irnb", "-itemRequireNotBlank", "--item-require-not-blank"],
        description = "Re-fetch the item pages if the required text is blank"
    )
    @Beta
    var itemRequireNotBlank = ""

    /**
     * Minimum page size required for item detail pages.
     *
     * Works identically to [requireSize] but applies only to item pages.
     * Item pages smaller than this threshold (in bytes) will be refetched.
     */
    @ApiPublic
    @Parameter(
        names = ["-irs", "-itemRequireSize", "--item-require-size"],
        description = "Re-fetch item pages smaller than requireSize"
    )
    var itemRequireSize = 0

    /**
     * Minimum number of images required for item detail pages.
     *
     * Works identically to [requireImages] but applies only to item pages.
     * Item pages with fewer images than this threshold will be refetched.
     */
    @ApiPublic
    @Parameter(
        names = ["-iri", "-itemRequireImages", "--item-require-images"],
        description = "Re-fetch item pages who's images is less than requireImages"
    )
    var itemRequireImages = 0

    /**
     * Minimum number of links required for item detail pages.
     *
     * Works identically to [requireAnchors] but applies only to item pages.
     * Item pages with fewer links than this threshold will be refetched.
     */
    @ApiPublic
    @Parameter(
        names = ["-ira", "-itemRequireAnchors", "--item-require-anchors"],
        description = "Re-fetch item pages who's anchors is less than requireAnchors"
    )
    var itemRequireAnchors = 0

    /**
     * Controls whether fetched pages are immediately persisted to storage.
     *
     * When enabled, pages are saved to the database as soon as they're fetched.
     * Disabling this can improve performance for tasks where persistence isn't needed.
     */
    @Parameter(
        names = ["-persist", "--persist"], arity = 1,
        description = "Persist fetched pages as soon as possible"
    )
    var persist = true

    /**
     * Controls whether page content (HTML) is stored in the database.
     *
     * The page content is typically the largest part of a page record. Disabling storage
     * can significantly reduce database size when only metadata is needed.
     */
    @Parameter(
        names = ["-sct", "-storeContent", "--store-content"], arity = 1,
        description = "If false, do not persist the page content which is usually very large."
    )
    var storeContent = LoadOptionDefaults.storeContent

    /**
     * When enabled, page content (HTML) will not be stored in the database.
     *
     * This is the inverse of [storeContent] and takes precedence when both are specified.
     * Useful for saving storage space when only page metadata is needed.
     *
     * Example:
     *
     * ```kotlin
     * session.load(url, "-dropContent")
     *
     * val options = session.options("-dropContent")
     * session.load(url, options)
     * ```
     */
    @Parameter(
        names = ["-dropContent", "--drop-content"],
        description = "If the option exists, do not persist the page content which is usually very large."
    )
    var dropContent = false

    /**
     * Forces an immediate fetch of the page, ignoring cache state and past failures.
     *
     * Acts as a shorthand for setting multiple options:
     * - Sets expires to 0s (immediate expiration)
     * - Enables ignoreFailure
     * - Resets fetch retry counters
     *
     * Equivalent to clicking refresh in a browser - fetches the latest version regardless of cache.
     */
    @ApiPublic
    @Parameter(
        names = ["-refresh", "--refresh"],
        description = "Refresh the fetch state of a page, clear the retry counters." +
            " If true, the page should be fetched immediately." +
            " The option can be explained as follows:" +
            " -refresh = -ignoreFailure -i 0s and set page.fetchRetries = 0"
    )
    var refresh = false
        set(value) {
            field = doRefresh(value)
        }

    /**
     * Attempts to fetch a page even if previous fetch attempts have failed.
     *
     * By default, pages that have failed to fetch will be skipped until refresh is called.
     * This option overrides that behavior and retries failed pages.
     */
    @ApiPublic
    @Parameter(
        names = ["-ignF", "-ignoreFailure", "--ignore-failure"],
        description = "Retry fetching the page even if it's failed last time"
    )
    var ignoreFailure = LoadOptionDefaults.ignoreFailure

    /**
     * Maximum number of fetch retries before marking a page as permanently failed.
     *
     * Once a page has been retried this many times without success, it will be considered
     * "gone" and won't be attempted again unless explicitly refreshed.
     */
    @Parameter(
        names = ["-nmr", "-nMaxRetry", "--n-max-retry"],
        description = "Retry to fetch at most n times, if page.fetchRetries > nMaxRetry," +
            " the page is marked as gone and do not fetch it again until -refresh is set to clear page.fetchRetries"
    )
    var nMaxRetry = 3

    /**
     * Maximum number of immediate retries during a single fetch operation.
     *
     * When a RETRY status code (1601) is encountered, the system will retry
     * immediately up to this many times before giving up on the current attempt.
     */
    @Parameter(
        names = ["-njr", "-nJitRetry", "--n-jit-retry"],
        description = "Retry at most n times at fetch phase immediately if RETRY(1601) code return"
    )
    var nJitRetry = LoadOptionDefaults.nJitRetry

    /**
     * Controls when pages are flushed to the database.
     *
     * When enabled, page writes are batched and delayed for better performance.
     * When disabled, pages are written immediately for better data safety.
     */
    @Parameter(
        names = ["-lazyFlush", "--lazy-flush"],
        description = "If false, pages are flushed into database as soon as possible"
    )
    var lazyFlush = LoadOptionDefaults.lazyFlush

    /**
     * Enables browser incognito/private mode for page fetching.
     *
     * NOTE: This setting has limited effect since browsers always run in
     * temporary contexts in the current implementation.
     */
    @Beta
    @Parameter(names = ["-ic", "-incognito", "--incognito"], description = "Run browser in incognito mode")
    var incognito = false

    /**
     * Enables immediate parsing of fetched pages.
     *
     * When enabled, pages are parsed into a [ai.platon.pulsar.dom.FeaturedDocument] as soon as they're fetched,
     * extracting links and other structured data. This is typically enabled for crawler operations.
     */
    @Parameter(names = ["-ps", "-parse", "--parse"], description = "If true, parse the page when it's just be fetched.")
    var parse = LoadOptionDefaults.parse

    /**
     * Removes query parameters from URLs during processing.
     *
     * When enabled, query strings (parameters after '?') are stripped from URLs.
     * Useful for treating URLs with different query parameters as the same resource.
     */
    @Parameter(
        names = ["-ignoreUrlQuery", "--ignore-url-query"],
        description = "Remove the query parameters in the url"
    )
    var ignoreUrlQuery = false

    /**
     * Disables URL normalization during link processing.
     *
     * When enabled, extracted links are used exactly as found without normalization.
     * This can lead to duplicate URLs in different formats being treated as distinct.
     */
    @Parameter(
        names = ["-noNorm", "--no-link-normalizer"],
        description = "If true, no normalizer will be applied when normalize urls."
    )
    var noNorm = false

    /**
     * Controls the level of interaction with web pages during crawling.
     *
     * This setting balances content quality against performance:
     * - Higher levels: More thorough interaction (scrolling, waiting, etc.) produces better content
     *   extraction but slower performance
     * - Lower levels: Minimal interaction for faster crawling but potentially missing dynamic content
     *
     * This is the primary setting for controlling how aggressively the crawler
     * interacts with pages to discover content.
     *
     * @see InteractLevel
     * @see InteractSettings
     */
    @Parameter(
        names = ["-ilv", "-interactLevel", "--interact-level"],
        converter = InteractLevelConverter::class,
        description = "Specifies the interaction level with the page (higher = better data, lower = faster)."
    )
    var interactLevel = InteractLevel.DEFAULT

    /**
     * Enables test mode with various verbosity levels.
     *
     * Higher values produce more detailed logging and output.
     * Set to 0 to disable test mode completely.
     */
    @Parameter(
        names = ["-test", "--test"],
        description = "The test level, 0 to disable, we will talk more in test mode"
    )
    var test = LoadOptionDefaults.test

    /**
     * Version identifier for the load options format.
     *
     * Used to track compatibility between different versions of the load options parser.
     */
    @Parameter(names = ["-v", "-version", "--version"], description = "The load option version")
    var version = "20220918"

    /**
     * Returns the outLinkSelector if it's non-blank, or null otherwise.
     *
     * Provides a convenient way to check if an outlink selector has been specified.
     */
    val outLinkSelectorOrNull
        get() = outLinkSelector.takeIf { it.isNotBlank() }

    /**
     * Returns the page event handlers, initializing them if needed.
     *
     * Ensures event handlers are available for the main page processing.
     */
    val eventHandlers: PageEventHandlers get() = enableEventHandlers()

    /**
     * Returns the item event handlers, initializing them if needed.
     *
     * Ensures event handlers are available for item page processing.
     */
    val itemEventHandlers: PageEventHandlers get() = enableItemEventHandlers()

    /**
     * Returns parameters that have been modified from their defaults.
     *
     * Useful for showing only the non-default options that are in effect.
     */
    open val modifiedParams: Params
        get() {
            val rowFormat = "%40s: %s"
            val fields = LoadOptions::class.java.declaredFields
            return fields.filter { it.annotations.any { it is Parameter } && !isDefault(it.name) }
                .onEach { it.isAccessible = true }
                .filter { it.get(this) != null }
                .associate { "-${it.name}" to it.get(this) }
                .let { Params.of(it).withRowFormat(rowFormat) }
        }

    /**
     * Returns a map of option names to their modified values.
     *
     * Includes only options that differ from their defaults.
     */
    open val modifiedOptions: Map<String, Any>
        get() {
            val fields = LoadOptions::class.java.declaredFields
            return fields.filter { it.annotations.any { it is Parameter } && !isDefault(it.name) }
                .onEach { it.isAccessible = true }
                .filter { it.get(this) != null }
                .associate { it.name to it.get(this) }
        }

    /**
     * Additional constructor that accepts a string of arguments.
     */
    protected constructor(args: String, conf: VolatileConfig) : this(split(args), conf)

    /**
     * Additional constructor that copies settings from another LoadOptions instance.
     */
    protected constructor(args: String, other: LoadOptions) :
        this(split(args), other.conf, other.rawEvent, other.rawItemEvent, other.referrer)

    /**
     * Checks if the parser phase should be activated based on current settings.
     *
     * Returns true if either explicit parsing is enabled or if content validation
     * requiring parsing is requested.
     */
    fun parserEngaged() = parse || requireNotBlank.isNotBlank()

    /**
     * Parses command-line arguments into this LoadOptions object.
     *
     * In addition to standard parameter parsing, it also:
     * 1. Fixes special handling for zero-arity boolean parameters
     * 2. Corrects the outLinkSelector format
     *
     * @return true if parsing was successful
     */
    override fun parse(): Boolean {
        val b = super.parse()
        if (b) {
            // fix zero-arity boolean parameter overwriting
            optionFields
                .filter { arity0BooleanParams.contains("-${it.name}") }
                .filter { argv.contains("-${it.name}") }
                .forEach {
                    it.isAccessible = true
                    it.set(this, true)
                }
            // fix out link parsing (remove surrounding symbols)
            outLinkSelector = correctOutLinkSelector() ?: ""
        }
        return b
    }

    /**
     * Creates a new LoadOptions instance specifically for item page processing.
     *
     * The new instance applies item-specific options to the main options for
     * processing detail pages rather than index pages.
     *
     * @return a new LoadOptions instance optimized for item page processing
     */
    open fun createItemOptions(): LoadOptions {
        val itemOptions = clone()
        itemOptions.itemOptions2MajorOptions()

        itemOptions.rawEvent = rawItemEvent

        return itemOptions
    }

    /**
     * Determines if a page should be considered expired based on its last fetch time.
     *
     * A page is considered expired when any of these conditions is true:
     * 1. The refresh flag is set (forcing immediate refresh)
     * 2. Current time is after expireAt and the last fetch was before expireAt
     * 3. The time since last fetch exceeds the expires duration
     *
     * @param prevFetchTime when the page was last fetched
     * @return true if the page should be fetched again
     */
    fun isExpired(prevFetchTime: Instant): Boolean {
        val now = Instant.now()
        return when {
            refresh -> true
            expireAt in prevFetchTime..now -> true
            now >= prevFetchTime + expires -> true
            else -> false
        }
    }

    /**
     * Checks if the task's deadline has passed and it should be abandoned.
     *
     * @return true if the current time is after the deadline
     */
    fun isDead(): Boolean {
        return deadline < Instant.now()
    }

    /**
     * Converts item-specific options to main options for item page processing.
     *
     * This method is called when transitioning from processing index pages to
     * detail pages, applying the item-specific settings as the new main settings.
     */
    open fun itemOptions2MajorOptions() {
        // Apply item options to major options
        expires = itemExpires
        autoScrollCount = itemScrollCount
        scriptTimeout = itemScriptTimeout
        scrollInterval = itemScrollInterval
        pageLoadTimeout = itemPageLoadTimeout
        waitNonBlank = itemWaitNonBlank
        requireNotBlank = itemRequireNotBlank
        requireSize = itemRequireSize
        requireImages = itemRequireImages
        requireAnchors = itemRequireAnchors
        browser = itemBrowser

        // Only for portal pages
        outLinkSelector = DEFAULT.outLinkSelector

        // No further item pages
        itemExpires = DEFAULT.itemExpires
        itemScrollCount = DEFAULT.itemScrollCount
        itemScriptTimeout = DEFAULT.itemScriptTimeout
        itemScrollInterval = DEFAULT.itemScrollInterval
        itemPageLoadTimeout = DEFAULT.itemPageLoadTimeout
        itemWaitNonBlank = DEFAULT.itemWaitNonBlank
        itemRequireNotBlank = DEFAULT.itemRequireNotBlank
        itemRequireSize = DEFAULT.itemRequireSize
        itemRequireImages = DEFAULT.itemRequireImages
        itemRequireAnchors = DEFAULT.itemRequireAnchors
        itemBrowser = DEFAULT.itemBrowser

        rawEvent = rawItemEvent
        rawItemEvent = null
    }

    /**
     * Copies option values to the configuration object for use by other components.
     *
     * @return the updated configuration object
     */
    fun overrideConfiguration() = overrideConfiguration(this.conf)

    /**
     * Copies option values to the provided configuration object for use by other components.
     *
     * Since LoadOptions isn't globally accessible, this method transfers settings to
     * a VolatileConfig that can be passed to other modules.
     *
     * @param conf the configuration to update
     * @return the updated configuration object
     */
    fun overrideConfiguration(conf: VolatileConfig?): VolatileConfig? {
        setInteractionSettings()

        if (conf != null) {
            rawEvent?.let { conf.putBean(it) }
            conf.setEnum(CapabilityTypes.BROWSER_TYPE, browser)
            // incognito mode is never used because the browsers are always running in temporary contexts
            conf.setBoolean(CapabilityTypes.BROWSER_INCOGNITO, incognito)
        }

        return conf
    }

    /**
     * Configures interaction settings based on the current options.
     *
     * Updates the interaction behavior configuration when any interaction-related
     * options have been modified from their defaults.
     */
    private fun setInteractionSettings() {
        val modified = listOf(
            "interactLevel",
            "scrollCount",
            "scrollInterval",
            "scriptTimeout",
            "pageLoadTimeout"
        ).any { !isDefault(it) }

        if (!modified) {
            return
        }

        val interactSettings = InteractSettings.create(interactLevel)

        if (!isDefault("scrollCount")) interactSettings.autoScrollCount = autoScrollCount
        if (!isDefault("scrollInterval")) interactSettings.scrollInterval = scrollInterval
        if (!isDefault("scriptTimeout")) interactSettings.scriptTimeout = scriptTimeout
        if (!isDefault("pageLoadTimeout")) interactSettings.pageLoadTimeout = pageLoadTimeout

        interactSettings.overrideConfiguration(conf)
    }

    /**
     * Checks if an option has its default value.
     *
     * @param optionName the option name without leading dash
     * @return true if the option has its default value
     */
    open fun isDefault(optionName: String): Boolean {
         val value = optionFieldsMap[optionName]?.also { it.isAccessible = true }?.get(this) ?: return false
        return value == defaultParams[optionName]
    }

    /**
     * Converts this LoadOptions to a Params object containing all options.
     *
     * @return a Params object representing all options
     */
    override fun getParams(): Params {
        val rowFormat = "%40s: %s"
        return optionFields.filter { it.annotations.any { it is Parameter } }
            .onEach { it.isAccessible = true }
            .associate { "-${it.name}" to it.get(this) }
            .filter { it.value != null }
            .let { Params.of(it).withRowFormat(rowFormat) }
    }

    /**
     * Converts this LoadOptions to a normalized command-line string.
     *
     * Only includes options that differ from defaults. The resulting string
     * can be parsed back into an equivalent LoadOptions object.
     *
     * @return a normalized command-line string representing these options
     */
    override fun toString(): String {
        return modifiedParams.distinct().sorted()
            .withCmdLineStyle(true)
            .withKVDelimiter(" ")
            .withDistinctBooleanParams(arity1BooleanParams)
            .formatAsLine().replace("\\s+".toRegex(), " ")
    }

    /**
     * Compares this LoadOptions with another object for equality.
     *
     * Two LoadOptions are considered equal if their normalized string
     * representations are identical.
     *
     * @param other the object to compare with
     * @return true if the objects are equal
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true;
        }

        return other is LoadOptions && other.toString() == toString()
    }

    /**
     * Returns a hash code for this LoadOptions.
     *
     * @return the hash code
     */
    override fun hashCode(): Int {
        return super.hashCode()
    }

    /**
     * Creates a copy of this LoadOptions with the same settings.
     *
     * @return a new LoadOptions instance with the same settings
     */
    open fun clone() = parse(toString(), this)

    /**
     * Corrects the outLinkSelector format by removing quotes and ensuring proper format.
     *
     * Handles a JCommander bug with quoted options and ensures the selector
     * has the correct form with an "a" tag if needed.
     *
     * @return the corrected selector or null if blank
     */
    private fun correctOutLinkSelector(): String? {
        return outLinkSelector.trim('"')
            .takeIf { it.isNotBlank() }
            ?.let { appendSelectorIfMissing(it, "a") }
    }

    /**
     * Implements the refresh action when the refresh flag is set.
     *
     * Sets expired/expireAt to force immediate refresh and enables ignoreFailure
     * to retry even failed pages.
     *
     * @param value the new refresh flag value
     * @return the refresh flag value
     */
    private fun doRefresh(value: Boolean): Boolean {
        if (value) {
            expires = Duration.ZERO
            expireAt = Instant.now()

            itemExpires = Duration.ZERO
            itemExpireAt = Instant.now()

            ignoreFailure = true
        }
        return value
    }

    /**
     * Initializes and returns the page event handlers.
     *
     * Creates event handlers if they don't already exist.
     *
     * @return the page event handlers
     */
    private fun enableEventHandlers(): PageEventHandlers {
        val eh = rawEvent ?: PageEventHandlersFactory(conf).create()
        rawEvent = eh
        return eh
    }

    /**
     * Initializes and returns the item page event handlers.
     *
     * Creates item event handlers if they don't already exist.
     *
     * @return the item page event handlers
     */
    private fun enableItemEventHandlers(): PageEventHandlers {
        val eh = rawEvent ?: PageEventHandlersFactory(conf).create()
        rawItemEvent = eh
        return eh
    }

    companion object {
        /**
         * Default LoadOptions instance with standard settings.
         * Used as a base for comparison and default values.
         */
        val DEFAULT = LoadOptions("", VolatileConfig.UNSAFE)

        /**
         * List of all option fields in the LoadOptions class.
         * Contains fields with Parameter annotations for option parsing.
         */
        val optionFields = LoadOptions::class.java.declaredFields
            .asSequence()
            .onEach { it.isAccessible = true }
            .filter { it.annotations.filterIsInstance<Parameter>().isNotEmpty() }
            .onEach {
                val name = it.name
                val count = it.annotations.filterIsInstance<Parameter>().count { it.names.contains("-$name") }
                require(count > 0) {
                    "Missing -$name option for field <$name>. " +
                        "Every option with name `optionName` has to take a [Parameter] name [-optionName]."
                }
            }

        /**
         * Map of field names to their corresponding Field objects.
         */
        val optionFieldsMap = optionFields.associateBy { it.name }

        /**
         * Map of field names to their default values from DEFAULT instance.
         */
        val defaultParams = optionFields.associate { it.name to it.get(DEFAULT) }

        /**
         * Map of default option names and values.
         */
        val defaultArgsMap = DEFAULT.toArgsMap()

        /**
         * List of zero-arity boolean parameter names.
         * These are parameters that don't require a value (flag parameters).
         */
        val arity0BooleanParams = optionFields
            .onEach { it.isAccessible = true }
            .filter { it.get(DEFAULT) is Boolean }
            .flatMap { it.annotations.toList() }
            .filterIsInstance<Parameter>()
            .filter { it.arity < 1 }
            .flatMap { it.names.toList() }
            .toList()

        /**
         * List of single-arity boolean parameter names.
         * These are boolean parameters that require a value.
         */
        val arity1BooleanParams = optionFields
            .onEach { it.isAccessible = true }
            .filter { it.get(DEFAULT) is Boolean }
            .flatMap { it.annotations.toList() }
            .filterIsInstance<Parameter>()
            .filter { it.arity == 1 }
            .flatMap { it.names.toList() }
            .toList()

        /**
         * List of all option names from parameter annotations.
         */
        val optionNames = optionFields
            .flatMap { it.annotations.toList() }
            .filterIsInstance<Parameter>()
            .flatMap { it.names.toList() }
            .toList()

        /**
         * List of option names that are marked as API public.
         * These options are exposed through REST APIs.
         */
        val apiPublicOptionNames = optionFields
            .filter { it.kotlinProperty?.hasAnnotation<ApiPublic>() == true }
            .flatMap { it.annotations.toList() }
            .filterIsInstance<Parameter>()
            .flatMap { it.names.toList() }
            .toList()

        /**
         * Generates help documentation from field annotations.
         * Returns a list of option descriptions for documentation.
         */
        val helpList: List<List<String>>
            get() =
                optionFields
                    .mapNotNull { (it.annotations.firstOrNull { it is Parameter } as? Parameter)?.to(it) }
                    .map {
                        listOf(
                            it.first.names.joinToString { it },
                            it.second.type.typeName.substringAfterLast("."),
                            defaultParams[it.second.name].toString(),
                            it.first.description
                        )
                    }.toList()

        /**
         * Sets the value of a field based on its annotation name.
         *
         * @param options the LoadOptions instance to modify
         * @param annotationName the annotation name to search for
         * @param value the value to set
         */
        fun setFieldByAnnotation(options: LoadOptions, annotationName: String, value: Any) {
            optionFields.forEach {
                val found = it.annotations.filterIsInstance<Parameter>().any { annotationName in it.names }
                if (found) {
                    it.isAccessible = true
                    it.set(options, value)
                }
            }
        }

        /**
         * Returns all option names for a given field.
         *
         * @param fieldName the field name to look up
         * @return list of all option names for that field
         */
        fun getOptionNames(fieldName: String): List<String> {
            return optionFields
                .filter { it.name == fieldName }
                .flatMap { it.annotations.toList() }
                .filterIsInstance<Parameter>()
                .flatMap { it.names.toList() }
                .toList()
        }

        /**
         * Creates a new empty LoadOptions with the given configuration.
         *
         * @param conf the configuration to use
         * @return a new LoadOptions instance
         */
        fun create(conf: VolatileConfig) = LoadOptions(arrayOf(), conf).apply { parse() }

        /**
         * Creates a new empty LoadOptions with an unsafe configuration.
         *
         * @return a new LoadOptions instance
         */
        fun createUnsafe() = create(VolatileConfig.UNSAFE)

        /**
         * Normalizes arguments into a standard format.
         *
         * @param args the arguments to normalize
         * @return normalized arguments string
         */
        fun normalize(vararg args: String?) = parse(args.filterNotNull().joinToString(" ")).toString()

        /**
         * Parses a string of arguments into a LoadOptions object.
         *
         * @param args the arguments string to parse
         * @param conf the configuration to use
         * @return the parsed LoadOptions
         */
        fun parse(args: String, conf: VolatileConfig = VolatileConfig()) =
            LoadOptions(args.trim(), conf).apply { parse() }

        /**
         * Parses a string of arguments into a LoadOptions object based on another instance.
         *
         * @param args the arguments string to parse
         * @param options the base options to use
         * @return the parsed LoadOptions
         */
        fun parse(args: String, options: LoadOptions) = LoadOptions(args.trim(), options).apply {
            parse()
        }

        /**
         * Merges two LoadOptions objects, with o2 options taking precedence.
         *
         * @param o1 the base options
         * @param o2 the overriding options
         * @return a new merged LoadOptions
         */
        fun merge(o1: LoadOptions, o2: LoadOptions) = parse("$o1 $o2", o2)

        /**
         * Merges a LoadOptions object with additional arguments string.
         *
         * @param o1 the base options
         * @param args the overriding arguments
         * @return a new merged LoadOptions
         */
        fun merge(o1: LoadOptions, args: String?) = parse("$o1 $args", o1)

        /**
         * Merges two argument strings into a single LoadOptions.
         *
         * @param args the base arguments
         * @param args2 the overriding arguments
         * @param conf the configuration to use
         * @return a new merged LoadOptions
         */
        fun merge(args: String?, args2: String?, conf: VolatileConfig = VolatileConfig.UNSAFE) = parse("$args $args2", conf)

        /**
         * Merges multiple argument strings, with later arguments taking precedence.
         *
         * @param args the argument strings to merge
         * @param conf the configuration to use
         * @return the merged arguments string
         */
        fun mergeArgs(vararg args: String?, conf: VolatileConfig = VolatileConfig.UNSAFE) = parse(args.joinToString(" "), conf).toString()

        /**
         * Removes specified options from an arguments string.
         *
         * @param args the original arguments string
         * @param fieldNames the field names to remove
         * @return the modified arguments string
         */
        fun eraseOptions(args: String, vararg fieldNames: String): String {
            // do not forget the blanks
            var normalizedArgs = " $args "

            val optionNames = fieldNames.flatMap { getOptionNames(it) }.map { " $it " }
            optionNames.forEach {
                normalizedArgs = normalizedArgs.replace(it, " -erased ")
            }

            return normalizedArgs.trim()
        }
    }
}

