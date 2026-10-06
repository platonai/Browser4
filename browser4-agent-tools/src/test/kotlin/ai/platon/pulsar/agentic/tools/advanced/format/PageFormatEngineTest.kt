package ai.platon.pulsar.agentic.tools.advanced.format

import ai.platon.pulsar.skeleton.workflow.format.FormatContext
import ai.platon.pulsar.skeleton.workflow.format.FormatInput
import ai.platon.pulsar.skeleton.workflow.format.FormatOptionSchema
import ai.platon.pulsar.skeleton.workflow.format.PageFormat
import ai.platon.pulsar.skeleton.workflow.format.PageFormatContributor
import ai.platon.pulsar.skeleton.workflow.format.PageFormatContributorRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration

private const val KEY = "https://example.com/p"
private const val HREF = "https://example.com/p"
private const val CAPTURED_AT = "2026-10-06T00:00:00Z"

private const val CLEAN_HTML = """<h1>Title</h1><p>Body <a href="/x">link</a></p>"""
private const val RAW_HTML = "<html><body><p>raw</p></body></html>"
private const val READABILITY_JSON =
    """{"url":"$HREF","title":"T","byline":"B","siteName":"S","excerpt":"E","length":7,""" +
        """"confidence":0.9,"textContent":"Article","content":"<p>Article</p>"}"""
private const val LINKS_JSON = """["$HREF/x","$HREF/y","$HREF/x"]"""
private const val IMAGES_JSON = """["$HREF/a.png"]"""
private const val ATTRIBUTES_JSON = """["10","20"]"""
private const val QUERY_JSON = """{"isDone":true,"resultSet":[{"title":"T","price":10}]}"""
private const val ARTIFACT_DIR = "/tmp/web/screenshot"
private const val SCREENSHOT_BASE64 = "aGVsbG8="

private val SNAPSHOT_TOOLS = setOf(
    "html_snapshot.export",
    "html_snapshot.readability",
    "html_snapshot.scrape_all",
    "html_snapshot.query",
)

private val ALL_TOOLS = SNAPSHOT_TOOLS + "tab.screenshot"

@DisplayName("PageFormatEngine")
class PageFormatEngineTest {

    /**
     * A host that records what was asked of it and answers from a lookup table.
     *
     * Keying responses by the arguments (not just the method) is what lets the
     * tests tell the cleaned export from the raw one — the distinction the
     * `html` and `rawHtml` formats depend on.
     */
    private class FakeRunner(
        private val supported: Set<String> = SNAPSHOT_TOOLS,
        private val responses: Map<String, String> = emptyMap(),
        private val failing: Set<String> = emptySet(),
        /** A host with nowhere to put bytes — the engine must degrade, not fail. */
        private val persistFails: Boolean = false,
    ) : FormatStepRunner {

        var captures = 0
            private set

        /** Every call in execution order, as `capture` / `read:<key>` / `live:<key>`. */
        val calls = mutableListOf<String>()

        /** The snapshot each read was bound to. */
        val readSnapshots = mutableListOf<FormatSnapshot>()

        val liveCalls = mutableListOf<String>()

        /** Artifacts the engine asked this host to write, as `hint.extension`. */
        val persisted = mutableListOf<String>()

        override suspend fun acquireSnapshot(expires: Duration): FormatSnapshot {
            captures++
            calls += "capture"
            return FormatSnapshot(KEY, HREF, CAPTURED_AT, "miss")
        }

        override suspend fun readOnSnapshot(
            snapshot: FormatSnapshot,
            domain: String,
            method: String,
            args: Map<String, Any?>,
        ): String {
            val key = lookupKey(domain, method, args)
            calls += "read:$key"
            readSnapshots += snapshot
            if (key in failing) throw IllegalStateException("boom $key")
            return responses[key] ?: ""
        }

        override suspend fun runOnTab(domain: String, method: String, args: Map<String, Any?>): String {
            val key = lookupKey(domain, method, args)
            calls += "live:$key"
            liveCalls += key
            if (key in failing) throw IllegalStateException("boom $key")
            return responses[key] ?: ""
        }

        override suspend fun persistArtifact(nameHint: String, base64: String, extension: String): String {
            if (persistFails) throw UnsupportedOperationException("this host has nowhere to put bytes")
            persisted += "$nameHint.$extension"
            calls += "persist:$nameHint"
            return "$ARTIFACT_DIR/$nameHint.$extension"
        }

        override fun supports(domain: String, method: String): Boolean = "$domain.$method" in supported

        private fun lookupKey(domain: String, method: String, args: Map<String, Any?>): String = when (method) {
            "export" -> if (args["clean"] == true) "export.clean" else "export.raw"
            "scrape_all" -> "scrape_all:${args["selector"]}:${args["attrName"]}"
            else -> "$domain.$method"
        }
    }

