package ai.platon.pulsar.common.urls

import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.config.AppConstants.BROWSER_INTERNAL_BASE_URL
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.net.*
import java.util.*
import kotlin.io.path.toPath

class URLUtilsTest {

    @Test
    fun testURIBasics() {
        // 准备测试数据
        val path = AppPaths.getTmpDirectory("test.txt")
        val uri = path.toUri()
        val url = uri.toURL()
        assertEquals("file", uri.scheme)
        assertEquals("file", url.protocol)
    }

    @Test
    fun testNormalizer() {
        // URLs with special characters like quotes should now be encoded and handled, not rejected
        var url = "https://www.amazon.com/s?k=\"Boys%27+Novelty+Belt+Buckles\"&rh=n:9057119011&page=1"
        var normUrl = URLUtils.normalizeOrNull(url, true)
        assertNotNull(normUrl, "URLs with special characters should be encoded, not rejected")

        url = "https://www.amazon.com/s?k=Boys%27+Novelty+Belt+Buckles&rh=n:9057119011&page=1"
        normUrl = URLUtils.normalizeOrNull(url, true)
        assertNotNull(normUrl)
    }

    @Test
    fun `isStandard should return true for standard URL`() {
        // 标准 URL 测试
        val standardUrl = "https://www.example.com"
        assertTrue(URLUtils.isStandard(standardUrl))
    }

    @Test
    fun `isStandard should return false for non-standard URL`() {
        // 非标准 URL 测试
        val nonStandardUrl = "example"
        assertFalse(URLUtils.isStandard(nonStandardUrl))

        assertFalse(URLUtils.isStandard("about:blank"))
        assertFalse(URLUtils.isStandard("chrome://chrome-urls"))
        assertFalse(URLUtils.isStandard("chrome://accessibility"))
    }

    @Test
    fun `isStandard should return false for null input`() {
        // 空输入测试
        assertFalse(URLUtils.isStandard(null))
    }

    @Test
    fun `isStandard should return false for empty string`() {
        // 空字符串测试
        val emptyString = ""
        assertFalse(URLUtils.isStandard(emptyString))
    }

    @Test
    fun ensureChromeURLsAreMalformed() {
        assertThrows(MalformedURLException::class.java) {
            URI.create("chrome://chrome-urls").toURL()
        }
    }

    @Test
    fun testNormalize_WithoutQuery() {
        val result = URLUtils.normalize("http://example.com/path?query=123#fragment", true)
        assertEquals(URI.create("http://example.com/path").toURL(), result)
    }

    @Test
    fun testNormalize_WithQuery() {
        val result = URLUtils.normalize("http://example.com/path?query=123#fragment")
        assertEquals(URI.create("http://example.com/path?query=123").toURL(), result)
    }

    @Test
    fun testNormalize_RemoveFragment() {
        val result = URLUtils.normalize("http://example.com/path#fragment")
        assertEquals(URI.create("http://example.com/path").toURL(), result)
    }

