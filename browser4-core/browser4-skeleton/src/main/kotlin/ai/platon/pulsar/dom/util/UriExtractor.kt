package ai.platon.pulsar.dom.util

import ai.platon.pulsar.dom.FeaturedDocument
import java.net.URI
import java.util.regex.Pattern

class UriExtractor {

    data class UriCategory(
        val internalLinks: MutableList<String> = mutableListOf(),
        val externalLinks: MutableList<String> = mutableListOf(),
        val images: MutableList<String> = mutableListOf(),
        val stylesheets: MutableList<String> = mutableListOf(),
        val scripts: MutableList<String> = mutableListOf(),
        val fragments: MutableList<String> = mutableListOf(),
        val email: MutableList<String> = mutableListOf(),
        val phone: MutableList<String> = mutableListOf(),
        val other: MutableList<String> = mutableListOf()
    )

    companion object {
        private val CSS_URL_PATTERN = Pattern.compile("""url\(['"]?([^'")]+)['"]?\)""", Pattern.CASE_INSENSITIVE)

        // Attributes that commonly contain URIs
        private val URI_ATTRIBUTES = mapOf(
            "a" to listOf("href"),
            "img" to listOf("src", "data-src", "srcset"),
            "link" to listOf("href"),
            "script" to listOf("src"),
            "iframe" to listOf("src"),
            "form" to listOf("action"),
            "input" to listOf("src", "formaction"),
            "area" to listOf("href"),
            "base" to listOf("href"),
            "blockquote" to listOf("cite"),
            "del" to listOf("cite"),
            "ins" to listOf("cite"),
            "q" to listOf("cite"),
            "object" to listOf("data"),
            "source" to listOf("src", "srcset"),
            "track" to listOf("src"),
            "video" to listOf("src", "poster"),
            "audio" to listOf("src"),
            "embed" to listOf("src"),
            "meta" to listOf("content")
        )
    }

    /**
     * Extract all URIs from a [ai.platon.pulsar.dom.FeaturedDocument].
     *
     * @param document The document to extract URIs from.
     * @param baseUrl Optional base URL to resolve relative URIs.
     * @return A set of unique URIs extracted from the document.
     */
    fun extractAllUris(document: FeaturedDocument, baseUrl: String? = null): Set<String> {
        val uris = mutableSetOf<String>()

        // Extract URIs from standard attributes
        extractFromAttributes(document, uris)

        // Extract URIs from CSS (inline styles and style tags)
        extractFromCss(document, uris)

        // Convert relative URLs to absolute if base URL is provided
        return if (baseUrl != null) {
            uris.mapNotNull { uri -> resolveUri(uri, baseUrl) }.toSet()
        } else {
            uris
        }
    }

    private fun extractFromAttributes(document: FeaturedDocument, uris: MutableSet<String>) {
        URI_ATTRIBUTES.forEach { (tagName, attributes) ->
            document.select(tagName).forEach { element ->
                attributes.forEach { attr ->
                    element.attr(attr).takeIf { it.isNotBlank() }?.let { value ->
                        if (attr == "srcset") {
                            parseSrcset(value).forEach { uri -> uris.add(uri) }
                        } else {
                            uris.add(value)
                        }
                    }
                }
            }
        }
    }

    private fun extractFromCss(document: FeaturedDocument, uris: MutableSet<String>) {
        // Extract from inline style attributes
        document.select("[style]").forEach { element ->
            element.attr("style").let { style ->
                extractUrisFromCss(style).forEach { uri -> uris.add(uri) }
            }
        }

        // Extract from <style> tags
        document.select("style").forEach { styleElement ->
            styleElement.data().let { cssContent ->
                extractUrisFromCss(cssContent).forEach { uri -> uris.add(uri) }
            }
        }
    }

    private fun parseSrcset(srcsetValue: String): List<String> {
        return srcsetValue.split(",")
            .mapNotNull { item ->
                item.trim().split("\\s+".toRegex()).firstOrNull()?.takeIf { it.isNotBlank() }
            }
    }

    private fun extractUrisFromCss(cssContent: String): List<String> {
        val matcher = CSS_URL_PATTERN.matcher(cssContent)
        val uris = mutableListOf<String>()

        while (matcher.find()) {
            matcher.group(1)?.let { uri ->
                uris.add(uri)
            }
        }

        return uris
    }

    private fun resolveUri(uri: String, baseUrl: String): String? {
        return try {
            when {
                uri.startsWith("#") ||
                        uri.startsWith("mailto:") ||
                        uri.startsWith("tel:") ||
                        uri.startsWith("javascript:") -> uri

                uri.startsWith("http://") || uri.startsWith("https://") -> uri
                else -> {
//                    val base = URI.create(baseUrl).toURL()
//                    URL(base, uri).toString()
                    URI.create(baseUrl).resolve(uri).toString()
                }
            }
        } catch (e: Exception) {
            null // Invalid URI
        }
    }

    fun categorizeUris(uris: Set<String>, baseUrl: String?): UriCategory {
        val category = UriCategory()
        val baseDomain = baseUrl?.let {
            try {
                URI(it).host
            } catch (e: Exception) {
                null
            }
        }

        uris.forEach { uri ->
            when {
                uri.startsWith("#") -> category.fragments.add(uri)
                uri.startsWith("mailto:") -> category.email.add(uri)
                uri.startsWith("tel:") -> category.phone.add(uri)
                uri.matches(""".*\.(css)(\?.*)?$""".toRegex(RegexOption.IGNORE_CASE)) ->
                    category.stylesheets.add(uri)

                uri.matches(""".*\.(js)(\?.*)?$""".toRegex(RegexOption.IGNORE_CASE)) ->
                    category.scripts.add(uri)

                uri.matches(""".*\.(jpg|jpeg|png|gif|svg|webp|bmp|ico)(\?.*)?$""".toRegex(RegexOption.IGNORE_CASE)) ->
                    category.images.add(uri)

                uri.startsWith("http") -> {
                    try {
                        val uriHost = URI(uri).host
                        if (baseDomain != null && uriHost == baseDomain) {
                            category.internalLinks.add(uri)
                        } else {
                            category.externalLinks.add(uri)
                        }
                    } catch (e: Exception) {
                        category.other.add(uri)
                    }
                }

                else -> category.other.add(uri)
            }
        }

        return category
    }
}
