package ai.platon.pulsar.skeleton.workflow.parse

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.skeleton.workflow.common.MimeTypeResolver
import ai.platon.pulsar.skeleton.workflow.parse.html.PrimerHtmlParser
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Creates [Parser].
 */
class ParserFactory(private val conf: ImmutableConfig) {
    // Thread safe for both outer map and inner list
    private val mineType2Parsers = ConcurrentHashMap<String, List<Parser>>()

    constructor(
        availableParsers: List<Parser>, conf: ImmutableConfig
    ) : this(ParserConfigReader().parse(conf), availableParsers, conf)

    constructor(
        parserConfig: ParserConfig,
        availableParsers: List<Parser>,
        conf: ImmutableConfig
    ) : this(conf) {
        val availableNamedParsers = availableParsers.associateBy { it.javaClass.name }
        parserConfig.parsers.forEach { (mimeType: String, parserClasses: List<String>) ->
            val parsers = parserClasses.mapNotNull { name -> availableNamedParsers[name] }
            mineType2Parsers[mimeType] = Collections.synchronizedList(parsers)
        }
    }

    constructor(parses: Map<String, List<Parser>>, conf: ImmutableConfig) : this(conf) {
        mineType2Parsers.putAll(parses)
    }

    init {
        if (mineType2Parsers.isEmpty()) {
            val htmlParsers = listOf(PrimerHtmlParser(conf))
            listOf("text/html", "application/xhtml+xml").forEach {
                mineType2Parsers[it] = htmlParsers
            }
        }
    }

    /**
     * Function returns an array of [Parser]s for a given content type.
     *
     * The function consults the internal list of parse plugins for the
     * ParserFactory to determine the list of pluginIds, then gets the appropriate
     * extension points to instantiate as [Parser]s.
     *
     * The function is thread safe
     *
     * @param contentType The contentType to return the `Array` of [Parser]'s for.
     * @param url         The url for the content that may allow us to get the type from the file suffix.
     * @return An `List` of [Parser]s for the given contentType.
     */
    @Throws(ParserNotFound::class)
    fun getParsers(contentType: String, url: String = ""): List<Parser> {
        val mimeType = MimeTypeResolver.cleanMimeType(contentType) ?: DEFAULT_MINE_TYPE
        return mineType2Parsers[mimeType] ?: mineType2Parsers[DEFAULT_MINE_TYPE] ?: listOf()
    }

    override fun toString(): String {
        return mineType2Parsers.values.flatMap { it.map { it.javaClass.simpleName } }.distinct().joinToString()
    }

    companion object {
        const val DEFAULT_MINE_TYPE = "*"
    }
}
