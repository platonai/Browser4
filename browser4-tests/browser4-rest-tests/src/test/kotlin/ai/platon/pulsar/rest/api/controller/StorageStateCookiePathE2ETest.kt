package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallResponse
import ai.platon.pulsar.test.TestUrls
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.*
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.expectBody
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real-browser verification of cookie path scoping through the same MCP
 * storage-state path the CLI `cookie-set` command uses
 * (`browser_load_storage_state` with a cookie entry carrying `url` + `path`).
 *
 * Regression context (0902-storage I1): every `cookie-set --path` invocation
 * used to fail with an opaque backend error ("Invalid cookie fields"), so path
 * scoping was entirely broken.  The CLI now validates the path, maps the error
 * to an actionable message, and the backend normalizes/validates cookie
 * fields in-repo.  What was never re-verified is the real browser behavior of
 * a cookie carrying a DEEP path (e.g. `/ec/dp`) with a URL-scoped cookie —
 * Chrome's `Network.setCookies` derives the default path from the URL and may
 * silently ignore an explicit path, which would make `--path` a no-op.
 *
 * These tests set cookies exactly like the CLI does (name/value + url +
 * path) and then assert the browser actually scopes them by path: the cookie
 * must be visible on pages under the path and invisible on same-origin pages
 * outside it.
 */
@Tag("E2ETest")
class StorageStateCookiePathE2ETest : RestAPITestBase() {

    companion object {
        const val PROFILE_MODE = "SEQUENTIAL"
    }

