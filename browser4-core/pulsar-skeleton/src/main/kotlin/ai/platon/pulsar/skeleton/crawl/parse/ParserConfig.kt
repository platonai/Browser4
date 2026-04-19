
package ai.platon.pulsar.skeleton.crawl.parse

import com.google.common.collect.Lists

/**
 * This class represents a natural ordering for which parsing plugin should get
 * called for a particular mimeType. It provides methods to store the
 * parse-plugins.xml data, and methods to retrieve the name of the appropriate
 * parsing plugin for a contentType.
 */
class ParserConfig {
    /* a map to link mimeType to an ordered list of parsing plugins */
    private val mimeType2ParserClasses: MutableMap<String, List<String>> = LinkedHashMap()
    /* Aliases to class */
    var aliases: Map<String, String> = mapOf()

    fun setParsers(mimeType: String, classes: List<String>) {
        mimeType2ParserClasses[mimeType] = classes
    }

    val parsers: Map<String, List<String>>
        get() = mimeType2ParserClasses

    fun getParsers(mimeType: String): List<String> {
        return mimeType2ParserClasses[mimeType]?: listOf()
    }

    fun getClassName(aliase: String): String? {
        return aliases[aliase]
    }

    val supportedMimeTypes: List<String>
        get() = Lists.newArrayList(mimeType2ParserClasses.keys)

    override fun toString(): String {
        return mimeType2ParserClasses.toString()
    }
}