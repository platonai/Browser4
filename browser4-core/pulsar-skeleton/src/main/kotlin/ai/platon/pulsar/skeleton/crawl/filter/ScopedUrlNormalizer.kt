package ai.platon.pulsar.skeleton.crawl.filter

typealias NaiveUrlNormalizer = ai.platon.pulsar.common.urls.preprocess.UrlNormalizer

/**
 * Default scope. If no scope properties are defined then the configuration
 * for this scope will be used.
 */
const val SCOPE_DEFAULT: String = "default"

/**
 * Interface used to convert URLs to normal form and optionally perform
 * substitutions
 */
interface ScopedUrlNormalizer : NaiveUrlNormalizer {

    fun isRelevant(url: String, scope: String = SCOPE_DEFAULT): Boolean

    fun normalize(url: String, scope: String = SCOPE_DEFAULT): String?

    fun valid(urlString: String, scope: String): Boolean {
        return normalize(urlString, scope) != null
    }
}

abstract class AbstractScopedUrlNormalizer :
    ScopedUrlNormalizer {
    override fun isRelevant(url: String, scope: String): Boolean = false

    override fun invoke(url: String?) = url?.let { normalize(it) }

    abstract override fun normalize(url: String, scope: String): String?
}
