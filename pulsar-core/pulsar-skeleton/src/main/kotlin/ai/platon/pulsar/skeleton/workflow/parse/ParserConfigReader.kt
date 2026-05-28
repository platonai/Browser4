package ai.platon.pulsar.skeleton.workflow.parse

import ai.platon.pulsar.common.ResourceLoader
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.getLogger
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * A reader to load the information stored in the `parse-plugins.xml` file.
 */
class ParserConfigReader {
    /**
     * the parse-plugins file
     */
    private lateinit var parseConfigFile: String
    private val notDefinedParsers = mutableListOf<String>()

    /**
     * Reads the `parse-plugins.xml` file and returns the
     * [ParserConfig] defined by it.
     *
     * @return A [ParserConfig] specified by the
     * `parse-plugins.xml` file.
     */
    fun parse(conf: ImmutableConfig): ParserConfig {
        val parserConfig = ParserConfig()
        val resourcePrefix = conf[CapabilityTypes.PROFILE_KEY, ""]
        val fileResource = conf[PARSE_PLUGINS_FILE, "parse-plugins.xml"]
        var document: Document? = null
        try {
            ResourceLoader.getResourceAsReader(fileResource, resourcePrefix).use { reader ->
                val inputSource = InputSource(reader)
                val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                document = parser.parse(inputSource)
            }
        } catch (e: IOException) {
            LOG.error("Failed to find resource $fileResource")
            return parserConfig
        } catch (e: ParserConfigurationException) {
            LOG.warn("Unable to parse [$parseConfigFile]", e)
            return parserConfig
        } catch (e: SAXException) {
            LOG.warn("Unable to parse [$parseConfigFile]", e)
            return parserConfig
        } catch (e: Throwable) {
            LOG.warn("Unable to parse [$parseConfigFile], unexpected exception", e)
            return parserConfig
        }

        val parsePlugins = document!!.documentElement
        // build up the alias hash map
        val aliases = getAliases(parsePlugins)
        // And store it on the parse plugin list
        parserConfig.aliases = aliases
        // get all the mime type nodes
        val mimeTypes = parsePlugins.getElementsByTagName("mimeType")
        // iterate through the mime types
        for (i in 0 until mimeTypes.length) {
            val mimeType = mimeTypes.item(i) as Element
            val mimeTypeStr = mimeType.getAttribute("name")
            // for each mimeType, get the plugin list
            val parserNodes = mimeType.getElementsByTagName("parser")
            // iterate through the plugins, add them in order read
            // OR if they have a special order="" attribute, then hold those in
            // a separate list, and then insert them into the final list at the
            // order specified
            if (parserNodes.length > 0) {
                val parserClasses = mutableListOf<String>()
                for (j in 0 until parserNodes.length) {
                    val parserNode = parserNodes.item(j) as Element
                    val parserId = parserNode.getAttribute("id")
                    val parserClass = aliases[parserId]
                    if (parserClass == null) {
                        notDefinedParsers.add(parserId)
                        continue
                    }
                    parserClasses.add(parserClass)
                }
                // now add the plugin list and map it to this mimeType
                if (parserClasses.isNotEmpty()) {
                    parserConfig.setParsers(mimeTypeStr, parserClasses)
                }
            } else {
                LOG.warn("No plugins defined for mime type: $mimeTypeStr, continuing parse")
            }
        }
        return parserConfig
    }

    private fun getAliases(parsePluginsRoot: Element): Map<String, String> {
        val aliases: MutableMap<String, String> = HashMap()
        val aliasRoot = parsePluginsRoot.getElementsByTagName("aliases")
        if (aliasRoot.length == 0) {
            if (LOG.isWarnEnabled) {
                LOG.warn("No aliases defined in parse-plugins.xml!")
            }
            return aliases
        }
        if (aliasRoot.length > 1) { // log a warning, but try and continue processing
            if (LOG.isWarnEnabled) {
                LOG.warn("There should only be one \"aliases\" tag in parse-plugins.xml")
            }
        }
        val aliasRootElem = aliasRoot.item(0) as Element
        val aliasElements = aliasRootElem.getElementsByTagName("alias")
        if (aliasElements.length > 0) {
            for (i in 0 until aliasElements.length) {
                val aliasElem = aliasElements.item(i) as Element
                val name = aliasElem.getAttribute("name")
                val clazz = aliasElem.getAttribute("class")
                aliases[name] = clazz
            }
        }

        return aliases
    }

    companion object {
        /* our log stream */
        val LOG = getLogger(ParserConfigReader::class)

        /**
         * The property name of the parse-plugins location
         */
        const val PARSE_PLUGINS_FILE = "parse.plugin.file"
    }
}
