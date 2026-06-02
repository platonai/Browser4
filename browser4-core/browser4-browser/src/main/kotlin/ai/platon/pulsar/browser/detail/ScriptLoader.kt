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

open class ScriptLoader(
    val confuser: ScriptConfuser,
    val jsPropertyNames: List<String>
) {
    companion object {
        private val logger = getLogger(this)

        private val jsInitParameters: MutableMap<String, Any> = mutableMapOf()

        /**
         * addInitParameter("ATTR_ELEMENT_NODE_DATA", AppConstants.PULSAR_ATTR_ELEMENT_NODE_DATA)
         * addInitParameter("ATTR_COMPUTED_STYLE", AppConstants.PULSAR_ATTR_COMPUTED_STYLE)
         * */
        fun addInitParameter(name: String, value: String) {
            jsInitParameters[name] = value
        }

        val RESOURCES = """
            stealth.js
            configs.js
            node_ext.js
            node_traversor.js
            feature_calculator.js
            __pulsar_utils__.js
        """.trimIndent().split("\n").map { "js/" + it.trim() }.toMutableList()
    }

    private val jsCache: MutableMap<String, String> = LinkedHashMap()

    /**
     * The JavaScript code to inject into the browser.
     * */
    private var preloadJs = ""

    init {
        initDefaultJsParameters()
    }

    /**
     * Make sure generatePreloadJs is thread safe
     * */
    @Synchronized
    fun getPreloadJs(reload: Boolean = false): String {
        if (reload) {
            preloadJs = ""
        }

        if (preloadJs.isEmpty()) {
            load()
        }

        return preloadJs
    }

    @Synchronized
    fun reload() {
        load()
    }

    private fun load(): String {
        jsCache.clear()

        val sb = StringBuilder()

        val jsVariables = generatePredefinedJsConfig()
        sb.appendLine(jsVariables).appendLine("\n\n\n")

        loadExternalResource()
        loadDefaultResource()
        jsCache.values.joinTo(sb, ";\n")

        preloadJs = sb.toString()

        reportPreloadJs(preloadJs)

        return preloadJs
    }

    private fun generatePredefinedJsConfig(): String {
        // Note: Json-2.6.2 does not recognize MutableMap, but knows Map
        val configs = pulsarObjectMapper().writeValueAsString(jsInitParameters.toMap())

        // set predefined variables shared between JavaScript and jvm program
        val configVar = confuser.confuse("__pulsar_CONFIGS")
        return """
            ;
            let $configVar = $configs;
        """.trimIndent()
    }

    private fun loadDefaultResource() {
        RESOURCES.filter { !it.startsWith("#") }.distinct().associateWithTo(jsCache) {
            ResourceLoader.readAllLines(it).joinToString("\n") { confuser.confuse(it) }
        }
    }

    private fun loadExternalResource() {
        val dir = AppPaths.BROWSER_DATA_DIR.resolve("browser/js/preload")
        if (Files.isDirectory(dir)) {
            dir.listDirectoryEntries()
                .filter { it.isReadable() }
                .filter { it.toString().endsWith(".js") }
                .associateTo(jsCache) { it.toString() to Files.readString(it) }
        }
    }

    private fun reportPreloadJs(script: String) {
        val dir = AppPaths.REPORT_DIR.resolve("browser/js")
        Files.createDirectories(dir)
        val report = Files.writeString(dir.resolve("preload.gen.js"), script)
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
