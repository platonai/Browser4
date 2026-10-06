package ai.platon.pulsar.rest.api.service.scrape

import ai.platon.pulsar.agentic.tools.advanced.format.FormatSnapshot
import ai.platon.pulsar.common.AppPaths
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64

private const val CAPTURE_JSON =
    """{"url":"https://example.com/p","href":"https://example.com/p?a=1",""" +
        """"capturedAt":"2026-10-06T00:00:00Z","contentType":"text/html","title":"T"}"""

private val SNAPSHOT = FormatSnapshot("https://example.com/p", "https://example.com/p", "2026-10-06T00:00:00Z", "miss")

@DisplayName("SnapshotFormatStepRunner")
class SnapshotFormatStepRunnerTest {

    /**
     * A dispatcher that records every call, so the arguments the runner injects
     * can be asserted — which is the whole reason the runner takes a dispatcher
     * instead of reaching for a browser.
     */
    private class RecordingDispatcher(
        private val supported: Set<String> = setOf("html_snapshot.capture", "html_snapshot.export"),
        private val responses: Map<String, Any?> = emptyMap(),
    ) : FormatToolDispatcher {

        val calls = mutableListOf<Triple<String, String, Map<String, Any?>>>()

        override suspend fun call(domain: String, method: String, args: Map<String, Any?>): Any? {
            calls += Triple(domain, method, args)
            require("$domain.$method" in supported) { "Unsupported $domain method: $method" }
            return responses["$domain.$method"]
        }

        override fun supports(domain: String, method: String): Boolean = "$domain.$method" in supported

        fun argsOf(domain: String, method: String): Map<String, Any?> =
            calls.single { it.first == domain && it.second == method }.third

        fun callCount(domain: String, method: String): Int =
            calls.count { it.first == domain && it.second == method }
    }

    private fun runner(
        dispatcher: FormatToolDispatcher,
        readExpires: Duration = SnapshotFormatStepRunner.DEFAULT_READ_EXPIRES,
    ) = SnapshotFormatStepRunner(dispatcher, readExpires)

    // ---- artifact landing ---------------------------------------------------

    @Test
    @DisplayName("base64 is decoded to a real file under AppPaths' screenshot directory")
    fun artifactsLandUnderAppPaths() {
        val payload = "hello".toByteArray()
        val encoded = Base64.getEncoder().encodeToString(payload)

        val path = runBlocking { runner(RecordingDispatcher()).persistArtifact("screenshot", encoded, "png") }

        val file = Path.of(path)
        assertTrue(Files.exists(file), "expected a real file at $path")
        // AppPaths owns the location, and WEB_SCREENSHOT_DIR sits inside the process
        // temp tree — which is what makes the returned path a *temporary* file rather
        // than something the caller may keep.
        assertTrue(
            file.startsWith(AppPaths.WEB_SCREENSHOT_DIR),
            "expected $path under ${AppPaths.WEB_SCREENSHOT_DIR}",
        )
        assertTrue(path.endsWith(".png"), path)
        assertArrayEquals(payload, Files.readAllBytes(file))
        Files.deleteIfExists(file)
    }

    @Test
    @DisplayName("a PDF is filed under web/pdf, never under a directory called screenshot")
    fun nonImageArtifactsGetTheirOwnDirectory() {
        val encoded = Base64.getEncoder().encodeToString("%PDF-1.4".toByteArray())

        val path = runBlocking { runner(RecordingDispatcher()).persistArtifact("pdf", encoded, "pdf") }

        val file = Path.of(path)
        assertTrue(Files.exists(file), "expected a real file at $path")
        // Still AppPaths, still the temp tree — but the leaf follows the extension, so
        // the directory never contradicts what is inside it. One shared directory would
        // have been shorter and would have put PDFs in `screenshot/`.
        assertTrue(file.startsWith(AppPaths.WEB_CACHE_DIR), "expected $path under ${AppPaths.WEB_CACHE_DIR}")
        assertFalse(file.startsWith(AppPaths.WEB_SCREENSHOT_DIR), "a PDF must not land in $path")
        assertTrue(path.endsWith(".pdf"), path)
        Files.deleteIfExists(file)
    }