    private fun responses(): Map<String, String> = mapOf(
        "export.clean" to CLEAN_HTML,
        "export.raw" to RAW_HTML,
        "html_snapshot.readability" to READABILITY_JSON,
        "scrape_all:a:href" to LINKS_JSON,
        "scrape_all:img:src" to IMAGES_JSON,
        "scrape_all:.price:data-amount" to ATTRIBUTES_JSON,
        "html_snapshot.query" to QUERY_JSON,
    )

    private fun formats(vararg raw: Any?): List<PageFormat> =
        FormatOptionSchema.parse(raw.toList()).requireValid()

    private fun request(vararg raw: Any?, onlyMainContent: Boolean = true): PageScrapeRequest =
        PageScrapeRequest(
            formats = formats(*raw),
            onlyMainContent = onlyMainContent,
        )

    // ---- capture-once -------------------------------------------------------

    @Test
    @DisplayName("I1: eight formats cost exactly one capture")
    fun capturesOnceForManyFormats() = runBlocking {
        val runner = FakeRunner(responses = responses())
        val document = PageFormatEngine(runner).scrape(
            request(
                "markdown", "html", "rawHtml", "links", "images", "readability",
                mapOf(
                    "type" to "attributes",
                    "selectors" to listOf(mapOf("selector" to ".price", "attribute" to "data-amount")),
                ),
                mapOf("type" to "deterministicJson", "sql" to "select 1"),
            )
        )

        assertEquals(1, runner.captures, "the whole request must capture once: ${runner.calls}")
        assertEquals("capture", runner.calls.first())
        assertEquals(1, runner.calls.count { it == "capture" })
        assertNotNull(document.markdown)
    }

    @Test
    @DisplayName("I2: every snapshot read is bound to the captured snapshot")
    fun readsAreBoundToTheSnapshot() = runBlocking {
        val runner = FakeRunner(responses = responses())
        PageFormatEngine(runner).scrape(request("markdown", "links"))

        assertTrue(runner.readSnapshots.isNotEmpty())
        assertTrue(runner.readSnapshots.all { it.key == KEY && it.href == HREF })
        assertTrue(runner.liveCalls.isEmpty(), "snapshot formats must not touch the tab")
    }

    @Test
    @DisplayName("I3: a failing live step degrades without breaking snapshot formats")
    fun liveFailureDoesNotBreakSnapshotFormats() = runBlocking {
        val runner = FakeRunner(
            supported = SNAPSHOT_TOOLS + "tab.screenshot",
            responses = responses(),
            failing = setOf("tab.screenshot"),
        )
        val base = PageFormatPlanBuilder.build(formats("markdown"), FormatOptions())
        val withScreenshot = base.copy(
            requested = base.requested + "screenshot",
            steps = base.steps + FormatStep(
                format = "screenshot",
                stage = FormatStage.LIVE_TAB,
                domain = "tab",
                method = "screenshot",
                policy = StepPolicy.DEGRADABLE,
            ),
        )

        val document = PageFormatEngine(runner).scrape(request("markdown"), withScreenshot)

        assertNotNull(document.markdown, "markdown must survive a failed screenshot")
        assertTrue(document.warning!!.contains("screenshot"), document.warning)
        assertTrue(document.warning!!.contains("boom"), document.warning)
        assertEquals(1, runner.captures)
        // The tab work ran only after every snapshot read was done.
        assertEquals("live:tab.screenshot", runner.calls.last())
    }

