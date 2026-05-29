package ai.platon.pulsar.common.config;

import com.google.common.annotations.Beta;

/**
 * Created by Vincent on 17-1-17.
 * Copyright @ 2013-2023 Platon AI. All rights reserved
 *
 * @author vincent
 * @version $Id: $Id
 */
public interface CapabilityTypes {

    ///////////////////////////////////////////////////
    // Common Parameters

    /**
     * The key to retrieve the application identity string, can be specified by system environment variable or system property.
     * <p>
     * The default value is the current username.
     * */
    String APP_ID_KEY = "app.ident";
    /**
     * The key to retrieve the application name, can be specified by system environment variable or system property.
     * */
    String APP_NAME_KEY = "app.name";
    /**
     * The key to retrieve the profile, can be specified by system environment variable or system property.
     * */
    String PROFILE_KEY = "profile";
    /**
     * The key to retrieve the data directory, can be specified by system environment variable or system property.
     * */
    String APP_DATA_DIR_KEY = "app.data.dir";

    /**
     * The key to retrieve the base directory of the temporary files.
     * */
    String APP_TMP_BASE_DIR_KEY = "app.tmp.base.dir";

    /**
     * Main loop
     * */
    String MAIN_LOOP_CONCURRENCY_OVERRIDE = "main.loop.concurrency.override";

    String PARAM_JOB_NAME = "job.name";

    /**
     * Application metrics
     * */
    String METRICS_ENABLED = "metrics.enabled";

    String PAGE_EVENT_CLASS = "page.eventHandlers.class";

    ///////////////////////////////////////////////////////////////////////////
    // Storage

    String STORAGE_CRAWL_ID = "storage.crawl.id";

    String STORAGE_SCHEMA_WEBPAGE = "storage.schema.webpage";

    String STORAGE_PREFERRED_SCHEMA_NAME = "preferred.schema.name";

    String STORAGE_DATA_STORE_CLASS = "storage.data.store.class";

    ///////////////////////////////////////////////////////////////////////////
    // Spring

    String APPLICATION_CONTEXT_CONFIG_LOCATION = "application.context.config.location";

    ///////////////////////////////////////////////////////////////////////////
    // Load phrase

    /**
     * Deactivate the fetch component, ensuring that all pages are loaded exclusively from storage
     * and never fetched from the Internet.
     * <p>
     * If a page is not found in the local storage, return WebPageImpl.NIL.
     * */
    String LOAD_DEACTIVATE_FETCH_COMPONENT = "load.deactivate.fetch.component";

    ///////////////////////////////////////////////////////////////////////////
    // Fetch phrase

    String FETCH_CONCURRENCY = "fetch.concurrency";

    String FETCH_TASK_TIMEOUT = "fetch.task.timeout";

    String FETCH_MAX_HOST_FAILURES = "fetch.max.host.failures";

    String FETCH_MAX_INTERVAL = "fetch.interval.max";

    String FETCH_DEFAULT_INTERVAL = "fetch.default.interval";

    /**
     * The maximum number of pages to export in fetch phrase.
     * */
    String FETCH_PAGE_AUTO_EXPORT_LIMIT = "fetch.page.auto.export.limit";

    /**
     * Fetch
     * */
    String FETCH_PAGE_LOAD_TIMEOUT = "fetch.page.load.timeout";
    String FETCH_SCRIPT_TIMEOUT = "fetch.script.timeout";
    String FETCH_SCROLL_DOWN_COUNT = "fetch.scroll.down.count";
    String FETCH_SCROLL_DOWN_INTERVAL = "fetch.scroll.down.interval";

    String FETCH_CLIENT_JS_COMPUTED_STYLES = "fetch.browser.client.js.computed.styles";
    String FETCH_CLIENT_JS_PROPERTY_NAMES = "fetch.browser.client.js.property.names";

    String FETCH_MAX_CONTENT_LENGTH = "fetch.max.content.length";

    ///////////////////////////////////////////////////////////////////////////
    // Privacy context
    /**
     * The mode of browser context, case-insensitive.
     * default, system_default, prototype, sequential, temporary
     * @deprecated Use {@code BROWSER_PROFILE_MODE} instead
     */
    @Deprecated
    String BROWSER_CONTEXT_MODE = "browser.profile.mode";
    /**
     * The mode of browser profile, case-insensitive.
     * default, system_default, prototype, sequential, temporary
     */
    String BROWSER_PROFILE_MODE = "browser.profile.mode";
    /**
     * The number of active privacy contexts.
     */
    String PRIVACY_CONTEXT_NUMBER = "privacy.context.number";
    /**
     * The number of active privacy contexts.
     */
    String BROWSER_CONTEXT_NUMBER = "browser.context.number";

    /**
     * @deprecated Use {@code MIN_SEQUENTIAL_BROWSER_PROFILE_NUMBER} instead
     * */
    String MIN_SEQUENTIAL_PRIVACY_AGENT_NUMBER = "min.sequential.privacy.agent.number";
    /**
     * The minimal number of sequential browser profiles, the active privacy contexts is chosen from them.
     * */
    String MIN_SEQUENTIAL_BROWSER_PROFILE_NUMBER = "min.sequential.privacy.agent.number";
    /**
     * The maximum number of sequential browser profiles, the active privacy contexts is chosen from them.
     * */
    String MAX_SEQUENTIAL_PRIVACY_AGENT_NUMBER = "max.sequential.privacy.agent.number";
    /**
     * The class name of browser profile generator
     * */
    String PRIVACY_AGENT_GENERATOR_CLASS = "privacy.agent.generator.class";
    String PRIVACY_MINOR_WARNING_FACTOR = "privacy.minor.warning.factor";

    String PRIVACY_CONTEXT_IDLE_TIMEOUT = "privacy.idle.timeout";