    private val objectMapper = ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)

    private val createdSessions = mutableListOf<String>()

    @AfterEach
    fun cleanUp() {
        try {
            createdSessions.forEach { sessionId ->
                try { callTool("close_session", mapOf("sessionId" to sessionId)) } catch (_: Exception) { }
            }
        } catch (_: Exception) {
            // best-effort cleanup
        }
        createdSessions.clear()
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun callTool(tool: String, arguments: Map<String, Any?> = emptyMap()): MCPToolCallResponse {
        val request = mapOf("tool" to tool, "arguments" to arguments)
        val body = client.post().uri("/mcp/call-tool")
            .contentType(MediaType.APPLICATION_JSON)
            .body(request)
            .exchange()
            .expectStatus().is2xxSuccessful
            .expectBody<String>()
            .returnResult()
            .responseBody!!

        val tree = objectMapper.readTree(body)
        if (tree is ObjectNode && (tree.get("isError") == null || tree.get("isError").isNull)) {
            tree.put("isError", false)
        }
        return objectMapper.treeToValue(tree, MCPToolCallResponse::class.java)
    }

    private fun textContent(response: MCPToolCallResponse): String =
        response.content.firstOrNull()?.text.orEmpty()

    private fun assertNotError(response: MCPToolCallResponse) {
        assertFalse(
            response.isError,
            "Expected successful MCP response but got: ${textContent(response)}"
        )
    }

    private fun openSession(): String {
        val response = callTool(
            "open_session",
            mapOf(
                "capabilities" to mapOf(
                    "sessionId" to "cookie-path-test",
                    "profileMode" to PROFILE_MODE,
                    "interactLevel" to "FASTEST"
                )
            )
        )
        assertNotError(response)
        val sessionId = objectMapper.readTree(textContent(response)).path("sessionId").asText()
        assertTrue(sessionId.isNotBlank(), "open_session must return a non-blank sessionId")
        createdSessions.add(sessionId)
        return sessionId
    }

    private fun navigate(sessionId: String, url: String) {
        val response = callTool("browser_navigate", mapOf("sessionId" to sessionId, "url" to url))
        assertNotError(response)
    }

    private fun awaitPageTitle(sessionId: String, expectedContains: String, timeoutMs: Long = 15000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastTitle = ""
        while (System.currentTimeMillis() < deadline) {
            val result = callTool(
                "browser_evaluate",
                mapOf("sessionId" to sessionId, "expression" to "document.title")
            )
            if (!result.isError) {
                lastTitle = textContent(result)
                if (lastTitle.contains(expectedContains)) return
            }
            Thread.sleep(500)
        }
        throw AssertionError(
            "Timed out waiting for page title containing '$expectedContains', last: '$lastTitle'"
        )
    }

    private fun currentUrl(sessionId: String): String {
        val result = callTool(
            "browser_evaluate",
            mapOf("sessionId" to sessionId, "expression" to "document.URL")
        )
        assertNotError(result)
        return textContent(result).trim()
    }

    private fun documentCookie(sessionId: String): String {
        val result = callTool(
            "browser_evaluate",
            mapOf("sessionId" to sessionId, "expression" to "document.cookie")
        )
        assertNotError(result)
        return textContent(result).trim()
    }

    /**
     * Set one cookie through the same MCP storage-state payload the CLI
     * `cookie-set` command builds (url-scoped entry with an explicit path).
     */
    private fun setCookieViaStorageState(sessionId: String, url: String, name: String, path: String) {
        val state = """{"cookies":[{"name":"$name","value":"1","url":"$url","path":"$path"}],"origins":[]}"""
        val response = callTool(
            "browser_load_storage_state",
            mapOf("sessionId" to sessionId, "state" to state)
        )
        assertNotError(response)
    }

    /** Domain-scoped variant — mirrors `cookie-set <name> <value> --domain <host> --path <path>`. */
    private fun setDomainCookieViaStorageState(sessionId: String, domain: String, name: String, path: String) {
        val state = """{"cookies":[{"name":"$name","value":"1","domain":"$domain","path":"$path"}],"origins":[]}"""
        val response = callTool(
            "browser_load_storage_state",
            mapOf("sessionId" to sessionId, "state" to state)
        )
        assertNotError(response)
    }

    // =========================================================================
    // Path scoping
    // =========================================================================

    @Test
    @DisplayName("cookie with a deep path is honored and scoped by the browser")
    fun deepPathCookieIsScopedByPath() {
        val sessionId = openSession()
        navigate(sessionId, TestUrls.MOCK_PRODUCT_DETAIL_URL) // path /ec/dp/B0E000001
        awaitPageTitle(sessionId, "4K OLED TV")
        val productUrl = currentUrl(sessionId)

        // Mirrors `cookie-set deepcookie 1 --path /ec/dp` on the product page.
        setCookieViaStorageState(sessionId, productUrl, "deepcookie", "/ec/dp")

        // Visible on the current page (under /ec/dp).
        assertTrue(
            documentCookie(sessionId).contains("deepcookie=1"),
            "The deep-path cookie must be visible on a page under its path"
        )

        // Invisible on a same-origin page OUTSIDE the path — the browser must
        // honor the explicit path instead of silently defaulting it to '/' from
        // the URL (which would make --path a no-op).
        navigate(sessionId, TestUrls.MOCK_NEWS_URL) // path /htmlsnapshot-test/news
        awaitPageTitle(sessionId, "Hacker News")
        assertFalse(
            documentCookie(sessionId).contains("deepcookie"),
            "The deep-path cookie must NOT be visible on a same-origin page outside its path; " +
                "if it is, the explicit path was ignored (defaulted to '/')"
        )

        // Still present — navigating back under the path shows it again without re-setting.
        navigate(sessionId, TestUrls.MOCK_PRODUCT_DETAIL_URL)
        awaitPageTitle(sessionId, "4K OLED TV")
        assertTrue(
            documentCookie(sessionId).contains("deepcookie=1"),
            "The deep-path cookie must still be present after navigating back under its path"
        )
    }

    @Test
    @DisplayName("root-path cookie is visible on every page of the origin (contrast)")
    fun rootPathCookieVisibleEverywhere() {
        val sessionId = openSession()
        navigate(sessionId, TestUrls.MOCK_PRODUCT_DETAIL_URL)
        awaitPageTitle(sessionId, "4K OLED TV")

        // Mirrors `cookie-set rootcookie 1 --path /` (the CLI-documented case).
        setCookieViaStorageState(sessionId, currentUrl(sessionId), "rootcookie", "/")

        assertTrue(
            documentCookie(sessionId).contains("rootcookie=1"),
            "The root-path cookie must be visible on the page it was set for"
        )

        navigate(sessionId, TestUrls.MOCK_NEWS_URL)
        awaitPageTitle(sessionId, "Hacker News")
        assertTrue(
            documentCookie(sessionId).contains("rootcookie=1"),
            "The root-path cookie must be visible on every same-origin page"
        )
    }

    @Test
    @DisplayName("domain-scoped cookie with a deep path is honored (the original --domain --path repro)")
    fun domainScopedDeepPathCookieIsScopedByPath() {
        val sessionId = openSession()
        navigate(sessionId, TestUrls.MOCK_PRODUCT_DETAIL_URL)
        awaitPageTitle(sessionId, "4K OLED TV")

        // The exact combination from the original defect report:
        // `cookie-set session abc123 --domain localhost --path /ec/dp` used to
        // fail with an opaque "Invalid cookie fields" backend error.
        setDomainCookieViaStorageState(sessionId, "localhost", "domaincookie", "/ec/dp")

        assertTrue(
            documentCookie(sessionId).contains("domaincookie=1"),
            "The domain+path cookie must be visible on a page under its path"
        )

        navigate(sessionId, TestUrls.MOCK_NEWS_URL)
        awaitPageTitle(sessionId, "Hacker News")
        assertFalse(
            documentCookie(sessionId).contains("domaincookie"),
            "The domain+path cookie must NOT be visible on a same-origin page outside its path"
        )
    }
}