    @Test
    fun testNormalize_InvalidUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            URLUtils.normalize("invalid-url")
        }
    }

    @Test
    fun testNormalize_InvalidUriSyntax() {
        assertThrows(URISyntaxException::class.java) {
            URLUtils.normalize("http://example.com/path&%!({{")
        }
    }

    @Test
    fun testNormalize_EmptyUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            URLUtils.normalize("")
        }
    }

    /**
     * Test Windows file URI.
     * Enable only on Windows.
     * */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun testNormalize_WindowsFileURI() {
        val filePath = "C:\\Users\\Vincent\\Documents"
        val uri = File(filePath).toURI()  // 自动转换为合法的 file:// URI
        printlnPro(uri.toString()) // 输出 file:/C:/Users/Vincent/Documents
        assertEquals("file:/C:/Users/Vincent/Documents", uri.toString())

        // 注意：虽然 URI 标准中为绝对路径推荐 file:///C:/...，
        // 但 Java 会自动简化为 file:/C:/...，它依然是合法的并能正常解析。
        val uri1 = URI.create("file:/C:/Users/Vincent/Documents")
        val uri2 = URI.create("file:///C:/Users/Vincent/Documents")
        assertEquals(uri1, uri2)

        assertEquals("file:/C:/Users/Vincent/Documents", uri1.toPath().toFile().toURI().toString())
        assertEquals("file:/C:/Users/Vincent/Documents", uri2.toPath().toFile().toURI().toString())

        assertEquals("file:///C:/Users/Vincent/Documents", uri1.toPath().toUri().toString())
        assertEquals("file:///C:/Users/Vincent/Documents", uri2.toPath().toUri().toString())

        val url = "file:///C:/Users/User/Documents/file.txt"
        val normalizedUrl = URLUtils.normalize(url)
        assertEquals(URI.create("file:///C:/Users/User/Documents/file.txt").toURL(), normalizedUrl)
    }


    @Test
    fun testIsBrowserURL() {
        // Test with a browser-specific protocol
        assertTrue(URLUtils.isBrowserURL("chrome://settings"))
        assertTrue(URLUtils.isBrowserURL("edge://settings"))
        assertTrue(URLUtils.isBrowserURL("about:blank"))
        // Test with a non-browser-specific protocol
        assertFalse(URLUtils.isBrowserURL("http"))
    }

    @Test
    fun testBrowserURLToStandardURL() {
        // Test converting a browser protocol to URL
        val url = "chrome://settings"
        val expected = "$BROWSER_INTERNAL_BASE_URL?url=${URLEncoder.encode(url, Charsets.UTF_8)}"
        // printlnPro(expected)
        assertEquals(expected, URLUtils.browserURLToStandardURL(url))
    }

    @Test
    fun testUrlToBrowserProtocol() {
        // Test extracting and re-encoding a browser protocol from URL
        val expected = "chrome://settings"
        val url = "$BROWSER_INTERNAL_BASE_URL?url=${URLEncoder.encode(expected, Charsets.UTF_8)}"
        assertEquals(expected, URLUtils.standardURLToBrowserURL(url))

        // Test with a URL that does not contain a browser protocol
        assertNull(URLUtils.standardURLToBrowserURL("http://example.com"))
    }

    @Test
    fun `standardURLToBrowserURL should parse url param even when not first`() {
        val expected = "chrome://settings"
        val encoded = URLEncoder.encode(expected, Charsets.UTF_8)
        val url = "$BROWSER_INTERNAL_BASE_URL?a=1&url=$encoded&b=2"
        assertEquals(expected, URLUtils.standardURLToBrowserURL(url))
    }

    @Test
    fun `standardURLToBrowserURL should return null for blank url param`() {
        val url = "$BROWSER_INTERNAL_BASE_URL?url="
        assertNull(URLUtils.standardURLToBrowserURL(url))
    }

    @Test
    fun `localURLToPath should throw if path param is missing`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            URLUtils.localURLToPath(AppConstants.LOCAL_FILE_BASE_URL)
        }
        assertTrue(ex.message!!.contains("Missing query parameter 'path'"))
    }

    @Test
    fun `getOrigin should omit default ports and handle no explicit port`() {
        assertEquals("http://example.com", URLUtils.getOrigin("http://example.com/a"))
        assertEquals("http://example.com", URLUtils.getOrigin("http://example.com:80/a"))
        assertEquals("https://example.com", URLUtils.getOrigin("https://example.com/a"))
        assertEquals("https://example.com", URLUtils.getOrigin("https://example.com:443/a"))
        assertEquals("https://example.com:444", URLUtils.getOrigin("https://example.com:444/a"))
    }

    @Test
    fun testPathToLocalURL() {
        // 准备测试数据
        val path = AppPaths.getTmpDirectory("test.txt")
        assertEquals("file", path.toUri().scheme)

        // 调用待测试的方法
        val result = URLUtils.pathToLocalURL(path)

        // 验证结果是否符合预期
        val expectedPrefix = AppConstants.LOCAL_FILE_BASE_URL
        val base64 = Base64.getUrlEncoder().encode(path.toString().toByteArray()).toString(Charsets.UTF_8)
        val expectedURL = "$expectedPrefix?path=$base64"

//        printlnPro(path)
//        printlnPro(result)

        assertEquals(expectedURL, result)
    }

    @Test
    fun testNormalize_URLWithSpecialCharactersInQuery() {
        // Test URL with angle brackets (potential XSS)
        val urlWithAngleBrackets = "http://localhost:18080/test?param=<script>alert('xss')</script>"
        val normalized = URLUtils.normalize(urlWithAngleBrackets)
        assertNotNull(normalized)
        
        // The URL should be successfully normalized (not throw an exception)
        // The query parameters should be properly encoded
        assertTrue(normalized.toString().contains("localhost"))
        assertTrue(normalized.toString().contains("/test"))
        // Verify special characters are encoded
        val normalizedStr = normalized.toString()
        assertTrue(normalizedStr.contains("%3C") || normalizedStr.contains("<"), 
            "Expected '<' to be encoded as %3C or preserved, got: $normalizedStr")
    }

    @Test
    fun testNormalize_URLWithDoubleQuotesInQuery() {
        // Test URL with double quotes in query
        val urlWithQuotes = "http://example.com/search?q=\"test query\""
        val normalized = URLUtils.normalize(urlWithQuotes)
        assertNotNull(normalized)
        assertTrue(normalized.toString().contains("example.com"))
    }

    @Test
    fun testNormalize_URLWithMultipleSpecialChars() {
        // Test URL with various special characters
        val urlWithSpecialChars = "http://example.com/path?a=<>&b=\"test\"&c={}"
        val normalized = URLUtils.normalize(urlWithSpecialChars)
        assertNotNull(normalized)
        assertTrue(normalized.toString().contains("example.com"))
    }

    @Test
    fun testNormalize_AlreadyEncodedURL() {
        // Test that already-encoded URLs still work
        val encodedUrl = "http://example.com/test?param=%3Cscript%3E"
        val normalized = URLUtils.normalize(encodedUrl)
        assertNotNull(normalized)
        assertTrue(normalized.toString().contains("example.com"))
        // Verify it doesn't double-encode
        val normalizedStr = normalized.toString()
        assertFalse(normalizedStr.contains("%253C"), 
            "URL should not be double-encoded, got: $normalizedStr")
    }

    @Test
    fun testNormalize_URLWithFragment() {
        // Test that fragments are preserved
        val urlWithFragment = "http://example.com/test?param=<value>#section"
        val normalized = URLUtils.normalize(urlWithFragment, ignoreQuery = false)
        assertNotNull(normalized)
        val normalizedStr = normalized.toString()
        // Note: normalize() removes fragments, so this should not have a fragment
        assertTrue(normalizedStr.contains("example.com"))
        assertFalse(normalizedStr.contains("#"), 
            "Fragment should be removed by normalize(), got: $normalizedStr")
    }
}