    String PRIVACY_CONTEXT_FAILURE_RATE_THRESHOLD = "privacy.failure.rate.threshold";
    String PRIVACY_MAX_WARNINGS = "privacy.max.warnings";
    String PRIVACY_CONTEXT_MIN_THROUGHPUT = "privacy.context.min.throughput";
    /**
     * The strategy to close privacy context: asap, lazy
     * */
    String PRIVACY_CONTEXT_CLOSE_LAZY = "privacy.close.strategy";


    ///////////////////////////////////////////////////////////////////////////
    // Browser

    String CHROME_PATH = "chrome.path";
    /**
     * The max value of tabs a browser can open
     */
    String BROWSER_MAX_OPEN_TABS = "browser.max.open.tabs";
    /**
     * The web driver to use
     * */
    String BROWSER_WEB_DRIVER_PRIORITY = "browser.web.driver.priority";
    String BROWSER_DRIVER_POOL_IDLE_TIMEOUT = "browser.driver.pool.idle.timeout";
    String BROWSER_TYPE = "browser.type";
    @Beta
    String BROWSER_INCOGNITO = "browser.incognito";
    /**
     * The browser interact settings
     * */
    String BROWSER_INTERACT_SETTINGS = "browser.interact.settings";
    String BROWSER_DISPLAY_MODE = "browser.display.mode";

    String BROWSER_DELETE_ALL_COOKIES = "browser.delete.all.cookies";
    String BROWSER_RESPONSE_HANDLER = "browser.response.handler";
    /**
     * The probability to block urls specified by {@code WebDriver.addBlockedURLs}, between [0, 1]
     * */
    String BROWSER_RESOURCE_BLOCK_PROBABILITY = "browser.url.block.probability";
    String BROWSER_ENABLE_UA_OVERRIDING = "browser.enable.ua.overriding";
    String BROWSER_SPA_MODE = "browser.spa.mode";
    /**
     * Whether reuse the recovered drivers to serve new tasks.
     * */
    String BROWSER_REUSE_RECOVERED_DRIVERS = "browser.reuse.recovered.drivers";
    /**
     * Add a --no-sandbox flag to launch the chrome if we are running inside a virtual machine,
     * for example, VirtualBox, VMware or WSL
     * */
    String BROWSER_LAUNCH_NO_SANDBOX = "browser.launch.no.sandbox";
    String BROWSER_LAUNCH_SUPERVISOR_PROCESS = "browser.launch.supervisor.process";
    String BROWSER_LAUNCH_SUPERVISOR_PROCESS_ARGS = "browser.launch.supervisor.process.args";
    String POLLING_DRIVER_TIMEOUT = "polling.driver.timeout";

    ///////////////////////////////////////////////////////////////////////////
    // Proxy
    ///

    String PROXY_POOL_MANAGER_CLASS = "proxy.pool.manager.class";
    String PROXY_LOADER_CLASS = "proxy.loader.class";
    String PROXY_PARSER_CLASS = "proxy.parser.class";
    String PROXY_MAX_FETCH_SUCCESS = "proxy.max.fetch.success";
    String PROXY_MAX_ALLOWED_PROXY_ABSENCE = "proxy.max.allowed.proxy.absence";
    String PROXY_POOL_CAPACITY = "proxy.pool.size";
    String PROXY_POOL_POLLING_TIMEOUT = "proxy.pool.polling.interval";
    String PROXY_IDLE_TIMEOUT = "proxy.idle.timeout";

    /**
     * The key used to retrieve a proxy rotation URL. Each time the URL is accessed, a new set of proxy IPs will be returned.
     * */
    String PROXY_ROTATION_URL = "proxy.rotation.url";

    ///////////////////////////////////////////////////////////////////////////
    // Network

    String HTTP_TIMEOUT = "http.timeout";
    String HTTP_FETCH_MAX_RETRY = "http.fetch.max.retry";

    ///////////////////////////////////////////////////////////////////////////
    // Parse phrase

    String PARSE_TIMEOUT = "parser.timeout";
    String PARSE_MAX_URL_LENGTH = "parse.max.url.length";
    String PARSE_MIN_ANCHOR_LENGTH = "parse.min.anchor.length";
    String PARSE_MAX_ANCHOR_LENGTH = "parse.max.anchor.length";
    String PARSE_SUPPORT_ALL_CHARSETS = "parser.support.all.charsets";
    String PARSE_DEFAULT_ENCODING = "parser.character.encoding.default";

    ///////////////////////////////////////////////////////////////////////////
    // LLM
    String LLM_PROVIDER = "llm.provider";
    String LLM_NAME = "llm.name";
    String LLM_API_KEY = "llm.apiKey";

    ///////////////////////////////////////////////////////////////////////////
    // Other

    /**
     * Create default data collectors or not
     * */
    String CRAWL_ENABLE_DEFAULT_DATA_COLLECTORS = "crawl.enable.default.data.collectors";
    /**
     * Create default data collectors or not
     * */
    String CRAWL_SMART_RETRY = "crawl.smart.retry";
    /**
     * The size of global page cache
     * */
    String GLOBAL_PAGE_CACHE_SIZE = "global.page.cache.size";
    /**
     * The size of global document cache
     * */
    String GLOBAL_DOCUMENT_CACHE_SIZE = "global.document.cache.size";

    // H2
    String H2_SESSION_FACTORY_CLASS = "h2.sessionFactory";

    String SCENT_EXTRACT_TABULATE_CELL_TYPE = "scent.extract.tabulate.cell.type";

    /// /////////////////////////////////////////////////////
    // Distributed

    /**
     * The node id. The node id **HAS TO** be specified as a JVM System variable or a Environment variable.
     * */
    String APP_NODE_ID = "app.node.id";
}
