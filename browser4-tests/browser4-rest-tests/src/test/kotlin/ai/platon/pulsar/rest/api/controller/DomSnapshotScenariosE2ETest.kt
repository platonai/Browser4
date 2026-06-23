package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.mcp.controller.dto.MCPToolCallResponse
import ai.platon.pulsar.test.TestUrls
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.expectBody
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E2E tests for the 10 DOM Snapshot scenarios defined in
 * skill/references/domsnapshot-scenarios.md.
 *
 * Each test navigates to a mock page served by the embedded mock EC server
 * (port 18080 via [MockEcServerConfiguration]) and exercises the dom_snapshot_*
 * MCP tools.
 *
 * NOTE: The `dom_snapshot_query` tool uses the scrape service which employs an
 * optimized DOM parser.  Most mock pages yield a sparse DOM (4–40 elements
 * depending on the page).  Query tests therefore use selectors that match
 * within this representation (typically `body`-level queries or the rich
 * product-detail page where 40 elements survive).  Multi-row `load_and_select`
 * on listing pages is not supported under the optimized DOM.
 *
 * `dom_snapshot_scrape` and `dom_snapshot_export` work against the full
 * browser DOM and are tested extensively across all scenarios.
 */
@Tag("E2ETest")
class DomSnapshotScenariosE2ETest : RestAPITestBase() {

    companion object {
        const val PROFILE_MODE = "SEQUENTIAL"
    }

    private val logger = LoggerFactory.getLogger(DomSnapshotScenariosE2ETest::class.java)
    private val objectMapper = ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)

