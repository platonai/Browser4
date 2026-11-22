package ai.platon.pulsar.agentic.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

/**
 * A run writes its full prompt, complete tool payloads and page timeline under
 * `<data>/logs/agent/<time>/<uuid>/`, and nothing used to remove them: a month of
 * self-development runs left tens of thousands of files behind (issue #652, gap 5).
 *
 * `pruneTraceRuns` is the "keep it affordable" half of keeping a full audit trail. Every test
 * here works on a temp root — the production default is the process-wide data dir, and deleting
 * that is exactly what a retention run must not do by accident.
 */
@Tag("Unit")
@Tag("Fast")
@DisplayName("Agent trace retention")
class AgentPathsTraceRetentionTest {

    private fun seedTwoRuns(root: Path): Pair<Path, Path> {
        val expired = root.resolve("2026-01-01").resolve("11111111-1111-1111-1111-111111111111")
        val fresh = root.resolve("2026-01-02").resolve("22222222-2222-2222-2222-222222222222")
        for (dir in listOf(expired, fresh)) {
            Files.createDirectories(dir)
            Files.writeString(dir.resolve("cli-tool-trace.jsonl"), """{"seq":1}""" + "\n")
        }
        val anHourAgo = FileTime.fromMillis(System.currentTimeMillis() - 3_600_000)
        Files.setLastModifiedTime(expired, anHourAgo)
        Files.setLastModifiedTime(expired.resolve("cli-tool-trace.jsonl"), anHourAgo)
        return expired to fresh
    }

    @Test
    @DisplayName("run dirs older than the cutoff are removed, newer ones are kept")
    fun prunesOnlyExpiredRunDirs(@TempDir root: Path) {
        val (expired, fresh) = seedTwoRuns(root)

        val removed = AgentPaths.pruneTraceRuns(root, Instant.now().minusSeconds(60))

        assertEquals(1, removed)
        assertFalse(Files.exists(expired), "an expired run dir is removed whole")
        assertTrue(Files.exists(fresh.resolve("cli-tool-trace.jsonl")), "a live run keeps its trace")
    }

    @Test
    @DisplayName("a date dir whose runs all expired is removed with them")
    fun emptyDateDirsAreRemoved(@TempDir root: Path) {
        val (expired, _) = seedTwoRuns(root)
        val dateDir = expired.parent

        AgentPaths.pruneTraceRuns(root, Instant.now().minusSeconds(60))

        assertFalse(Files.exists(dateDir), "the date dir would otherwise accumulate empty folders")
    }

    @Test
    @DisplayName("a missing trace root is not an error")
    fun missingRootIsTolerated(@TempDir root: Path) {
        // The retention loop runs on every compaction, so "no traces yet" must be quiet.
        assertEquals(0, AgentPaths.pruneTraceRuns(root.resolve("absent"), Instant.now()))
    }

    @Test
    @DisplayName("the cutoff is a boundary, not a guess: a dir modified after it survives")
    fun theCutoffIsRespected(@TempDir root: Path) {
        val (expired, fresh) = seedTwoRuns(root)

        // A cutoff BEFORE both mtimes keeps both; a cutoff AFTER both removes both.
        assertEquals(0, AgentPaths.pruneTraceRuns(root, Instant.now().minusSeconds(7_200)))
        assertTrue(Files.exists(expired) && Files.exists(fresh))

        assertEquals(2, AgentPaths.pruneTraceRuns(root, Instant.now().plusSeconds(60)))
        assertFalse(Files.exists(expired) || Files.exists(fresh), "everything older than the cutoff goes")
    }
}
