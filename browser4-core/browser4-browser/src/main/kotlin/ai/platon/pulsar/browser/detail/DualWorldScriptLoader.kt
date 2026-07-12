package ai.platon.pulsar.browser.detail

import ai.platon.pulsar.browser.common.BrowserSettings
import ai.platon.pulsar.browser.common.ScriptConfuser
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.ResourceLoader
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import java.nio.file.Files
import kotlin.io.path.isReadable
import kotlin.io.path.listDirectoryEntries

/**
 * Dual-world script loader that separates scripts for Page World and Isolated World.
 *
 * Architecture:
 * - Page World: Only stealth patches and minimal hooks for anti-detection
 * - Isolated World: Full Browser4 runtime with DOM utilities and feature calculators
 *
 * This separation ensures:
 * 1. Page JavaScript cannot access or detect the Browser4 runtime
 * 2. The runtime remains isolated and secure
 * 3. CDP can still access the runtime for browser automation
 */
open class DualWorldScriptLoader(
    val confuser: ScriptConfuser, val jsPropertyNames: List<String>
) {
    companion object {
        private val logger = getLogger(this)

        private val jsInitParameters: MutableMap<String, Any> = mutableMapOf()

        /**
         * Scripts that should be injected into the Page World.
         * These are minimal stealth patches and fingerprint patches only.
         */
        val PAGE_WORLD_RESOURCES = listOf(
            "js/stealth.js"
        )

        /**
         * Scripts that should be injected into the Isolated World.
         * These contain the full Browser4 runtime and utilities.
         */
        val ISOLATED_WORLD_RESOURCES = listOf(
            "js/configs.js",
            "js/node_ext.js",
            "js/node_traversor.js",
            "js/feature_calculator.js",
            "js/__pulsar_utils__.js"
        )

        /**
         * Add initialization parameters that will be available to JavaScript.
         *
         * @param name The parameter name
         * @param value The parameter value
         */
        fun addInitParameter(name: String, value: String) {
            jsInitParameters[name] = value
        }
    }

    private val pageWorldCache: MutableMap<String, String> = LinkedHashMap()
    private val isolatedWorldCache: MutableMap<String, String> = LinkedHashMap()

    /**
     * The javascript code to inject into the Page World (anti-detection patches only).
     */
    private var pageWorldJs = ""

    /**
     * The javascript code to inject into the Isolated World (full runtime).
     */
    private var isolatedWorldJs = ""

    init {
        initDefaultJsParameters()
    }

    /**
     * Gets the Page World JavaScript (stealth patches only).
     *
     * @param reload Whether to reload the scripts from disk
     * @return The compiled Page World JavaScript
     */
    @Synchronized
    fun getPageWorldJs(reload: Boolean = false): String {
        if (reload) {
            pageWorldJs = ""
        }

        if (pageWorldJs.isEmpty()) {
            loadPageWorldScripts()
        }

        return pageWorldJs
    }

    /**
     * Gets the Isolated World JavaScript (full Browser4 runtime).
     *
     * @param reload Whether to reload the scripts from disk
     * @return The compiled Isolated World JavaScript
     */
    @Synchronized
    fun getIsolatedWorldJs(reload: Boolean = false): String {
        if (reload) {
            isolatedWorldJs = ""
        }

        if (isolatedWorldJs.isEmpty()) {
            loadIsolatedWorldScripts()
        }

        return isolatedWorldJs
    }

    /**
     * Reloads all scripts from disk.
     */
    @Synchronized
    fun reload() {
        loadPageWorldScripts()
        loadIsolatedWorldScripts()
    }

    /**
     * Loads Page World scripts (stealth patches).
     */
    private fun loadPageWorldScripts() {
        pageWorldCache.clear()

        val sb = StringBuilder()

        // Page World scripts should be minimal and not expose any configuration
        sb.appendLine("// Browser4 Page World - Stealth Patches Only")
        sb.appendLine("// These scripts run in the page context for anti-detection")
        sb.appendLine()

        loadDefaultResource(PAGE_WORLD_RESOURCES, pageWorldCache)
        loadExternalResource("page-world", pageWorldCache)

        pageWorldCache.values.joinTo(sb, ";\n")

        pageWorldJs = sb.toString()

        reportScript("page-world.gen.js", pageWorldJs)
    }

    /**
     * Loads Isolated World scripts (full runtime).
     */
    private fun loadIsolatedWorldScripts() {
        isolatedWorldCache.clear()

        val sb = StringBuilder()

        // Isolated World gets full configuration
        val jsVariables = generatePredefinedJsConfig()
        sb.appendLine(jsVariables).appendLine("\n\n")

        sb.appendLine("// Browser4 Isolated World Runtime")
        sb.appendLine("// This code runs in an isolated world and is invisible to page JS")
        sb.appendLine()

        loadDefaultResource(ISOLATED_WORLD_RESOURCES, isolatedWorldCache)
        loadExternalResource("isolated-world", isolatedWorldCache)

        isolatedWorldCache.values.joinTo(sb, ";\n")

        isolatedWorldJs = sb.toString()

        reportScript("isolated-world.gen.js", isolatedWorldJs)
    }

    private fun generatePredefinedJsConfig(): String {
        // Note: Json-2.6.2 does not recognize MutableMap, but knows Map
        val configs = pulsarObjectMapper().writeValueAsString(jsInitParameters.toMap())

        // Set predefined variables shared between javascript and jvm program
        val configVar = confuser.confuse("__pulsar_CONFIGS")
        return """
            ;
            let $configVar = $configs;
        """.trimIndent()
    }

    private fun loadDefaultResource(resources: List<String>, cache: MutableMap<String, String>) {
        resources.filter { !it.startsWith("#") }.distinct().associateWithTo(cache) {
            ResourceLoader.readAllLines(it).joinToString("\n") { line -> confuser.confuse(line) }
        }
    }

    private fun loadExternalResource(subdirectory: String, cache: MutableMap<String, String>) {
        val dir = AppPaths.BROWSER_DATA_DIR.resolve("browser/js/preload/$subdirectory")
        if (Files.isDirectory(dir)) {
            dir.listDirectoryEntries().filter { it.isReadable() }.filter { it.toString().endsWith(".js") }
                .associateTo(cache) { it.toString() to Files.readString(it) }
        }
    }

    private fun reportScript(filename: String, script: String) {
        val dir = AppPaths.REPORT_DIR.resolve("browser/js")
        Files.createDirectories(dir)
        val report = Files.writeString(dir.resolve(filename), script)
        logger.info("Generated js: file://$report")
    }

    private fun initDefaultJsParameters() {
        mapOf(
            "propertyNames" to jsPropertyNames,
            "viewPortWidth" to BrowserSettings.VIEWPORT.width,
            "viewPortHeight" to BrowserSettings.VIEWPORT.height,

            "META_INFORMATION_ID" to AppConstants.PULSAR_META_INFORMATION_ID,
            "SCRIPT_SECTION_ID" to AppConstants.PULSAR_SCRIPT_SECTION_ID,
            "ATTR_HIDDEN" to AppConstants.PULSAR_ATTR_HIDDEN,
            "ATTR_OVERFLOW_HIDDEN" to AppConstants.PULSAR_ATTR_OVERFLOW_HIDDEN,
            "ATTR_OVERFLOW_VISIBLE" to AppConstants.PULSAR_ATTR_OVERFLOW_VISIBLE,
            "ATTR_ELEMENT_NODE_VI" to AppConstants.PULSAR_ATTR_ELEMENT_NODE_VI,
            "ATTR_TEXT_NODE_VI" to AppConstants.PULSAR_ATTR_TEXT_NODE_VI
        ).also { jsInitParameters.putAll(it) }
    }
}
