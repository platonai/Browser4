package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the dev-task checkpoint store (plan P2.4): a resume record must
 * survive a restart (round-trip through disk), list newest-first, and keep the
 * remaining-step computation honest.
 */
class DevTaskCheckpointStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private fun store(enabled: Boolean = true) = DevTaskCheckpointStore(tempDir, enabled)

    private fun step(order: Int, tool: String = "coding.validate") = DevTaskStepSnapshot(
        order = order,
        tool = tool,
        purpose = "purpose $order",
        command = "coding.validate(type=\"repo-consistency\")",
        args = mapOf("type" to "repo-consistency"),
    )

    private fun checkpoint(
        id: String = "dt-1",
        completed: List<DevTaskStepResult> = emptyList(),
        state: String = DevTaskCheckpoint.STATE_RUNNING,
        updatedAt: String = "2026-10-21T18:00:00Z",
    ) = DevTaskCheckpoint(
        id = id,
        task = "fix a test in browser4-rest",
        state = state,
        steps = listOf(step(1), step(2), step(3)),
        completed = completed,
        updatedAt = updatedAt,
        createdAt = "2026-10-21T17:00:00Z",
    )

    @Test
    @DisplayName("a checkpoint round-trips through disk")
    fun roundTrip() {
        val saved = checkpoint(
            completed = listOf(DevTaskStepResult(1, "coding.validate", true, "ok")),
        )
        assertTrue(store().save(saved))

        val loaded = store().load("dt-1")
        assertNotNull(loaded)
        assertEquals("dt-1", loaded!!.id)
        assertEquals("fix a test in browser4-rest", loaded.task)
        assertEquals(3, loaded.steps.size)
        assertEquals(listOf(1), loaded.completed.map { it.order })
        assertEquals(DevTaskCheckpoint.STATE_RUNNING, loaded.state)
        assertEquals("coding.validate", loaded.steps[1].tool)
        assertEquals(mapOf("type" to "repo-consistency"), loaded.steps[1].args)
    }

    @Test
    @DisplayName("remainingSteps skips the completed orders")
    fun remainingSteps() {
        val cp = checkpoint(
            completed = listOf(
                DevTaskStepResult(2, "coding.validate", true, "ok"),
            )
        )
        assertEquals(listOf(1, 3), cp.remainingSteps().map { it.order })
    }

    @Test
    @DisplayName("list returns newest checkpoints first")
    fun listNewestFirst() {
        store().save(checkpoint(id = "dt-old", updatedAt = "2026-10-21T10:00:00Z"))
        store().save(checkpoint(id = "dt-new", updatedAt = "2026-10-21T18:00:00Z"))
        val ids = store().list().map { it.id }
        assertEquals(listOf("dt-new", "dt-old"), ids)
    }

    @Test
    @DisplayName("delete removes the checkpoint")
    fun delete() {
        store().save(checkpoint())
        assertNotNull(store().load("dt-1"))
        assertTrue(store().delete("dt-1"))
        assertNull(store().load("dt-1"))
    }

    @Test
    @DisplayName("an unknown id loads as null")
    fun unknownId() {
        assertNull(store().load("nope"))
    }

    @Test
    @DisplayName("a disabled store never touches the disk")
    fun disabledStore() {
        assertFalse(store(enabled = false).save(checkpoint()))
        assertNull(store(enabled = false).load("dt-1"))
        assertFalse(Files.exists(tempDir.resolve(DevTaskCheckpointStore.DIR)))
    }

    @Test
    @DisplayName("checkpoint ids are sanitized into file names")
    fun idSanitized() {
        store().save(checkpoint(id = "dt:1/2"))
        // Lookups sanitize too, so the raw id resolves to the stored record…
        assertNotNull(store().load("dt:1/2"))
        assertNotNull(store().load("dt-1-2"))
        // …and the file name on disk is safe.
        val files = Files.list(tempDir.resolve(DevTaskCheckpointStore.DIR)).use { stream ->
            stream.map { it.fileName.toString() }.toList()
        }
        assertEquals(listOf("dt-1-2.json"), files)
    }
}
