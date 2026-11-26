package ai.platon.pulsar.agentic.tools.advanced.agent.skillsummary

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("Unit")
@Tag("Fast")
class SkillFingerprintTest {

    @Test
    @DisplayName("tokensOf lowercases, strips punctuation and stopwords, drops short tokens")
    fun normalizesPrompt() {
        val tokens = SkillFingerprint.tokensOf("Please refactor the Auth module, NOW!")
        assertTrue(tokens.containsAll(listOf("refactor", "auth", "module")))
        // stopwords / short / punctuation dropped
        assertTrue("please" !in tokens)
        assertTrue("the" !in tokens)
        assertTrue("now" !in tokens) // length <= 2 after... wait "now" is len 3 but is a stopword
        assertEquals(3, tokens.size)
    }

    @Test
    @DisplayName("tokensOf of blank/empty is empty set")
    fun blankPromptYieldsEmpty() {
        assertTrue(SkillFingerprint.tokensOf(null).isEmpty())
        assertTrue(SkillFingerprint.tokensOf("   ").isEmpty())
        assertTrue(SkillFingerprint.tokensOf("a an the").isEmpty())
    }

    @Test
    @DisplayName("tokensOf keeps CJK runs as bigrams so Chinese prompts are not empty")
    fun chinesePromptYieldsBigrams() {
        val tokens = SkillFingerprint.tokensOf("帮我在亚马逊上找一个适合14岁中国女孩的小礼物")
        assertTrue(tokens.isNotEmpty(), "a Chinese prompt must not produce an empty fingerprint")
        assertTrue(tokens.contains("亚马"))
        assertTrue(tokens.contains("中国"))
    }

    @Test
    @DisplayName("similar Chinese prompts reach the dedup similarity threshold")
    fun chineseSimilarity() {
        val a = SkillFingerprint.tokensOf("帮我在亚马逊上找一个适合14岁中国女孩的小礼物")
        val b = SkillFingerprint.tokensOf("帮我在亚马逊上找一个适合14岁中国女孩的小礼物，给出价格")
        assertTrue(
            SkillFingerprint.jaccard(a, b) >= 0.85,
            "similar Chinese prompts must be deduped into one skill",
        )
    }

    @Test
    @DisplayName("jaccard of identical sets is 1.0; disjoint is 0.0")
    fun jaccardEdges() {
        assertEquals(1.0, SkillFingerprint.jaccard(setOf("a", "b"), setOf("a", "b")))
        assertEquals(0.0, SkillFingerprint.jaccard(setOf("a"), setOf("b")))
        assertEquals(1.0, SkillFingerprint.jaccard(emptySet(), emptySet()))
        assertEquals(0.0, SkillFingerprint.jaccard(emptySet(), setOf("a")))
    }

    @Test
    @DisplayName("jaccard of overlapping sets is intersection/union")
    fun jaccardOverlap() {
        // {a,b,c} ∩ {a,b,d} = {a,b} (2); union = {a,b,c,d} (4) → 0.5
        assertEquals(0.5, SkillFingerprint.jaccard(setOf("a", "b", "c"), setOf("a", "b", "d")))
    }
}