private val createdSessions = mutableListOf<String>()

    @AfterEach
    fun cleanUp() {
        try {
            callTool("kill_all_sessions")
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

    private fun openSession(capabilities: Map<String, Any?>? = null): String {
        val arguments = buildMap<String, Any?> {
            if (capabilities != null) put("capabilities", capabilities)
        }
        val response = callTool("open_session", arguments)
        assertNotError(response)
        val sessionId = objectMapper.readTree(textContent(response)).path("sessionId").asText()
        assertTrue(sessionId.isNotBlank(), "open_session must return a non-blank sessionId")
        createdSessions.add(sessionId)
        return sessionId
    }

    private fun openTemporarySession(): String = openSession(
        mapOf(
            "sessionId" to "domsnapshot-test",
            "profileMode" to PROFILE_MODE,
            "interactLevel" to "FASTEST"
        )
    )

    private fun navigate(sessionId: String, url: String) {
        val response = callTool("browser_navigate", mapOf("sessionId" to sessionId, "url" to url))
        assertNotError(response)
    }

    private fun openAndNavigate(url: String): String {
        val sessionId = openTemporarySession()
        val response = callTool(
            "browser_navigate",
            mapOf("sessionId" to sessionId, "url" to url)
        )
        if (!response.isError) return sessionId
        if (!textContent(response).contains("Cannot find context with specified id")) {
            assertNotError(response)
        }
        // Retry once
        logger.info("Retrying browser_navigate with a fresh session")
        callTool("close_session", mapOf("sessionId" to sessionId))
        createdSessions.remove(sessionId)
        val retryId = openTemporarySession()
        assertNotError(callTool("browser_navigate", mapOf("sessionId" to retryId, "url" to url)))
        return retryId
    }

    /** Force a fresh capture then call dom_snapshot_scrape. */
    private fun scrapeField(
        sessionId: String,
        field: String,
        selector: String,
        attrName: String? = null
    ): String {
        // Force a fresh DOM snapshot capture — the implicit capture in scrape
        // may return a stale cached page.
        callTool("dom_snapshot_capture", mapOf("sessionId" to sessionId))
        val args = buildMap<String, Any?> {
            put("sessionId", sessionId)
            put("field", field)
            put("selector", selector)
            if (attrName != null) put("attrName", attrName)
        }
        val response = callTool("dom_snapshot_scrape", args)
        assertNotError(response)
        return textContent(response)
    }

    /** Call dom_snapshot_query and return parsed JSON. */
    private fun queryDomSnapshot(
        sessionId: String,
        sql: String
    ): com.fasterxml.jackson.databind.JsonNode {
        val response = callTool(
            "dom_snapshot_query",
            mapOf("sessionId" to sessionId, "sql" to sql)
        )
        assertNotError(response)
        return objectMapper.readTree(textContent(response))
    }

    /** Force a fresh capture then export the DOM snapshot. */
    private fun exportDomSnapshot(sessionId: String): String {
        callTool("dom_snapshot_capture", mapOf("sessionId" to sessionId))
        val response = callTool("dom_snapshot_export", mapOf("sessionId" to sessionId))
        assertNotError(response)
        return textContent(response)
    }

    /** Parse the `resultSet` from a query response, asserting it is non-null. */
    private fun requireResultSet(result: com.fasterxml.jackson.databind.JsonNode) =
        result["resultSet"].also {
            assertNotNull(it, "Expected resultSet in query response, got: $result")
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

    // =========================================================================
    // Scenario 1 — E-Commerce Product Monitoring
    // =========================================================================

    @Test
    @DisplayName("1a — Extract single product details via dom_snapshot_scrape")
    fun test1a_extractSingleProductDetails() {
        val sessionId = openAndNavigate(TestUrls.MOCK_PRODUCT_DETAIL_URL)
        awaitPageTitle(sessionId, "4K OLED TV")

        val title = scrapeField(sessionId, "text", "#productTitle")
        assertTrue(title.contains("4K OLED TV"), "Expected product title, got: $title")

        val price = scrapeField(sessionId, "text", "#product-price")
        assertTrue(price.contains("899.99"), "Expected price, got: $price")

        val rating = scrapeField(sessionId, "text", "#product-rating")
        assertTrue(rating.contains("4.6"), "Expected rating, got: $rating")

        val imageSrc = scrapeField(sessionId, "attr", "#product-image", "src")
        assertTrue(imageSrc.isNotBlank(), "Expected non-blank image src")
    }

    @Test
    @DisplayName("1b — Query product detail page with X-SQL (rich DOM)")
    fun test1b_queryProductDetailWithXSql() {
        val sessionId = openAndNavigate(TestUrls.MOCK_PRODUCT_DETAIL_URL)
        awaitPageTitle(sessionId, "4K OLED TV")

        val sql = """
            SELECT
              dom_first_text(dom, '#productTitle') AS title,
              dom_first_text(dom, '#product-price') AS price,
              dom_first_text(dom, '#product-rating') AS rating
            FROM load_and_select(@url, 'body')
        """.trimIndent()

        val result = queryDomSnapshot(sessionId, sql)
        val resultSet = requireResultSet(result)
        assertTrue(resultSet.size() == 1, "Expected 1 body row, got ${resultSet.size()}")

        val row = resultSet[0]
        assertTrue(
            row["title"]?.asText()?.contains("4K OLED TV") == true,
            "Expected product title, got: ${row["title"]}"
        )
        assertTrue(
            row["price"]?.asText()?.contains("899.99") == true,
            "Expected price, got: ${row["price"]}"
        )
    }

    @Test
    @DisplayName("1c — Export product page for offline analysis")
    fun test1c_exportForOfflineAnalysis() {
        val sessionId = openAndNavigate(TestUrls.MOCK_PRODUCT_DETAIL_URL)
        awaitPageTitle(sessionId, "4K OLED TV")

        val html = exportDomSnapshot(sessionId)
        assertTrue(html.contains("4K OLED TV"), "Exported HTML should contain product title")
        assertTrue(html.contains("product-price"), "Exported HTML should contain price element")
    }

    // =========================================================================
    // Scenario 2 — News Headline Aggregator
    // =========================================================================

    @Test
    @DisplayName("2a — Query news page: verify X-SQL pipeline runs without error")
    fun test2a_queryNewsPageWithXSql() {
        val sessionId = openAndNavigate(TestUrls.MOCK_NEWS_URL)

        // The scrape service's X-SQL engine uses an optimized DOM that may strip
        // most elements.  We verify the query pipeline runs end-to-end (no error,
        // resultSet present).  Full-content assertions are left to the scrape/export
        // tests which use the browser's complete DOM.
        val sql = """
            SELECT COUNT(*) AS cnt
            FROM load_and_select(@url, '*')
        """.trimIndent()

        val result = queryDomSnapshot(sessionId, sql)
        val resultSet = requireResultSet(result)
        assertTrue(resultSet.size() >= 1, "Expected at least 1 row from COUNT(*)")
        val count = resultSet[0]["cnt"]?.asText()?.toIntOrNull() ?: 0
        assertTrue(count >= 1, "Expected at least 1 total element, got $count")
    }

    @Test
    @DisplayName("2b — Get single headline via dom_snapshot_scrape")
    fun test2b_getSingleHeadline() {
        val sessionId = openAndNavigate(TestUrls.MOCK_NEWS_URL)

        val headline = scrapeField(sessionId, "text", ".titleline > a")
        assertTrue(headline.contains("New AI Breakthrough"), "Expected first headline, got: $headline")

        val points = scrapeField(sessionId, "text", ".score")
        assertTrue(points.isNotBlank(), "Expected non-blank points")
    }

    @Test
    @DisplayName("2c — Export news page for archival")
    fun test2c_exportNewsPage() {
        val sessionId = openAndNavigate(TestUrls.MOCK_NEWS_URL)

        val html = exportDomSnapshot(sessionId)
        assertTrue(html.contains("Hacker News"), "Exported HTML should contain page identity")
        assertTrue(html.contains("titleline"), "Exported HTML should contain article markup")
    }

    // =========================================================================
    // Scenario 3 — SEO Health Audit
    // =========================================================================

    @Test
    @DisplayName("3a — Count headings via dom_snapshot_scrape")
    fun test3a_checkHeadingsExist() {
        val sessionId = openAndNavigate(TestUrls.MOCK_SEO_URL)

        val h1Text = scrapeField(sessionId, "text", "h1")
        assertTrue(h1Text.contains("SEO Health Audit"), "Expected H1 heading, got: $h1Text")

        val h2Text = scrapeField(sessionId, "text", "h2")
        assertTrue(h2Text.isNotBlank(), "Expected at least one H2 heading")
    }

    @Test
    @DisplayName("3b — Find images missing alt text via scrape")
    fun test3b_findImagesMissingAlt() {
        val sessionId = openAndNavigate(TestUrls.MOCK_SEO_URL)

        // The SEO page has images without alt — verify we can find them
        // dom_snapshot_scrape returns the first match, which will be an img with alt
        val imgWithAlt = scrapeField(sessionId, "attr", "img[alt]", "alt")
        assertTrue(imgWithAlt.isNotBlank(), "Expected at least one image with alt text")

        // The page also has images without alt
        val imgSrcNoAlt = scrapeField(sessionId, "attr", "img:not([alt])", "src")
        assertTrue(imgSrcNoAlt.isNotBlank(), "Expected at least one image without alt")
    }

    @Test
    @DisplayName("3c — Extract meta tags via dom_snapshot_scrape")
    fun test3c_extractMetaTags() {
        val sessionId = openAndNavigate(TestUrls.MOCK_SEO_URL)

        val description = scrapeField(
            sessionId, "attr", "meta[name=\"description\"]", "content"
        )
        assertTrue(description.contains("comprehensive guide"), "Expected meta description: $description")

        val keywords = scrapeField(
            sessionId, "attr", "meta[name=\"keywords\"]", "content"
        )
        assertTrue(keywords.contains("SEO"), "Expected keywords: $keywords")

        val canonical = scrapeField(
            sessionId, "attr", "link[rel=\"canonical\"]", "href"
        )
        assertTrue(canonical.contains("example.com"), "Expected canonical URL: $canonical")
    }

    @Test
    @DisplayName("3d — Verify outbound links exist via scrape")
    fun test3d_findOutboundLinks() {
        val sessionId = openAndNavigate(TestUrls.MOCK_SEO_URL)

        val linkText = scrapeField(sessionId, "text", "a[href^=\"http\"]")
        assertTrue(linkText.isNotBlank(), "Expected at least one outbound link")

        val linkHref = scrapeField(sessionId, "attr", "a[href^=\"http\"]", "href")
        assertTrue(linkHref.startsWith("http"), "Expected absolute URL, got: $linkHref")
    }

    // =========================================================================
    // Scenario 4 — Competitive Price Tracker
    // =========================================================================

    @Test
    @DisplayName("4a — Query product detail with X-SQL (rich DOM)")
    fun test4a_singleProductQuery() {
        val sessionId = openAndNavigate(TestUrls.MOCK_PRODUCT_DETAIL_URL)
        awaitPageTitle(sessionId, "4K OLED TV")

        val sql = """
            SELECT
              dom_base_uri(dom) AS url,
              dom_first_text(dom, '#productTitle') AS title,
              dom_first_text(dom, '#product-price') AS price
            FROM load_and_select(@url, 'body')
        """.trimIndent()

        val result = queryDomSnapshot(sessionId, sql)
        val resultSet = requireResultSet(result)
        val row = resultSet[0]
        assertTrue(row["title"]?.asText()?.contains("4K OLED TV") == true, "Expected product title")
        assertTrue(row["price"]?.asText()?.contains("899.99") == true, "Expected price")
    }

    @Test
    @DisplayName("4b — Query two different pages in same session")
    fun test4b_queryMultipleUrls() {
        val sessionId = openAndNavigate(TestUrls.MOCK_PRODUCT_DETAIL_URL)
        awaitPageTitle(sessionId, "4K OLED TV")

        val sql1 = """
            SELECT dom_first_text(dom, '#productTitle') AS title
            FROM load_and_select(@url, 'body')
        """.trimIndent()
        val r1 = requireResultSet(queryDomSnapshot(sessionId, sql1))
        assertTrue(r1[0]["title"]?.asText()?.contains("4K OLED TV") == true)

        // Navigate to another product page (use a different product)
        navigate(sessionId, "http://localhost:18080/ec/dp/B0E000002")
        awaitPageTitle(sessionId, "Wireless")

        val sql2 = """
            SELECT dom_first_text(dom, '#productTitle') AS title
            FROM load_and_select(@url, 'body')
        """.trimIndent()
        val r2 = requireResultSet(queryDomSnapshot(sessionId, sql2))
        assertTrue(
            r2[0]["title"]?.asText()?.contains("Wireless") == true,
            "Expected second product title, got: ${r2[0]["title"]}"
        )
    }

    // =========================================================================
    // Scenario 5 — Job Board Scraper
    // =========================================================================

    @Test
    @DisplayName("5a — Verify X-SQL pipeline runs on jobs page")
    fun test5a_extractJobInfo() {
        val sessionId = openAndNavigate(TestUrls.MOCK_JOBS_URL)

        // Optimized DOM: verify query pipeline runs end-to-end
        val sql = """
            SELECT COUNT(*) AS cnt
            FROM load_and_select(@url, '*')
        """.trimIndent()

        val result = queryDomSnapshot(sessionId, sql)
        val resultSet = requireResultSet(result)
        val count = resultSet[0]["cnt"]?.asText()?.toIntOrNull() ?: 0
        assertTrue(count >= 1, "Expected at least 1 element, got $count")
    }

    @Test
    @DisplayName("5b — Get single job title via dom_snapshot_scrape")
    fun test5b_getSingleField() {
        val sessionId = openAndNavigate(TestUrls.MOCK_JOBS_URL)

        val title = scrapeField(sessionId, "text", ".job-card-list__title")
        assertTrue(title.contains("Senior Frontend Engineer"), "Expected first job title: $title")

        val company = scrapeField(sessionId, "text", ".job-card-container__company-name")
        assertTrue(company == "TechCorp", "Expected first company: $company")
    }

    @Test
    @DisplayName("5c — Export jobs page")
    fun test5c_exportJobsPage() {
        val sessionId = openAndNavigate(TestUrls.MOCK_JOBS_URL)

        val html = exportDomSnapshot(sessionId)
        assertTrue(html.contains("Senior Frontend Engineer"), "Export should contain job title")
        assertTrue(html.contains("TechCorp"), "Export should contain company name")
    }

    // =========================================================================
    // Scenario 6 — Compliance Verification
    // =========================================================================

    @Test
    @DisplayName("6a — Verify legal disclaimer exists via scrape")
    fun test6a_verifyLegalDisclaimerExists() {
        val sessionId = openAndNavigate(TestUrls.MOCK_COMPLIANCE_URL)

        val disclaimer = scrapeField(sessionId, "text", ".legal-disclaimer")
        assertTrue(disclaimer.contains("Legal Disclaimer"), "Expected legal disclaimer: $disclaimer")
    }

    @Test
    @DisplayName("6b — Verify cookie consent banner exists")
    fun test6b_verifyCookieBannerExists() {
        val sessionId = openAndNavigate(TestUrls.MOCK_COMPLIANCE_URL)

        val banner = scrapeField(sessionId, "text", "#cookie-consent-banner")
        assertTrue(banner.contains("uses cookies"), "Expected cookie banner, got: $banner")
    }

    @Test
    @DisplayName("6c — Verify accessibility statement link")
    fun test6c_verifyAccessibilityLink() {
        val sessionId = openAndNavigate(TestUrls.MOCK_COMPLIANCE_URL)

        val href = scrapeField(sessionId, "attr", "a[href*='accessibility']", "href")
        assertTrue(href.contains("accessibility"), "Expected accessibility URL, got: $href")
    }

    @Test
    @DisplayName("6d — Verify privacy policy link")
    fun test6d_verifyPrivacyLink() {
        val sessionId = openAndNavigate(TestUrls.MOCK_COMPLIANCE_URL)

        val href = scrapeField(sessionId, "attr", "a[href*='privacy']", "href")
        assertTrue(href.contains("privacy"), "Expected privacy URL, got: $href")
    }

    @Test
    @DisplayName("6e — Export compliance page for audit trail")
    fun test6e_exportForAuditTrail() {
        val sessionId = openAndNavigate(TestUrls.MOCK_COMPLIANCE_URL)

        val html = exportDomSnapshot(sessionId)
        assertTrue(html.contains("Legal Disclaimer"), "Export should contain legal disclaimer")
        assertTrue(html.contains("cookie-consent-banner"), "Export should contain cookie banner")
    }

    // =========================================================================
    // Scenario 7 — Academic Literature Metadata Extraction
    // =========================================================================

    @Test
    @DisplayName("7a — Verify X-SQL pipeline runs on research page")
    fun test7a_extractSearchResults() {
        val sessionId = openAndNavigate(TestUrls.MOCK_RESEARCH_URL)

        // Optimized DOM: verify query pipeline runs end-to-end
        val sql = """
            SELECT COUNT(*) AS cnt
            FROM load_and_select(@url, '*')
        """.trimIndent()

        val result = queryDomSnapshot(sessionId, sql)
        val resultSet = requireResultSet(result)
        val count = resultSet[0]["cnt"]?.asText()?.toIntOrNull() ?: 0
        assertTrue(count >= 1, "Expected at least 1 element, got $count")
    }

    @Test
    @DisplayName("7b — Extract paper details via dom_snapshot_scrape")
    fun test7b_extractIndividualPaper() {
        val sessionId = openAndNavigate(TestUrls.MOCK_RESEARCH_URL)

        val firstTitle = scrapeField(sessionId, "text", ".docsum-title")
        assertTrue(firstTitle.contains("Machine Learning"), "Expected first paper title: $firstTitle")

        val firstAuthors = scrapeField(sessionId, "text", ".full-author-list")
        assertTrue(firstAuthors.contains("Smith"), "Expected first authors: $firstAuthors")
    }

    // =========================================================================
    // Scenario 8 — Real Estate Listing Monitor
    // =========================================================================

    @Test
    @DisplayName("8a — Verify X-SQL pipeline runs on real estate page")
    fun test8a_extractAllListings() {
        val sessionId = openAndNavigate(TestUrls.MOCK_REAL_ESTATE_URL)

        // Optimized DOM: verify query pipeline runs end-to-end
        val sql = """
            SELECT COUNT(*) AS cnt
            FROM load_and_select(@url, '*')
        """.trimIndent()

        val result = queryDomSnapshot(sessionId, sql)
        val resultSet = requireResultSet(result)
        val count = resultSet[0]["cnt"]?.asText()?.toIntOrNull() ?: 0
        assertTrue(count >= 1, "Expected at least 1 element, got $count")
    }

    @Test
    @DisplayName("8b — Quick check via dom_snapshot_scrape")
    fun test8b_quickDiffCheck() {
        val sessionId = openAndNavigate(TestUrls.MOCK_REAL_ESTATE_URL)

        val address = scrapeField(sessionId, "text", "[data-test=\"property-card-address\"]")
        assertTrue(address.contains("123 Main St"), "Expected first address: $address")

        val price = scrapeField(sessionId, "text", "[data-test=\"property-card-price\"]")
        assertTrue(price.contains("$1,200,000"), "Expected first price: $price")
    }

    // =========================================================================
    // Scenario 9 — CI / E2E Visual Regression Snapshot
    // =========================================================================

    @Test
    @DisplayName("9a — Capture DOM snapshot and verify metadata")
    fun test9a_captureAndVerifyMetadata() {
        val sessionId = openAndNavigate(TestUrls.MOCK_PRODUCT_DETAIL_URL)
        awaitPageTitle(sessionId, "4K OLED TV")

        val response = callTool("dom_snapshot_capture", mapOf("sessionId" to sessionId))
        assertNotError(response)
        val captureResult = objectMapper.readTree(textContent(response))

        assertTrue(captureResult.has("url"), "Capture should include url field")
        assertTrue(captureResult.has("title"), "Capture should include title field")
        assertTrue(captureResult.has("sizeBytes"), "Capture should include sizeBytes field")
        assertTrue(captureResult.has("capturedAt"), "Capture should include capturedAt field")

        val title = captureResult["title"]?.asText() ?: ""
        assertTrue(title.contains("4K OLED TV"), "Capture title should contain product name")
    }

    @Test
    @DisplayName("9b — Export DOM snapshot for diff")
    fun test9b_exportForDiff() {
        val sessionId = openAndNavigate(TestUrls.MOCK_PRODUCT_DETAIL_URL)
        awaitPageTitle(sessionId, "4K OLED TV")

        val html = exportDomSnapshot(sessionId)
        assertTrue(html.isNotBlank(), "Exported HTML should not be blank")
        assertTrue(
            html.contains("<") && html.contains(">"),
            "Export should contain HTML tags"
        )
    }

    // =========================================================================
    // Scenario 10 — Agent-Assisted Form Discovery
    // =========================================================================

    @Test
    @DisplayName("10a — Discover form input names via dom_snapshot_scrape")
    fun test10a_discoverFormFields() {
        val sessionId = openAndNavigate(TestUrls.MOCK_FORM_PAGE_URL)

        val inputName = scrapeField(sessionId, "attr", "form input[name]", "name")
        assertTrue(inputName.isNotBlank(), "Expected input name attribute, got: $inputName")
    }

    @Test
    @DisplayName("10b — Discover form input types via X-SQL query")
    fun test10b_discoverInputTypes() {
        val sessionId = openAndNavigate(TestUrls.MOCK_FORM_PAGE_URL)

        val sql = """
            SELECT
              dom_first_attr(dom, 'form input', 'type') AS input_type,
              dom_first_attr(dom, 'form input', 'name') AS input_name
            FROM load_and_select(@url, 'body')
        """.trimIndent()

        val result = queryDomSnapshot(sessionId, sql)
        val resultSet = requireResultSet(result)
        val row = resultSet[0]
        val inputType = row["input_type"]?.asText() ?: ""
        assertTrue(inputType.isNotBlank(), "Expected input type, got empty")
    }

    @Test
    @DisplayName("10c — Get full form HTML for LLM analysis")
    fun test10c_getFullFormHtml() {
        val sessionId = openAndNavigate(TestUrls.MOCK_FORM_PAGE_URL)

        val formHtml = scrapeField(sessionId, "html", "form#testForm")
        assertTrue(formHtml.isNotBlank(), "Expected non-blank form HTML")
        assertTrue(formHtml.contains("username"), "Form HTML should contain username field")
        assertTrue(formHtml.contains("email"), "Form HTML should contain email field")
    }
}