    @Test
    @DisplayName("an extension that could steer the write is refused rather than sanitised")
    fun hostileArtifactExtensionIsRefused() {
        val encoded = Base64.getEncoder().encodeToString("hi".toByteArray())

        // The extension becomes a directory name and a file suffix, and it arrives from a
        // provider's `ArtifactSpec` — an extension point open to plugins. A traversal
        // attempt must fail loudly; silently rewriting it would hide the plugin's bug.
        for (extension in listOf("", "   ", "../evil", "p/n", "p\\n", "pdf;rm")) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { runner(RecordingDispatcher()).persistArtifact("pdf", encoded, extension) }
            }
        }
    }

    @Test
    @DisplayName("an extension is normalised once, so the value validated is the value written")
    fun artifactExtensionIsNormalised() {
        val encoded = Base64.getEncoder().encodeToString("%PDF-1.4".toByteArray())

        // `ArtifactSpec` documents a dot-less lowercase extension. A provider that spells
        // it `.PDF` should still get a correct file — not `..PDF` — and the directory
        // decision must follow the same normalised value rather than the raw one.
        val path = runBlocking { runner(RecordingDispatcher()).persistArtifact("pdf", encoded, ".PDF") }

        val file = Path.of(path)
        assertTrue(Files.exists(file), "expected a real file at $path")
        assertTrue(
            file.startsWith(AppPaths.WEB_CACHE_DIR.resolve("pdf")),
            "expected $path under ${AppPaths.WEB_CACHE_DIR.resolve("pdf")}",
        )
        assertTrue(path.endsWith(".pdf"), "expected a normalised suffix, got $path")
        Files.deleteIfExists(file)
    }

    @Test
    @DisplayName("a data-URI prefix is tolerated, and a payload that is not base64 is refused")
    fun artifactPayloadIsValidated() {
        val encoded = Base64.getEncoder().encodeToString("hi".toByteArray())

        val withPrefix = runBlocking {
            runner(RecordingDispatcher()).persistArtifact("screenshot", "data:image/png;base64,$encoded", "png")
        }
        assertArrayEquals("hi".toByteArray(), Files.readAllBytes(Path.of(withPrefix)))
        Files.deleteIfExists(Path.of(withPrefix))

        // Loudly, so the format degrades with a reason instead of a corrupt file
        // landing on disk and being reported as a successful capture.
        val bad = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { runner(RecordingDispatcher()).persistArtifact("screenshot", "not base64 !!", "png") }
        }
        assertTrue(bad.message!!.contains("not base64"), bad.message)
    }

    @Test
    @DisplayName("two captures of the same page get different files")
    fun artifactNamesDoNotCollide() {
        val encoded = Base64.getEncoder().encodeToString("hi".toByteArray())
        val one = runner(RecordingDispatcher())

        val first = runBlocking { one.persistArtifact("screenshot", encoded, "png") }
        val second = runBlocking { one.persistArtifact("screenshot", encoded, "png") }

        // A request id would be nicer, but none exists yet; a timestamp plus a random
        // suffix is what keeps two concurrent captures of one page from overwriting
        // each other.
        assertTrue(first != second, "expected distinct paths, both were $first")
        Files.deleteIfExists(Path.of(first))
        Files.deleteIfExists(Path.of(second))
    }

    // ---- the capture-once invariant -----------------------------------------

    @Test
    @DisplayName("every snapshot read asks the store for a positive window")
    fun snapshotReadInjectsAPositiveExpires() = runBlocking {
        val dispatcher = RecordingDispatcher(responses = mapOf("html_snapshot.export" to "<html/>"))

        runner(dispatcher).readOnSnapshot(SNAPSHOT, "html_snapshot", "export", mapOf("clean" to true))

        val args = dispatcher.argsOf("html_snapshot", "export")
        assertEquals("1d", args[SnapshotFormatStepRunner.EXPIRES_ARG])
        // The step's own arguments survive: only the window is added.
        assertEquals(true, args["clean"])
    }

    @Test
    @DisplayName("a step-supplied expires is overridden, not merely defaulted")
    fun snapshotReadOverridesAStepSuppliedExpires() = runBlocking {
        // `0s` is the tool default and means "capture the live page": letting a step
        // carry it through would reload the page once per format and quietly make
        // capture-once a fiction.
        val dispatcher = RecordingDispatcher(responses = mapOf("html_snapshot.export" to "<html/>"))

        runner(dispatcher).readOnSnapshot(
            SNAPSHOT, "html_snapshot", "export", mapOf(SnapshotFormatStepRunner.EXPIRES_ARG to "0s"),
        )

        assertEquals("1d", dispatcher.argsOf("html_snapshot", "export")[SnapshotFormatStepRunner.EXPIRES_ARG])
    }

    @Test
    @DisplayName("a zero or negative read window is refused when the runner is built")
    fun zeroReadWindowIsRefusedAtConstruction() {
        val dispatcher = RecordingDispatcher()

        // The failure has to land here, not on a request that loads the page eight
        // times and still reports success.
        assertThrows(IllegalArgumentException::class.java) { runner(dispatcher, Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { runner(dispatcher, Duration.ofSeconds(-1)) }
    }

    @Test
    @DisplayName("a read window below one second is refused: it would round to the live page")
    fun subSecondReadWindowIsRefused() {
        val dispatcher = RecordingDispatcher()

        // `Duration.ofMillis(500)` is neither zero nor negative, yet it renders as
        // `0s` in the duration grammar — the tool's "capture the live page". This is
        // the one way a "positive" window can still undo capture-once, so the floor
        // is a second.
        assertThrows(IllegalArgumentException::class.java) { runner(dispatcher, Duration.ofMillis(500)) }
        assertThrows(IllegalArgumentException::class.java) {
            SnapshotFormatStepRunner.formatExpires(Duration.ofMillis(500))
        }
        // One second is the smallest window that survives the grammar.
        assertEquals("1s", SnapshotFormatStepRunner.formatExpires(Duration.ofSeconds(1)))
        // Sub-second precision is truncated, not rounded up: a shorter window only
        // ever makes a read fall back to the live page sooner, which is the safe
        // direction, and it can never reach `0s`.
        assertEquals("1s", SnapshotFormatStepRunner.formatExpires(Duration.ofMillis(1_500)))
    }

    @Test
    @DisplayName("the read window is spelled in the largest exact unit")
    fun readWindowUsesTheLargestExactUnit() = runBlocking {
        val cases = mapOf(
            Duration.ofDays(2) to "2d",
            Duration.ofHours(2) to "2h",
            Duration.ofMinutes(10) to "10m",
            Duration.ofSeconds(45) to "45s",
            // 90 minutes is an exact number of minutes but not of hours, so minutes
            // is the largest unit that divides it exactly.
            Duration.ofMinutes(90) to "90m",
        )
        for ((window, expected) in cases) {
            val dispatcher = RecordingDispatcher(responses = mapOf("html_snapshot.export" to "<html/>"))
            runner(dispatcher, window).readOnSnapshot(SNAPSHOT, "html_snapshot", "export", emptyMap())

            assertEquals(expected, dispatcher.argsOf("html_snapshot", "export")[SnapshotFormatStepRunner.EXPIRES_ARG])
        }
    }

    // ---- capture ------------------------------------------------------------

    @Test
    @DisplayName("acquireSnapshot captures once and returns the store identity")
    fun acquireSnapshotCapturesOnce() = runBlocking {
        val dispatcher = RecordingDispatcher(responses = mapOf("html_snapshot.capture" to CAPTURE_JSON))

        val snapshot = runner(dispatcher).acquireSnapshot(Duration.ZERO)

        assertEquals(1, dispatcher.callCount("html_snapshot", "capture"))
        // `url` is the normalized page-store key, `href` the browser-facing address.
        assertEquals("https://example.com/p", snapshot.key)
        assertEquals("https://example.com/p?a=1", snapshot.href)
        assertEquals("2026-10-06T00:00:00Z", snapshot.capturedAt)
        assertEquals("miss", snapshot.cacheState)
    }

    @Test
    @DisplayName("acquireSnapshot accepts already-parsed metadata as well as JSON text")
    fun acquireSnapshotAcceptsAMap() = runBlocking {
        val dispatcher = RecordingDispatcher(
            responses = mapOf(
                "html_snapshot.capture" to mapOf("url" to "https://example.com/p", "capturedAt" to "T"),
            ),
        )

        val snapshot = runner(dispatcher).acquireSnapshot(Duration.ZERO)

        assertEquals("https://example.com/p", snapshot.key)
        // No `href` in the metadata: the key is the honest fallback.
        assertEquals("https://example.com/p", snapshot.href)
    }

    @Test
    @DisplayName("a capture with no usable metadata fails loudly")
    fun captureWithoutMetadataFails() {
        for (response in listOf<Any?>("", "   ", "not json", """{"title":"no url"}""")) {
            val dispatcher = RecordingDispatcher(responses = mapOf("html_snapshot.capture" to response))
            assertThrows(IllegalStateException::class.java) {
                runBlocking { runner(dispatcher).acquireSnapshot(Duration.ZERO) }
            }
        }
    }

    // ---- live steps ---------------------------------------------------------

    @Test
    @DisplayName("a live step is run as given, with no window injected")
    fun liveStepGetsNoExpiresWindow() = runBlocking {
        val dispatcher = RecordingDispatcher(
            supported = setOf("tab.screenshot"),
            responses = mapOf("tab.screenshot" to "/tmp/s.png"),
        )

        val output = runner(dispatcher).runOnTab("tab", "screenshot", mapOf("fullPage" to true))

        assertEquals("/tmp/s.png", output)
        val args = dispatcher.argsOf("tab", "screenshot")
        assertFalse(args.containsKey(SnapshotFormatStepRunner.EXPIRES_ARG))
        assertEquals(true, args["fullPage"])
    }

    // ---- failure and capability semantics -----------------------------------

    @Test
    @DisplayName("a tool that produced nothing is a failure, not an empty result")
    fun emptyOutputFailsLoudly() {
        val dispatcher = RecordingDispatcher(responses = mapOf("html_snapshot.export" to null))

        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { runner(dispatcher).readOnSnapshot(SNAPSHOT, "html_snapshot", "export", emptyMap()) }
        }
        assertTrue(error.message!!.contains("html_snapshot.export returned no output"), error.message)
    }

    @Test
    @DisplayName("supports is the dispatcher's answer, so the engine can degrade before calling")
    fun supportsDelegates() {
        val dispatcher = RecordingDispatcher(supported = setOf("html_snapshot.export"))

        val runner = runner(dispatcher)
        assertTrue(runner.supports("html_snapshot", "export"))
        assertFalse(runner.supports("html_snapshot", "capture"))
        assertTrue(dispatcher.calls.isEmpty(), "supports must not dispatch")
    }
}
