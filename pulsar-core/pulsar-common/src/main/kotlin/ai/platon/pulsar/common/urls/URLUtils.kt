@file:Suppress("Unused")

package ai.platon.pulsar.common.urls

import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.config.AppConstants.INTERNAL_URL_PREFIX
import com.google.common.net.InternetDomainName
import org.apache.commons.lang3.StringUtils
import org.apache.hc.core5.net.URIBuilder
import java.net.*
import java.nio.file.Path
import java.util.*

object URLUtils {
    /**
     * The prefix of allowed urls
     */
    val INTERNAL_URL_PREFIXES = listOf("chrome://", "edge://", "brave://")

    /**
     * The urls of all allowed internal urls
     */
    val INTERNAL_URLS = listOf("about:blank")

    /**
     * Test if the url is an internal URL. Internal URLs are URLs that are used to identify internal resources and
     * will never be fetched from the internet.
     *
     * @param  url   The url to test
     * @return true if the given str is an internal URL, false otherwise
     * */
    @JvmStatic
    fun isInternal(url: String): Boolean {
        return url.startsWith(INTERNAL_URL_PREFIX)
    }

    /**
     * Test if the url is not an internal URL. Internal URLs are URLs that are used to identify internal resources and
     * will never be fetched from the internet.
     *
     * @param  url   The url to test
     * @return true if the given str is not an internal URL, false otherwise
     * */
    @JvmStatic
    fun isNotInternal(url: String) = !isInternal(url)

    /**
     * Check if the given url is a local file url, which is a url that starts with {@link AppConstants#LOCAL_FILE_SERVE_PREFIX}
     * */
    @JvmStatic
    fun isLocalFile(url: String): Boolean {
        return url.startsWith(AppConstants.LOCAL_FILE_BASE_URL)
    }

    /**
     * Convert a path to a URL, the path will be encoded to base64 and appended to the {@link AppConstants#LOCAL_FILE_FAKE_SERVER_HOME}
     *
     * For example:
     *
     * `C:\Users\pereg\AppData\Local\Temp\pulsar\test.txt`
     * will be converted to:
     * `http://localfile.org?path=QzpcVXNlcnNccGVyZWdcQXBwRGF0YVxMb2NhbFxUZW1wXHB1bHNhclx0ZXN0LnR4dA==`
     *
     * @param path The path to convert
     *
     * TODO: consider just use path.toUri() in the system
     * */
    @JvmStatic
    fun pathToLocalURL(path: Path): String {
        val base64 = Base64.getUrlEncoder().encode(path.toString().toByteArray()).toString(Charsets.UTF_8)
        val prefix = AppConstants.LOCAL_FILE_BASE_URL
        return "$prefix?path=$base64"
    }

    /**
     * Convert a URL to a path, the path is decoded from base64 and the prefix {@link AppConstants#LOCAL_FILE_SERVE_PREFIX} is removed
     * */
    @JvmStatic
    fun localURLToPath(url: String): Path {
        val encodedPath = kotlin.runCatching {
            URIBuilder(url).queryParams?.firstOrNull { it.name == "path" }?.value
        }.getOrNull()

        require(!encodedPath.isNullOrBlank()) { "Missing query parameter 'path' in local file url: $url" }

        val decoded = Base64.getUrlDecoder().decode(encodedPath).toString(Charsets.UTF_8)
        return Path.of(decoded)
    }

    /**
     * Checks if the given string is a browser-specific url.
     *
     * This function determines whether the string is a browser-specific url
     * by checking if it exists in the internal URL list (INTERNAL_URLS),
     * or if it starts with any of the internal URL prefixes (INTERNAL_URL_PREFIXES).
     *
     * @param str The string to be checked.
     * @return Returns true if the string is a browser-specific url; otherwise, returns false.
     */
    @JvmStatic
    fun isBrowserURL(str: String): Boolean {
        return INTERNAL_URLS.contains(str) || INTERNAL_URL_PREFIXES.any { str.startsWith(it) }
    }

    /**
     * Checks if the given URL is a browser-specific URL by verifying if it starts with a predefined prefix.
     *
     * @param url The URL to check.
     * @return Returns true if the URL starts with the browser-specific url prefix, otherwise false.
     */
    @JvmStatic
    fun isMappedBrowserURL(url: String): Boolean {
        return url.startsWith(AppConstants.BROWSER_INTERNAL_BASE_URL)
    }