    @Test
    @DisplayName("a plan that needs no snapshot never captures")
    fun noCaptureWhenNoSnapshotStep() = runBlocking {
        val runner = FakeRunner(responses = responses())
        val document = PageFormatEngine(runner).scrape(request("branding"))

        assertEquals(0, runner.captures)
        assertTrue(document.warning!!.contains("branding"), document.warning)
        assertTrue(document.metadata.formatsDelivered.isEmpty())
    }

    // ---- failure policy -----------------------------------------------------

    @Test
    @DisplayName("a REQUIRED step failure propagates with its original type and message")
    fun requiredFailurePropagates() {
        val runner = FakeRunner(responses = responses(), failing = setOf("export.raw"))
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { PageFormatEngine(runner).scrape(request("rawHtml")) }
        }
        assertTrue(error.message!!.contains("boom export.raw"), error.message)
    }

    @Test
    @DisplayName("a DEGRADABLE step failure becomes a warning and keeps the rest")
    fun degradedFailureWarns() = runBlocking {
        val runner = FakeRunner(responses = responses(), failing = setOf("scrape_all:a:href"))
        val document = PageFormatEngine(runner).scrape(request("markdown", "links"))

        assertNull(document.links)
        assertNotNull(document.markdown)
        assertTrue(document.warning!!.contains("links"), document.warning)
        assertEquals(listOf("markdown"), document.metadata.formatsDelivered)
    }

    @Test
    @DisplayName("an unsupported tool degrades instead of being called")
    fun unsupportedToolDegrades() = runBlocking {
        val runner = FakeRunner(supported = emptySet(), responses = responses())
        val document = PageFormatEngine(runner).scrape(request("markdown"))

        assertNull(document.markdown)
        assertTrue(document.warning!!.contains("not supported in this deployment"), document.warning)
        assertEquals(0, runner.calls.count { it.startsWith("read:") })
    }

    // ---- assembly -----------------------------------------------------------

    @Test
    @DisplayName("every requested format lands in its own field")
    fun assemblesEveryField() = runBlocking {
        val runner = FakeRunner(responses = responses())
        val document = PageFormatEngine(runner).scrape(
            request(
                "markdown", "html", "rawHtml", "links", "images", "readability",
                mapOf(
                    "type" to "attributes",
                    "selectors" to listOf(mapOf("selector" to ".price", "attribute" to "data-amount")),
                ),
                mapOf("type" to "deterministicJson", "sql" to "select 1"),
            )
        )

        // onlyMainContent defaults to true, so markdown comes from the readable
        // article; the export-based rendering is covered separately.
        assertEquals("Article", document.markdown)
        assertEquals(CLEAN_HTML, document.html)
        assertEquals(RAW_HTML, document.rawHtml)
        assertEquals(listOf("$HREF/x", "$HREF/y"), document.links)
        assertEquals(listOf("$HREF/a.png"), document.images)
        assertEquals("T", document.readability!!.title)
        assertEquals(0.9, document.readability!!.confidence, 0.0001)
        assertEquals(listOf("10", "20"), document.attributes!!.single().values)
        assertEquals(1, (document.json as List<*>).size)
    }

    @Test
    @DisplayName("markdown falls back to the full export when the article read fails")
    fun markdownFallsBackToExport() = runBlocking {
        val runner = FakeRunner(responses = responses(), failing = setOf("html_snapshot.readability"))
        val document = PageFormatEngine(runner).scrape(request("markdown"))

        assertEquals("# Title\n\nBody [link](https://example.com/x)", document.markdown)
        assertTrue(document.warning!!.contains("html_snapshot.readability"), document.warning)
    }

    @Test
    @DisplayName("fields of formats that were not requested are absent")
    fun unrequestedFieldsAreAbsent() = runBlocking {
        val runner = FakeRunner(responses = responses())
        val document = PageFormatEngine(runner).scrape(request("markdown", "html"))

        assertNull(document.rawHtml)
        assertNull(document.links)
        assertNull(document.json)
    }

    @Test
    @DisplayName("metadata records the capture and what was delivered")
    fun metadataIsStamped() = runBlocking {
        val runner = FakeRunner(responses = responses())
        val document = PageFormatEngine(runner).scrape(request("markdown", "links"))

        assertEquals(KEY, document.metadata.captureId)
        assertEquals(CAPTURED_AT, document.metadata.captureTime)
        assertEquals("miss", document.metadata.cacheState)
        assertEquals(listOf("markdown", "links"), document.metadata.formatsRequested)
        assertEquals(listOf("markdown", "links"), document.metadata.formatsDelivered)
    }

    @Test
    @DisplayName("an X-SQL failure surfaces instead of yielding an empty json field")
    fun deterministicJsonFailureSurfaces() {
        val runner = FakeRunner(
            responses = responses() + ("html_snapshot.query" to """{"isDone":true,"message":"Column not found"}""")
        )
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                PageFormatEngine(runner).scrape(
                    request(mapOf("type" to "deterministicJson", "sql" to "select nope"))
                )
            }
        }
        assertTrue(error.message!!.contains("Column not found"), error.message)
    }

    @Test
    @DisplayName("the capture is asked for no reuse, and its cache state reaches the metadata")
    fun captureAsksForNoReuse() = runBlocking {
        val runner = object : FormatStepRunner {
            var seen: Duration? = null
            override suspend fun acquireSnapshot(expires: Duration): FormatSnapshot {
                seen = expires
                return FormatSnapshot(KEY, HREF, CAPTURED_AT, "hit")
            }

            override suspend fun readOnSnapshot(
                snapshot: FormatSnapshot, domain: String, method: String, args: Map<String, Any?>,
            ): String = CLEAN_HTML

            override suspend fun runOnTab(domain: String, method: String, args: Map<String, Any?>): String = ""
            override fun supports(domain: String, method: String): Boolean = true
        }
        val document = PageFormatEngine(runner).scrape(PageScrapeRequest(formats("html")))

        // `ZERO` is the truthful instruction — reuse nothing, capture the live page.
        // A positive window is Firecrawl's `maxAge`, and nothing may ask for one until
        // some method can report a *stored* snapshot's identity; the request type no
        // longer has a field to carry it, so this asserts the remaining contract.
        assertEquals(Duration.ZERO, runner.seen)
        assertEquals("hit", document.metadata.cacheState)
    }

    // ---- live stage and artifacts -------------------------------------------

    @Test
    @DisplayName("a live step runs after every snapshot read, and its bytes are persisted")
    fun liveStepRunsLastAndItsArtifactIsPersisted() = runBlocking {
        val runner = FakeRunner(
            supported = ALL_TOOLS,
            responses = responses() + ("tab.screenshot" to SCREENSHOT_BASE64),
        )

        val document = PageFormatEngine(runner).scrape(request("markdown", "screenshot"))

        // `screenshot` is the only format that needs the tab, so this is the ordering
        // guarantee's real shape: one capture, every snapshot read (markdown's
        // readability probe first, then the export fallback), then the live step, then
        // the bytes landing on disk.
        assertEquals(
            listOf(
                "capture",
                "read:html_snapshot.readability",
                "read:export.clean",
                "live:tab.screenshot",
                "persist:screenshot",
            ),
            runner.calls,
        )
        assertEquals(listOf("screenshot.png"), runner.persisted)
        assertEquals("$ARTIFACT_DIR/screenshot.png", document.screenshot)
        // Not asked for: the bytes are opt-in because they dwarf the document.
        assertNull(document.screenshotBase64)
        assertEquals(listOf("markdown", "screenshot"), document.metadata.formatsDelivered)
    }

    @Test
    @DisplayName("base64 rides alongside the path when the format asks for it")
    fun base64IsOptional() = runBlocking {
        val runner = FakeRunner(
            supported = ALL_TOOLS,
            responses = responses() + ("tab.screenshot" to SCREENSHOT_BASE64),
        )

        val document = PageFormatEngine(runner).scrape(
            request(mapOf("type" to "screenshot", "base64" to true))
        )

        assertEquals("$ARTIFACT_DIR/screenshot.png", document.screenshot)
        assertEquals(SCREENSHOT_BASE64, document.screenshotBase64)
    }

    @Test
    @DisplayName("fullPage reaches the tool, and a bare screenshot asks for no region")
    fun fullPageReachesTheTool() = runBlocking {
        val seen = mutableListOf<Map<String, Any?>>()
        val runner = object : FormatStepRunner {
            override suspend fun acquireSnapshot(expires: Duration): FormatSnapshot =
                FormatSnapshot(KEY, HREF, CAPTURED_AT, "miss")

            override suspend fun readOnSnapshot(
                snapshot: FormatSnapshot, domain: String, method: String, args: Map<String, Any?>,
            ): String = CLEAN_HTML

            override suspend fun runOnTab(domain: String, method: String, args: Map<String, Any?>): String {
                seen += args
                return SCREENSHOT_BASE64
            }

            override suspend fun persistArtifact(nameHint: String, base64: String, extension: String): String =
                "$ARTIFACT_DIR/$nameHint.$extension"

            override fun supports(domain: String, method: String): Boolean = true
        }

        PageFormatEngine(runner).scrape(request("screenshot"))
        PageFormatEngine(runner).scrape(request(mapOf("type" to "screenshot", "fullPage" to true)))

        assertEquals(emptyMap<String, Any?>(), seen[0])
        assertEquals(mapOf("fullPage" to true), seen[1])
    }

    @Test
    @DisplayName("a host that cannot persist bytes degrades the format instead of failing the request")
    fun aHostWithoutDiskDegrades() = runBlocking {
        val runner = FakeRunner(
            supported = ALL_TOOLS,
            responses = responses() + ("tab.screenshot" to SCREENSHOT_BASE64),
            persistFails = true,
        )

        val document = PageFormatEngine(runner).scrape(request("markdown", "screenshot"))

        // The live step itself succeeded; failing to *store* it is a degradation, and
        // markdown — which never needed the tab — is untouched.
        assertNotNull(document.markdown)
        assertNull(document.screenshot)
        assertTrue(document.warning!!.contains("nowhere to put bytes"), document.warning)
        assertEquals(listOf("markdown"), document.metadata.formatsDelivered)
    }

    @Test
    @DisplayName("an unsupported option is refused at plan time, with the rest of the request intact")
    fun anUnsupportedOptionDegradesAtPlanTime() = runBlocking {
        val runner = FakeRunner(supported = ALL_TOOLS, responses = responses())

        val document = PageFormatEngine(runner).scrape(
            request("markdown", mapOf("type" to "screenshot", "quality" to 80))
        )

        assertNotNull(document.markdown)
        assertNull(document.screenshot)
        assertTrue(document.warning!!.contains("screenshot: quality is not supported"), document.warning)
        // Refused before anything ran: no live call at all.
        assertTrue(runner.liveCalls.isEmpty(), "refused options must not reach the browser")
        assertEquals(listOf("markdown"), document.metadata.formatsDelivered)
    }

    @Test
    @DisplayName("no warning is recorded when every requested format is delivered")
    fun noWarningOnFullSuccess() = runBlocking {
        val runner = FakeRunner(responses = responses())
        val document = PageFormatEngine(runner).scrape(request("markdown", "links", "images"))

        assertNull(document.warning)
        assertFalse(document.metadata.formatsDelivered.isEmpty())
    }

    // ---- plugin contributors ------------------------------------------------

    /**
     * A contributor that answers with a fixed value (or a fixed failure), so the
     * engine's handling of every outcome is testable without a real plugin.
     */
    private class FakeContributor(
        override val id: String,
        override val requires: Set<FormatInput> = emptySet(),
        override val outputField: String = id,
        private val available: Boolean = true,
        private val reason: String? = null,
        private val value: Any? = "contributed",
        private val failure: Exception? = null,
    ) : PageFormatContributor {
        override val displayName: String = id
        override val description: String = "test contributor $id"

        /** The context the engine handed over; null when it was never called. */
        var seen: FormatContext? = null
            private set

        override fun isAvailable(): Boolean = available

        override fun unavailableReason(): String? = reason

        override suspend fun contribute(ctx: FormatContext): Any? {
            seen = ctx
            failure?.let { throw it }
            return value
        }
    }

    /** The registry is a process-wide singleton; no test may leak into the next. */
    @AfterEach
    fun clearContributors() {
        PageFormatContributorRegistry.instance.clear()
    }

    @Test
    @DisplayName("a registered contributor fills its field and counts as delivered")
    fun contributorDeliversIntoItsField() = runBlocking {
        val profile = mapOf("logo" to "https://example.com/l.svg")
        PageFormatContributorRegistry.instance.register(FakeContributor("branding", value = profile))

        val runner = FakeRunner(responses = responses())
        val document = PageFormatEngine(runner).scrape(request("branding"))

        assertEquals(profile, document.branding)
        assertNull(document.warning)
        assertEquals(listOf("branding"), document.metadata.formatsDelivered)
        // Nothing in a contributor request needs the page, so nothing is captured.
        assertEquals(0, runner.captures)
    }

    @Test
    @DisplayName("a contributor reads the same capture the core formats read")
    fun contributorSeesTheSameCapture() = runBlocking {
        val contributor = FakeContributor("branding")
        PageFormatContributorRegistry.instance.register(contributor)

        val runner = FakeRunner(responses = responses())
        val document = PageFormatEngine(runner).scrape(request("markdown", "html", "rawHtml", "branding"))

        assertNotNull(contributor.seen)
        val ctx = contributor.seen!!
        assertEquals(KEY, ctx.snapshotKey)
        assertEquals(HREF, ctx.url)
        assertEquals(RAW_HTML, ctx.rawHtml)
        assertEquals(CLEAN_HTML, ctx.html)
        assertNotNull(ctx.markdown)
        // One page load served markdown, html, rawHtml and the contributor alike.
        assertEquals(1, runner.captures)
        assertEquals(listOf("branding", "html", "markdown", "rawHtml"), document.metadata.formatsDelivered.sorted())
    }

    @Test
    @DisplayName("a contributor is handed the format's own request options")
    fun contributorSeesItsOptions() = runBlocking {
        val contributor = FakeContributor("branding")
        PageFormatContributorRegistry.instance.register(contributor)

        PageFormatEngine(FakeRunner(responses = responses()))
            .scrape(request(mapOf("type" to "branding", "mode" to "standard")))

        assertNotNull(contributor.seen)
        assertEquals("standard", contributor.seen!!.options["mode"])
    }

    @Test
    @DisplayName("a contributed format with no contributor warns instead of vanishing")
    fun absentContributorWarns() = runBlocking {
        val document = PageFormatEngine(FakeRunner(responses = responses())).scrape(request("branding"))

        assertNull(document.branding)
        assertTrue(
            document.warning!!.contains("branding: unavailable (no plugin contributor installed)"),
            document.warning,
        )
    }

    @Test
    @DisplayName("a contributor that reports itself unavailable warns with its reason")
    fun unavailableContributorWarnsWithItsReason() = runBlocking {
        PageFormatContributorRegistry.instance.register(
            FakeContributor("branding", available = false, reason = "branding service is not configured")
        )

        val document = PageFormatEngine(FakeRunner(responses = responses())).scrape(request("branding"))

        assertNull(document.branding)
        assertTrue(document.warning!!.contains("branding service is not configured"), document.warning)
    }

    @Test
    @DisplayName("a contributor needing an input the request does not carry is not called")
    fun missingInputIsReported() = runBlocking {
        val contributor = FakeContributor("branding", requires = setOf(FormatInput.MARKDOWN))
        PageFormatContributorRegistry.instance.register(contributor)

        val document = PageFormatEngine(FakeRunner(responses = responses())).scrape(request("branding"))

        assertNull(contributor.seen, "a contributor must not be called with an input it asked for missing")
        assertTrue(document.warning!!.contains("needs markdown"), document.warning)
        assertNull(document.branding)
    }

    @Test
    @DisplayName("requiring a live tab is reported, because a contributor is handed values")
    fun liveTabRequirementIsReported() = runBlocking {
        val contributor = FakeContributor("branding", requires = setOf(FormatInput.LIVE_TAB))
        PageFormatContributorRegistry.instance.register(contributor)

        val document = PageFormatEngine(FakeRunner(responses = responses())).scrape(request("branding"))

        assertNull(contributor.seen)
        assertTrue(document.warning!!.contains("needs a live tab"), document.warning)
    }

    @Test
    @DisplayName("a contributor reporting nothing is a warning, not an empty field")
    fun contributorReportingNothingWarns() = runBlocking {
        PageFormatContributorRegistry.instance.register(FakeContributor("branding", value = null))

        val document = PageFormatEngine(FakeRunner(responses = responses())).scrape(request("branding"))

        assertNull(document.branding)
        assertTrue(document.warning!!.contains("branding: nothing to report"), document.warning)
    }

    @Test
    @DisplayName("a contributor failure warns and leaves the rest of the request intact")
    fun contributorFailureWarns() = runBlocking {
        PageFormatContributorRegistry.instance.register(
            FakeContributor("branding", failure = IllegalStateException("branding vendor is down"))
        )

        val runner = FakeRunner(responses = responses())
        val document = PageFormatEngine(runner).scrape(request("markdown", "branding"))

        assertNotNull(document.markdown)
        assertNull(document.branding)
        assertTrue(document.warning!!.contains("branding vendor is down"), document.warning)
        assertEquals(listOf("markdown"), document.metadata.formatsDelivered)
    }

    @Test
    @DisplayName("a contributor writing a field the document does not have warns")
    fun foreignOutputFieldWarns() = runBlocking {
        PageFormatContributorRegistry.instance.register(
            FakeContributor("branding", outputField = "summary")
        )

        val document = PageFormatEngine(FakeRunner(responses = responses())).scrape(request("branding"))

        assertNull(document.summary)
        assertTrue(
            document.warning!!.contains("writes 'summary', which is not a contributor field"),
            document.warning,
        )
    }

    @Test
    @DisplayName("this build reserves the core format ids against contributors")
    fun coreIdsAreNotContributable() {
        // The engine's contributor pass keys off PageFormats.isContributed, so a
        // plugin claiming `markdown` would be dead code rather than a hijack —
        // registration refuses it, which is the louder half of the same rule.
        val accepted = runCatching {
            PageFormatContributorRegistry.instance.register(FakeContributor("markdown"))
        }.isSuccess

        assertFalse(accepted, "a core format id must not be claimable by a contributor")
        assertNull(PageFormatContributorRegistry.instance.get("markdown"))
    }
}
