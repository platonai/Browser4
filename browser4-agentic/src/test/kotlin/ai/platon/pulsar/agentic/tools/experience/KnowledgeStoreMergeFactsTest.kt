package ai.platon.pulsar.agentic.tools.experience

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.test.*

/**
 * Tests for the retrospective-knowledge write path:
 * [KnowledgeStore.mergeFacts] + the facts patch (selectors / interaction
 * hints / known blockers / anti-patterns) recorded with experience_save.
 */
@OptIn(ExperimentalPathApi::class)
@DisplayName("KnowledgeStore mergeFacts (facts write path)")
class KnowledgeStoreMergeFactsTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var store: KnowledgeStore

    @BeforeEach
    fun setUp() {
        store = KnowledgeStore(tempDir)
        store.initializeStore()
    }

    @AfterEach
    fun tearDown() {
        try { tempDir.deleteRecursively() } catch (_: Exception) {}
    }

    private fun samplePatch() = FactsPatch(
        selectors = mapOf(
            "composeBox" to VerifiedSelector(
                primary = "[data-testid='tweetTextarea_0']",
                fallbacks = listOf("div[role='textbox']"),
                note = "X.com composer",
            )
        ),
        interactionHints = listOf("单帖最多 4 张图，超出会被静默拒绝（计数不上涨）"),
        knownBlockers = listOf(
            BlockerInfo(type = "media limit", selector = "[data-testid='tweetButton']", action = "verify", note = ">4 images silently ignored")
        ),
        antiPatterns = listOf("不要在主页滚动抓帖：虚拟滚动会回收旧帖，改用官方 JSON 接口"),
    )

    @Test
    fun `merge creates a hypothesis facts entry and round-trips through YAML`() = runBlocking {
        val result = store.mergeFacts(
            domain = "x.com", intent = "publish", urlPattern = "/*", patch = samplePatch()
        )

        assertFalse(result.rejected)
        assertEquals(VerificationStatus.HYPOTHESIS, result.facts.status)

        // Reload from disk — the hand-written YAML serializer must round-trip
        // every patched field.
        val loaded = store.loadFacts("x.com", "publish")
        assertNotNull(loaded)
        assertEquals(1, loaded.selectors.size)
        val selector = loaded.selectors["composeBox"]
        assertEquals("[data-testid='tweetTextarea_0']", selector?.primary)
        assertEquals(listOf("div[role='textbox']"), selector?.fallbacks)
        assertEquals(1, loaded.interactionHints.size)
        assertTrue(loaded.interactionHints.first().contains("4 张图"))
        assertEquals(1, loaded.knownBlockers.size)
        assertEquals("media limit", loaded.knownBlockers.first().type)
        assertEquals(1, loaded.antiPatterns.size)
        assertTrue(loaded.antiPatterns.first().contains("虚拟滚动"))
    }

    @Test
    fun `second merge appends deduped lists and patch wins for same selector key`() = runBlocking {
        store.mergeFacts("x.com", "publish", "/*", samplePatch())

        val second = FactsPatch(
            selectors = mapOf(
                "composeBox" to VerifiedSelector(primary = "[data-testid='tweetTextarea_0']:not([aria-hidden])")
            ),
            interactionHints = listOf("粘贴图片后必须校验预览计数"),
            knownBlockers = listOf(
                BlockerInfo(type = "media limit", selector = "[data-testid='tweetButton']", action = "verify")
            ),
            antiPatterns = listOf("不要在主页滚动抓帖：虚拟滚动会回收旧帖，改用官方 JSON 接口"),
        )
        val result = store.mergeFacts("x.com", "publish", "/*", second)
        assertFalse(result.rejected)

        val loaded = store.loadFacts("x.com", "publish")!!
        // Patch wins for the same selector key.
        assertEquals("[data-testid='tweetTextarea_0']:not([aria-hidden])", loaded.selectors["composeBox"]?.primary)
        // Lists append with dedupe: hint count 2, blocker count 1, anti-pattern count 1.
        assertEquals(2, loaded.interactionHints.size)
        assertEquals(1, loaded.knownBlockers.size)
        assertEquals(1, loaded.antiPatterns.size)
    }

    @Test
    fun `merge refuses to touch VERIFIED facts`() = runBlocking {
        // Seed facts and force-verify them (as promotion would).
        store.mergeFacts("x.com", "publish", "/*", samplePatch())
        val verified = store.loadFacts("x.com", "publish")!!
            .copy(status = VerificationStatus.VERIFIED)
        store.saveFacts(verified)

        val result = store.mergeFacts(
            "x.com", "publish", "/*",
            FactsPatch(interactionHints = listOf("should never land"))
        )

        assertTrue(result.rejected)
        assertTrue(result.message.contains("VERIFIED"))
        val after = store.loadFacts("x.com", "publish")!!
        assertTrue(after.interactionHints.none { it == "should never land" })
        assertEquals(VerificationStatus.VERIFIED, after.status)
    }

    @Test
    fun `empty patch is refused`() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { store.mergeFacts("x.com", "publish", "/*", FactsPatch()) }
        }
    }
}