    /**
     * Converts a browser url string into a complete URL.
     * The function URL-encodes the url string and appends it to a predefined prefix to form the final URL.
     *
     * @param url The browser url string to be converted. This string will be URL-encoded.
     * @return Returns the complete URL string containing the prefix and the encoded url parameter.
     */
    @JvmStatic
    fun browserURLToStandardURL(url: String): String {
        val encoded = URLEncoder.encode(url, Charsets.UTF_8)
        val prefix = AppConstants.BROWSER_INTERNAL_BASE_URL
        return "$prefix?url=$encoded"
    }

    /**
     * Extracts the browser url from a given URL and re-encodes it.
     * The function retrieves the url parameter from the URL, re-encodes it, and reconstructs the URL.
     *
     * @param url The URL containing the browser url.
     * @return Returns the reconstructed URL with the re-encoded url parameter.
     */
    @JvmStatic
    fun standardURLToBrowserURL(url: String): String? {
        val encoded = kotlin.runCatching {
            URIBuilder(url).queryParams?.firstOrNull { it.name == "url" }?.value
        }.getOrNull() ?: return null

        if (encoded.isBlank()) {
            return null
        }

        return URLDecoder.decode(encoded, Charsets.UTF_8)
    }

    /**
     * Creates a {@code URL} object from the {@code String}
     * representation.
     *
     * @param      spec   the {@code String} to parse as a URL.
     * @return     the URL parsed from [spec],
     *             or null if no protocol is specified, or an
     *               unknown protocol is found, or {@code spec} is {@code null},
     *               or the parsed URL fails to comply with the specific syntax
     *               of the associated protocol.
     * @see        java.net.URL#URI.create(java.net.URL)
     */
    @JvmStatic
    fun getURLOrNull(spec: String?): URL? {
        if (spec.isNullOrBlank()) {
            return null
        }

        return kotlin.runCatching { URI.create(spec).toURL() }.getOrNull()
    }

    /**
     * Test if the str is a standard URL.
     *
     * @param  str   The string to test
     * @return true if the given str is a standard URL, false otherwise
     * */
    @JvmStatic
    fun isStandard(str: String?): Boolean {
        return getURLOrNull(str) != null
    }

    /**
     * Test if the str is an allowed URL.
     *
     * @param  str   The string to test
     * @return true if the given str is a standard URL, false otherwise
     * */
    @JvmStatic
    fun isAllowed(str: String?): Boolean {
        return str != null && (isInternal(str) || isStandard(str))
    }

    /**
     * Safely encodes a URL with unencoded special characters in query parameters.
     * 
     * This method attempts to parse the URL and encode any special characters in the query string
     * that would cause URIBuilder to fail. It does NOT fix URLs with special characters in the path.
     * It preserves already-encoded characters and handles fragments correctly.
     * 
     * @param url The URL string that may contain unencoded special characters in query parameters
     * @return A properly encoded URL string safe for URIBuilder
     * @throws URISyntaxException If the URL structure is fundamentally invalid or has illegal characters in the path
     */
    private fun safeEncodeUrl(url: String): String {
        return try {
            // First try to parse with URIBuilder - if it works, the URL is already well-formed
            URIBuilder(url)
            url
        } catch (e: URISyntaxException) {
            // Check if the error is specifically about query parameters
            val message = e.message ?: ""
            if (!message.contains("Illegal character in query", ignoreCase = true) &&
                !message.contains("query", ignoreCase = true)) {
                // The error is not in the query string (e.g., it's in the path or authority)
                // Don't try to fix it - let it fail
                throw e
            }
            
            // The error is in the query string - try to encode the query parameters
            val queryStart = url.indexOf('?')
            if (queryStart == -1) {
                // No query string, but we got a query error? Re-throw the original exception
                throw e
            }
            
            // Split into base URL, query, and fragment parts
            val fragmentStart = url.indexOf('#', queryStart)
            val baseUrl = url.substring(0, queryStart)
            
            // Verify that the base URL is valid by itself
            try {
                URI(baseUrl)
            } catch (baseException: URISyntaxException) {
                // Base URL is invalid - can't fix this
                throw baseException
            }
            
            // Extract query string (between ? and # or end of string)
            val queryEnd = if (fragmentStart != -1) fragmentStart else url.length
            val queryString = url.substring(queryStart + 1, queryEnd)
            
            // Extract fragment if present
            val fragment = if (fragmentStart != -1 && fragmentStart < url.length - 1) {
                url.substring(fragmentStart + 1)
            } else null
            
            // Encode query parameters
            // Note: We encode each parameter independently which means ampersands in values
            // must be already encoded, otherwise they'll be treated as parameter separators
            val encodedQuery = queryString.split("&").joinToString("&") { param ->
                val eqIndex = param.indexOf('=')
                if (eqIndex != -1) {
                    val key = param.substring(0, eqIndex)
                    val value = param.substring(eqIndex + 1)
                    // Encode the value if it contains illegal characters
                    // We use a simple strategy: try to construct a URI with this query parameter
                    // If it fails, encode it
                    val testUri = try {
                        java.net.URI("http://test.com?$param")
                        // URI construction succeeded, the parameter is already well-formed
                        param
                    } catch (testException: Exception) {
                        // URI construction failed, need to encode the value
                        "$key=${URLEncoder.encode(value, "UTF-8").replace("+", "%20")}"
                    }
                    testUri
                } else {
                    // Key without value - try to encode if needed
                    try {
                        java.net.URI("http://test.com?$param")
                        param
                    } catch (testException: Exception) {
                        URLEncoder.encode(param, "UTF-8").replace("+", "%20")
                    }
                }
            }
            
            // Reconstruct the URL with encoded query and fragment
            StringBuilder().apply {
                append(baseUrl)
                append("?").append(encodedQuery)
                if (fragment != null) {
                    append("#").append(fragment)
                }
            }.toString()
        }
    }

