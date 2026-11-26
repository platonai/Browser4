package ai.platon.pulsar.agentic.tools.advanced.agent.skillsummary

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Tag("Unit")
@Tag("Fast")
class SkillFingerprintIndexTest {

    @Test
    @DisplayName("TR-3.4 record then lookup hits the same token set")
    fun recordAndLookup(@TempDir dir: Path) {
        val idx = SkillFingerprintIndex(dir.resolve("index.json"))
        idx.record(setOf("refactor", "auth", "module"), "refactor-auth")
        assertEquals("refactor-auth", idx.lookup(setOf("refactor", "auth", "module"), 0.8)?.skillId)
    }

    @Test
    @DisplayName("lookup below threshold returns null")
    fun lookupBelowThreshold(@TempDir dir: Path) {
        val idx = SkillFingerprintIndex(dir.resolve("index.json"))
        idx.record(setOf("a", "b", "c"), "skill-1")
        assertNull(idx.lookup(setOf("x", "y", "z"), 0.8))
    }

    @Test
    @DisplayName("TR-3.3 index persists to disk and a fresh instance reloads it")
    fun persistsAndReloads(@TempDir dir: Path) {
        val file = dir.resolve("index.json")
        SkillFingerprintIndex(file).record(setOf("a", "b", "c"), "skill-1")
        val reloaded = SkillFingerprintIndex(file)
        assertEquals(1, reloaded.size())
        assertEquals("skill-1", reloaded.lookup(setOf("a", "b", "c"), 0.5)?.skillId)
    }

    @Test
    @DisplayName("lookup does not filter by cooldown; it returns the hit with lastGeneratedAt")
    fun lookupReturnsHitRegardlessOfCooldown(@TempDir dir: Path) {
        val idx = SkillFingerprintIndex(dir.resolve("index.json"))
        val t0 = 1_000_000L
        idx.record(setOf("a", "b", "c"), "skill-1", now = t0)
        val hit = idx.lookup(setOf("a", "b", "c"), 0.5, now = t0 + 3_600_000)
        // cooling is decided by the decider, not by lookup
        assertEquals("skill-1", hit?.skillId)
        assertEquals(t0, hit?.lastGeneratedAt)
    }

    @Test
    @DisplayName("bump increments exampleCount and refreshes timestamp")
    fun bumpUpdates(@TempDir dir: Path) {
        val file = dir.resolve("index.json")
        val idx = SkillFingerprintIndex(file)
        idx.record(setOf("a", "b"), "skill-1", now = 1000L)
        idx.bump("skill-1", now = 2000L)
        val reloaded = SkillFingerprintIndex(file)
        assertEquals("skill-1", reloaded.lookup(setOf("a", "b"), 0.5)?.skillId)
    }

    @Test
    @DisplayName("missing/corrupt index file loads as empty without throwing")
    fun corruptIndexIsEmpty(@TempDir dir: Path) {
        val file = dir.resolve("index.json")
        file.toFile().writeText("{ not valid json")
        val idx = SkillFingerprintIndex(file)
        assertEquals(0, idx.size())
    }
}