    /**
     * Normalize a url spec.
     *
     * A URL may have appended to it a "fragment", also known as a "ref" or a "reference".
     * The fragment is indicated by the sharp sign character "#" followed by more characters.
     * For example: http://java.sun.com/index.html#chapter1
     *
     * The fragment will be removed after the normalization.
     * If ignoreQuery is true, the query string will be removed.
     *
     * @param url
     *        The url to normalize, a tailing argument list is allowed and will be removed
     *
     * @param ignoreQuery
     *        If true, the result url does not contain a query string
     *
     * @return The normalized URL
     * @throws URISyntaxException
     *         If the given string violates RFC&nbsp;2396
     * @throws MalformedURLException
     * @throws IllegalArgumentException
     * */
    @JvmStatic
    @Throws(URISyntaxException::class, IllegalArgumentException::class, MalformedURLException::class)
    fun normalize(url: String, ignoreQuery: Boolean = false): URL {
        val (url0, _) = splitUrlArgs(url)

        val safeUrl = safeEncodeUrl(url0)
        val uriBuilder = URIBuilder(safeUrl)
        uriBuilder.fragment = null
        if (ignoreQuery) {
            uriBuilder.removeQuery()
        }

        return uriBuilder.build().toURL()
    }

    /**
     * Normalize a url spec.
     *
     * A URL may have appended to it a "fragment", also known as a "ref" or a "reference".
     * The fragment is indicated by the sharp sign character "#" followed by more characters.
     * For example: http://java.sun.com/index.html#chapter1
     *
     * The fragment will be removed after the normalization.
     * If ignoreQuery is true, the query string will be removed.
     *
     * @param url
     *        The url to normalize, a tailing argument list is allowed and will be removed
     *
     * @param ignoreQuery
     *        If true, the result url does not contain a query string
     *
     * @return The normalized url,
     *         or an empty string ("") if the given string violates RFC&nbsp;2396
     * */
    @JvmStatic
    fun normalizeOrEmpty(url: String, ignoreQuery: Boolean = false): String {
        return try {
            normalize(url, ignoreQuery).toString()
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Normalize a url spec.
     *
     * A URL may have appended to it a "fragment", also known as a "ref" or a "reference".
     * The fragment is indicated by the sharp sign character "#" followed by more characters.
     * For example: http://java.sun.com/index.html#chapter1
     *
     * The fragment will be removed after the normalization.
     * If ignoreQuery is true, the query string will be removed.
     *
     * @param url
     *        The url to normalize, a tailing argument list is allowed and will be removed
     *
     * @param ignoreQuery
     *        If true, the result url does not contain a query string
     *
     * @return The normalized url,
     *         or null if the given string violates RFC&nbsp;2396
     * */
    @JvmStatic
    fun normalizeOrNull(url: String?, ignoreQuery: Boolean = false): String? {
        if (url == null) {
            return null
        }

        return try {
            normalize(url, ignoreQuery).toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Normalize a url spec.
     *
     * A URL may have appended to it a "fragment", also known as a "ref" or a "reference".
     * The fragment is indicated by the sharp sign character "#" followed by more characters.
     * For example: http://java.sun.com/index.html#chapter1
     *
     * The fragment will be removed after the normalization.
     * If ignoreQuery is true, the query string will be removed.
     *
     * @param urls
     *        The urls to normalize, a tailing argument list is allowed and will be removed
     *
     * @param ignoreQuery
     *        If true, the result url does not contain a query string
     *
     * @return The normalized URLs
     * */
    @JvmStatic
    fun normalizeUrls(urls: Iterable<String>, ignoreQuery: Boolean = false): List<String> {
        return urls.mapNotNull { normalizeOrNull(it, ignoreQuery) }
    }

    /**
     * Normalizes a URL by removing redundant slashes and normalizing the URI.
     * @param url The URL to normalize.
     * @return The normalized URL as a string.
     * */
    fun removeRedundantSlashes(url: String): String {
        val uri = URI(url).normalize()

        val normalizedPath = uri.path?.replace(Regex("/+"), "/") ?: "/"

        return URI(
            uri.scheme,
            uri.userInfo,
            uri.host,
            uri.port,
            normalizedPath,
            uri.query,
            uri.fragment
        ).toString()
    }

    /**
     * Builds a server URL from the given hostname, port, context path, and path.
     * @param hostname The hostname of the server.
     * @param port The port number of the server.
     * @param contextPath The context path of the server.
     * @param path The specific path to append to the context path.
     * @return The constructed server URL as a string.
     * */
    @Throws(URISyntaxException::class)
    fun buildServerUrl(hostname: String, port: Int, contextPath: String, path: String = ""): String {
        val combinedPath = listOf(contextPath, path)
            .filter { it.isNotBlank() }
            .joinToString("/")
            .replace(Regex("/+"), "/")
            .let { if (!it.startsWith("/")) "/$it" else it }
        val url = "http://$hostname:$port$combinedPath"
        return removeRedundantSlashes(url)
    }

    /**
     * Split the query parameters of a url.
     *
     * @param url The url to split
     * @return The query parameters of the url
     * */
    @Throws(URISyntaxException::class)
    fun splitQueryParameters(url: String): Map<String, String> {
        return URIBuilder(url).queryParams?.associate { it.name to it.value } ?: mapOf()
    }

    /**
     * Get the query parameter of a url.
     *
     * @param url The url to split
     * @param parameterName The name of the query parameter
     * @return The query parameter of the url
     * */
    @Throws(URISyntaxException::class)
    fun getQueryParameters(url: String, parameterName: String): String? {
        return URIBuilder(url).queryParams?.firstOrNull { it.name == parameterName }?.value
    }

    /**
     * Remove the query parameters of a url.
     *
     * @param url The url to split
     * @param parameterNames The names of the query parameters
     * @return The url without the query parameters
     * */
    @Throws(URISyntaxException::class)
    fun removeQueryParameters(url: String, vararg parameterNames: String): String {
        val uriBuilder = URIBuilder(url)
        uriBuilder.setParameters(uriBuilder.queryParams.apply { removeIf { it.name in parameterNames } })
        return uriBuilder.build().toString()
    }

    /**
     * Keep the query parameters of a url, and remove the others.
     *
     * @param url The url to split
     * @param parameterNames The names of the query parameters
     * @return The url with only the query parameters
     * */
    @Throws(URISyntaxException::class)
    fun keepQueryParameters(url: String, vararg parameterNames: String): String {
        val uriBuilder = URIBuilder(url)
        uriBuilder.setParameters(uriBuilder.queryParams.apply { removeIf { it.name !in parameterNames } })
        return uriBuilder.build().toString()
    }

    /**
     * Split url and args
     *
     * @param configuredUrl url and args in `$url $args` format
     * @return url and args pair
     */
    @JvmStatic
    fun splitUrlArgs(configuredUrl: String): Pair<String, String> {
        var url = configuredUrl.trim().replace("[\\r\\n\\t]".toRegex(), "")
        val pos = url.indexOfFirst { it.isWhitespace() }

        var args = ""
        if (pos >= 0) {
            args = url.substring(pos)
            url = url.substring(0, pos)
        }

        return url.trim() to args.trim()
    }

    /**
     * Merge url and args
     *
     * @param url  url
     * @param args args
     * @return url and args in `$url $args` format
     */
    @JvmStatic
    fun mergeUrlArgs(url: String, args: String? = null): String {
        return if (args.isNullOrBlank()) url.trim() else "${url.trim()} ${args.trim()}"
    }

    /**
     * Get the url without parameters
     *
     * @param url url
     * @return url without parameters
     */
    @JvmStatic
    fun getUrlWithoutParameters(url: String): String {
        try {
            var uri = URI(url)
            uri = URI(
                uri.scheme,
                uri.authority,
                uri.path,
                null, // Ignore the query part of the input url
                uri.fragment
            )
            return uri.toString()
        } catch (ignored: Throwable) {
        }

        return ""
    }

    /**
     * Returns the normalized url and key
     *
     * @param originalUrl
     * @param norm
     * @return normalized url and key
     */
    @JvmStatic
    fun normalizedUrlAndKey(originalUrl: String, norm: Boolean = false): Pair<String, String> {
        val url = if (norm) (normalizeOrNull(originalUrl) ?: "") else originalUrl
        val key = reverseUrlOrEmpty(url)
        return url to key
    }

    /**
     * Reverses a url's domain. This form is better for storing in hbase. Because
     * scans within the same domain are faster.
     *
     * E.g. "http://bar.foo.com:8983/to/index.html?a=b" becomes
     * "com.foo.bar:8983:http/to/index.html?a=b".
     *
     * @param url url to be reversed
     * @return Reversed url
     * @throws MalformedURLException
     */
    @JvmStatic
    fun reverseUrl(url: String): String {
        return reverseUrl(URI.create(url).toURL())
    }

    /**
     * Reverses a url's domain. This form is better for storing in hbase. Because
     * scans within the same domain are faster.
     *
     * E.g. "http://bar.foo.com:8983/to/index.html?a=b" becomes
     * "com.foo.bar:8983:http/to/index.html?a=b".
     *
     * @param url url to be reversed
     * @return Reversed url or empty string if the url is invalid
     */
    @JvmStatic
    fun reverseUrlOrEmpty(url: String): String {
        return try {
            reverseUrl(URI.create(url).toURL())
        } catch (e: MalformedURLException) {
            ""
        }
    }

    /**
     * Reverses a url's domain. This form is better for storing in hbase. Because
     * scans within the same domain are faster.
     *
     * E.g. "http://bar.foo.com:8983/to/index.html?a=b" becomes
     * "com.foo.bar:8983:http/to/index.html?a=b".
     *
     * @param url url to be reversed
     * @return Reversed url or null if the url is invalid
     */
    @JvmStatic
    fun reverseUrlOrNull(url: String): String? {
        return try {
            reverseUrl(URI.create(url).toURL())
        } catch (e: MalformedURLException) {
            null
        }
    }

    /**
     * Reverses a url's domain. This form is better for storing in hbase. Because scans within the same domain are
     * faster.
     *
     * E.g. "http://bar.foo.com:8983/to/index.html?a=b" becomes "com.foo.bar:http:8983/to/index.html?a=b".
     *
     * @param url url to be reversed
     * @return Reversed url
     */
    @JvmStatic
    fun reverseUrl(url: URL): String {
        val host = url.host
        val file = url.file
        val protocol = url.protocol
        val port = url.port

        val buf = StringBuilder()

        /* reverse host */
        reverseAppendSplits(host, buf)

        /* put protocol */
        buf.append(':')
        buf.append(protocol)

        /* put port if necessary */
        if (port != -1) {
            buf.append(':')
            buf.append(port)
        }

        /* put path */
        if (file.isNotEmpty() && '/' != file[0]) {
            buf.append('/')
        }
        buf.append(file)

        return buf.toString()
    }

    /**
     * Get the reversed and tenanted format of unreversedUrl, unreversedUrl can be both tenanted or not tenanted
     * This method might change the tenant id of the original url
     *
     * Zero tenant id means no tenant
     *
     * @param unreversedUrl the unreversed url, can be both tenanted or not tenanted
     * @return the tenanted and reversed url of unreversedUrl
     */
    @JvmStatic
    fun reverseUrl(tenantId: Int, unreversedUrl: String): String {
        val tenantedUrl = TenantedUrl.split(unreversedUrl)
        return TenantedUrl.combine(tenantId, reverseUrl(tenantedUrl.url))
    }

    /**
     * Get the unreversed url of a reversed url.
     *
     * @param reversedUrl
     * @return the unreversed url of reversedUrl
     */
    @JvmStatic
    fun unreverseUrl(reversedUrl: String): String {
        val buf = StringBuilder(reversedUrl.length + 2)

        var pathBegin = reversedUrl.indexOf('/')
        if (pathBegin == -1) {
            pathBegin = reversedUrl.length
        }
        val sub = reversedUrl.substring(0, pathBegin)

        val splits = StringUtils.splitPreserveAllTokens(sub, ':') // {<reversed host>, <port>, <protocol>}

        buf.append(splits[1]) // put protocol
        buf.append("://")
        reverseAppendSplits(splits[0], buf) // splits[0] is reversed
        // host
        if (splits.size == 3) { // has a port
            buf.append(':')
            buf.append(splits[2])
        }

        buf.append(reversedUrl.substring(pathBegin))

        return buf.toString()
    }

    /**
     * Get the unreversed url of a reversed url.
     *
     * @param reversedUrl
     * @return the unreversed url of reversedUrl or null if the url is invalid
     */
    @JvmStatic
    fun unreverseUrlOrNull(reversedUrl: String) = kotlin.runCatching { unreverseUrl(reversedUrl) }.getOrNull()

    /**
     * Get unreversed and tenanted url of reversedUrl, reversedUrl can be both tenanted or not tenanted,
     * This method might change the tenant id of the original url
     *
     * @param tenantId    the expected tenant id of the reversedUrl
     * @param reversedUrl the reversed url, can be both tenanted or not tenanted
     * @return the unreversed url of reversedTenantedUrl
     * @throws MalformedURLException
     */
    @JvmStatic
    fun unreverseUrl(tenantId: Int, reversedUrl: String): String {
        val tenantedUrl = TenantedUrl.split(reversedUrl)
        return TenantedUrl.combine(tenantId, unreverseUrl(tenantedUrl.url))
    }

    /**
     * Get start key for tenanted table
     *
     * @param unreversedUrl unreversed key, which is the original url
     * @return reverse and tenanted key
     */
    @JvmStatic
    fun getStartKey(tenantId: Int, unreversedUrl: String?): String? {
        if (unreversedUrl == null) {
            // restricted within tenant space
            return if (tenantId == 0) null else tenantId.toString()
        }

        //    if (StringUtils.countMatches(unreversedUrl, "0001") > 1) {
        //      return null;
        //    }

        val startKey = decodeKeyLowerBound(unreversedUrl)
        return reverseUrl(tenantId, startKey)
    }

    /**
     * Get start key for non-tenanted table
     *
     * @param unreversedUrl unreversed key, which is the original url
     * @return reverse key
     */
    @JvmStatic
    fun getStartKey(unreversedUrl: String?): String? {
        if (unreversedUrl == null) {
            return null
        }

        //    if (StringUtils.countMatches(unreversedUrl, "0001") > 1) {
        //      return null;
        //    }

        val startKey = decodeKeyLowerBound(unreversedUrl)
        return reverseUrl(startKey)
    }

    /**
     * Get end key for non-tenanted tables
     *
     * @param unreversedUrl unreversed key, which is the original url
     * @return reverse, key bound decoded key
     */
    @JvmStatic
    fun getEndKey(unreversedUrl: String?): String? {
        if (unreversedUrl == null) {
            return null
        }

        //    if (StringUtils.countMatches(unreversedUrl, "FFFF") > 1) {
        //      return null;
        //    }

        val endKey = decodeKeyUpperBound(unreversedUrl)
        return reverseUrl(endKey)
    }

    /**
     * Get end key for tenanted tables
     *
     * @param unreversedUrl unreversed key, which is the original url
     * @return reverse, tenanted and key bound decoded key
     */
    @JvmStatic
    fun getEndKey(tenantId: Int, unreversedUrl: String?): String? {
        if (unreversedUrl == null) {
            // restricted within tenant space
            return if (tenantId == 0) null else (tenantId + 1).toString()
        }

        //    if (StringUtils.countMatches(unreversedUrl, "FFFF") > 1) {
        //      return null;
        //    }

        val endKey = decodeKeyUpperBound(unreversedUrl)
        return reverseUrl(tenantId, endKey)
    }

    /**
     * We use unicode character \u0001 to be the lower key bound, but the client usally
     * encode the character to be a string "\\u0001" or "\\\\u0001", so we should decode
     * them to be the right one
     *
     * Note, the character is displayed as <U></U>+0001> in some output system
     *
     * Now, we consider all the three character/string \u0001, "\\u0001" and "\\\\u0001"
     * are the lower key bound
     */
    @JvmStatic
    fun decodeKeyLowerBound(startKey: String): String {
        var startKey1 = startKey
        startKey1 = startKey1.replace("\\\\u0001".toRegex(), "\u0001")
        startKey1 = startKey1.replace("\\u0001".toRegex(), "\u0001")

        return startKey1
    }

    /**
     * We use unicode character \uFFFF to be the upper key bound, but the client usally
     * encode the character to be a string "\\uFFFF" or "\\\\uFFFF", so we should decode
     * them to be the right one
     *
     *
     * Note, the character may display as <U></U>+FFFF> in some output system
     *
     *
     * Now, we consider all the three character/string \uFFFF, "\\uFFFF" and "\\\\uFFFF"
     * are the upper key bound
     */
    @JvmStatic
    fun decodeKeyUpperBound(endKey: String): String {
        var endKey1 = endKey
        // Character lastChar = Character.MAX_VALUE;
        endKey1 = endKey1.replace("\\\\uFFFF".toRegex(), "\uFFFF")
        endKey1 = endKey1.replace("\\uFFFF".toRegex(), "\uFFFF")

        return endKey1
    }

    /**
     * Given a reversed url, returns the reversed host E.g
     * "com.foo.bar:http:8983/to/index.html?a=b" -> "com.foo.bar"
     *
     * @param reversedUrl Reversed url
     * @return Reversed host
     */
    @JvmStatic
    fun getReversedHost(reversedUrl: String): String {
        return reversedUrl.substring(0, reversedUrl.indexOf(':'))
    }

    /**
     * Reverse the host name.
     *
     * @param hostName host name
     * @return reversed host name
     */
    @JvmStatic
    fun reverseHost(hostName: String): String {
        val buf = StringBuilder()
        reverseAppendSplits(hostName, buf)
        return buf.toString()
    }

    /**
     * Unreverse the host name.
     *
     * @param reversedHostName reversed host name
     * @return host name
     */
    @JvmStatic
    fun unreverseHost(reversedHostName: String): String {
        return reverseHost(reversedHostName) // Reversible
    }


    /**
     * Indicates whether this domain name represents a *public suffix*, as defined by the Mozilla
     * Foundation's [Public Suffix List](http://publicsuffix.org/) (PSL). A public suffix
     * is one under which Internet users can directly register names, such as `com`, `co.uk` or `pvt.k12.wy.us`. Examples of domain names that are *not* public suffixes
     * include `google.com`, `foo.co.uk`, and `myblog.blogspot.com`.
     *
     *
     * Public suffixes are a proper superset of [registry suffixes][.isRegistrySuffix].
     * The list of public suffixes additionally contains privately owned domain names under which
     * Internet users can register subdomains. An example of a public suffix that is not a registry
     * suffix is `blogspot.com`. Note that it is true that all public suffixes *have*
     * registry suffixes, since domain name registries collectively control all internet domain names.
     *
     *
     * For considerations on whether the public suffix or registry suffix designation is more
     * suitable for your application, see [this article](https://github.com/google/guava/wiki/InternetDomainNameExplained).
     *
     * @return `true` if this domain name appears exactly on the public suffix list
     */
    fun isPublicSuffix(domain: String): Boolean {
        return InternetDomainName.from(domain).isPublicSuffix
    }

    /**
     * Get the host's public suffix. For example, co.uk, com, etc.
     *
     * @since 6.0
     */
    fun getPublicSuffix(url: String): String? {
        val domain = getTopPrivateDomainOrNull(url) ?: return null
        return InternetDomainName.from(domain).publicSuffix()?.toString()
    }

    /**
     * Get the host's public suffix. For example, co.uk, com, etc.
     */
    fun getPublicSuffix(url: URL): String? {
        return InternetDomainName.from(url.host).publicSuffix()?.toString()
    }

    /**
     * Indicates whether this domain name is composed of exactly one subdomain component followed by a
     * {@linkplain #isPublicSuffix() public suffix}. For example, returns {@code true} for {@code
     * google.com} {@code foo.co.uk}, and {@code myblog.blogspot.com}, but not for {@code
     * www.google.com}, {@code co.uk}, or {@code blogspot.com}.
     *
     * <p>This method can be used to determine whether a domain is probably the highest level for
     * which cookies may be set, though even that depends on individual browsers' implementations of
     * cookie controls. See <a href="http://www.ietf.org/rfc/rfc2109.txt">RFC 2109</a> for details.
     */
    fun isTopPrivateDomain(url: URL): Boolean {
        return InternetDomainName.from(url.host).isTopPrivateDomain
    }

    /**
     * Returns the portion of this domain name that is one level beneath the [isPublicSuffix] public suffix.
     * For example, for `x.adwords.google.co.uk` it returns `google.co.uk`, since `co.uk` is a public suffix.
     * Similarly, for `myblog.blogspot.com` it returns the same domain, `myblog.blogspot.com`, since `blogspot.com` is a public suffix.
     *
     * If [isTopPrivateDomain] is true, the current domain name instance is returned.
     *
     * This method can be used to determine the probable highest level parent domain for which cookies may be set,
     * though even that depends on individual browsers' implementations of cookie controls.
     *
     * @throws IllegalStateException if this domain does not end with a public suffix
     */
    @Throws(IllegalStateException::class)
    fun getTopPrivateDomain(url: URL): String {
        return InternetDomainName.from(url.host).topPrivateDomain().toString()
    }

    /**
     * Returns the portion of this domain name that is one level beneath the [isPublicSuffix] public suffix.
     * For example, for `x.adwords.google.co.uk` it returns `google.co.uk`, since `co.uk` is a public suffix.
     * Similarly, for `myblog.blogspot.com` it returns the same domain, `myblog.blogspot.com`, since `blogspot.com` is a public suffix.
     *
     * If [isTopPrivateDomain] is true, the current domain name instance is returned.
     *
     * This method can be used to determine the probable highest level parent domain for which cookies may be set,
     * though even that depends on individual browsers' implementations of cookie controls.
     *
     * @throws IllegalStateException if this domain does not end with a public suffix
     */
    @Throws(IllegalStateException::class, MalformedURLException::class)
    fun getTopPrivateDomain(url: String) = getTopPrivateDomain(URI.create(url).toURL())

    /**
     * Returns the portion of this domain name that is one level beneath the [isPublicSuffix] public suffix.
     * For example, for `x.adwords.google.co.uk` it returns `google.co.uk`, since `co.uk` is a public suffix.
     * Similarly, for `myblog.blogspot.com` it returns the same domain, `myblog.blogspot.com`, since `blogspot.com` is a public suffix.
     *
     * If [isTopPrivateDomain] is true, the current domain name instance is returned.
     *
     * This method can be used to determine the probable highest level parent domain for which cookies may be set,
     * though even that depends on individual browsers' implementations of cookie controls.
     *
     * @throws IllegalStateException if this domain does not end with a public suffix
     */
    fun getTopPrivateDomainOrNull(url: String) = kotlin.runCatching { getTopPrivateDomain(url) }.getOrNull()

    /**
     * Returns the lowercase origin for the url.
     *
     * @param url The url to check.
     * @return String The hostname for the url.
     */
    @Throws(MalformedURLException::class)
    fun getOrigin(url: String): String {
        val u = URI.create(url).toURL()

        val defaultPort = when (u.protocol.lowercase(Locale.getDefault())) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }

        val port = u.port
        return if (port == -1 || (defaultPort != -1 && port == defaultPort)) {
            "${u.protocol}://${u.host}"
        } else {
            "${u.protocol}://${u.host}:$port"
        }
    }

    /**
     * Returns the lowercase origin for the url or null if the url is not well-formed.
     *
     * @param url The url to check.
     * @return String The hostname for the url.
     */
    fun getOriginOrNull(url: String?): String? {
        if (url == null) {
            return null
        }

        return try {
            return getOrigin(url)
        } catch (t: Throwable) {
            null
        }
    }


    /**
     * Returns the lowercase hostname for the url.
     *
     * @param url The url to check.
     * @return String The hostname for the url.
     */
    @Throws(MalformedURLException::class)
    fun getHostName(url: String): String {
        val host = URI.create(url).host
        require(!host.isNullOrBlank()) { "Missing host in url: $url" }
        return host.lowercase(Locale.getDefault())
    }

    /**
     * Returns the lowercase hostname for the url or null if the url is not well-formed.
     *
     * @param url The url to check.
     * @return String The hostname for the url.
     */
    fun getHostNameOrNull(url: String?): String? {
        if (url == null) {
            return null
        }

        return kotlin.runCatching {
            val host = URI.create(url).host ?: return@runCatching null
            host.lowercase(Locale.getDefault())
        }.getOrNull()
    }

    fun getHostName(url: String?, defaultValue: String): String {
        if (url == null) {
            return defaultValue
        }

        return kotlin.runCatching {
            val host = URI.create(url).host
            if (host.isNullOrBlank()) defaultValue else host.lowercase(Locale.getDefault())
        }.getOrDefault(defaultValue)
    }


    private fun reverseAppendSplits(string: String, buf: StringBuilder) {
        val splits = StringUtils.split(string, '.')
        if (splits.isNotEmpty()) {
            for (i in splits.size - 1 downTo 1) {
                buf.append(splits[i])
                buf.append('.')
            }
            buf.append(splits[0])
        } else {
            buf.append(string)
        }
    }
}

@Deprecated("Inappropriate name", ReplaceWith("URLUtils"))
typealias UrlUtils  = URLUtils
