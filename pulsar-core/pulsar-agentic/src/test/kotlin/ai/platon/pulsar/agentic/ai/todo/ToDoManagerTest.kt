package ai.platon.pulsar.agentic.ai.todo

import ai.platon.pulsar.agentic.AgentConfig
import ai.platon.pulsar.agentic.ToolCall
import ai.platon.pulsar.agentic.common.AgentFileSystem
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*

/**
 * Unit tests for ToDoManager component.
 */
class ToDoManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var fs: AgentFileSystem
    private lateinit var config: AgentConfig
    private lateinit var uuid: UUID
    private lateinit var todoManager: ToDoManager

    @BeforeEach
    fun setUp() {
        fs = AgentFileSystem(tempDir, createDefaultFiles = true)
        config = AgentConfig(
            enableTodoWrites = true,
            todoPlanWithLLM = true,
            todoMaxProgressLines = 10
        )
        uuid = UUID.randomUUID()
        todoManager = ToDoManager(fs, config, uuid)
    }

    @Test
    fun `primeIfEmpty should initialize todo file with sections`() = runBlocking {
        // Clear the default content first
        fs.writeString("todolist.md", "")

        todoManager.primeIfEmpty("Test instruction", "https://example.com")

        val content = fs.getTodoContents()
        assertTrue(content.contains("# TODO for session"))
        assertTrue(content.contains("Instruction: Test instruction"))
        assertTrue(content.contains("Current URL: https://example.com"))
        assertTrue(content.contains("Progress: (0/∞)"))
        assertTrue(content.contains(ToDoManager.SECTION_PLAN))
        assertTrue(content.contains(ToDoManager.SECTION_PROGRESS_LOG))
        assertTrue(content.contains(ToDoManager.SECTION_NOTES))
    }

    @Test
    fun `primeIfEmpty should not overwrite existing content`() = runBlocking {
        val existingContent = "Existing content"
        fs.writeString("todolist.md", existingContent)

        todoManager.primeIfEmpty("New instruction", "https://new.com")

        val content = fs.getTodoContents()
        assertEquals(existingContent, content)
    }

    @Test
    fun `primeIfEmpty should handle null URL`() = runBlocking {
        fs.writeString("todolist.md", "")

        todoManager.primeIfEmpty("Test instruction", null)

        val content = fs.getTodoContents()
        assertTrue(content.contains("Current URL: (unknown)"))
    }

    @Test
    fun `appendProgress should add progress line`() = runBlocking {
        fs.writeString("todolist.md", "Initial content\n")

        val toolCall = ToolCall(domain = "driver", method = "click")
        val result = todoManager.appendProgress(1, toolCall, null, "https://example.com", "Clicked button")

        assertTrue(result)
        val content = fs.getTodoContents()
        assertTrue(content.contains("${ToDoManager.MARKER_OK}"))
        assertTrue(content.contains("click"))
        assertTrue(content.contains("https://example.com"))
        assertTrue(content.contains("Clicked button"))
    }

    @Test
    fun `appendProgress should respect max lines limit`() = runBlocking {
        // Create content with max progress lines already
        val existingLines = (1..10).joinToString("\n") { "${ToDoManager.MARKER_OK} line $it" }
        fs.writeString("todolist.md", existingLines)

        val toolCall = ToolCall(domain = "driver", method = "type")
        val result = todoManager.appendProgress(11, toolCall, null, "https://example.com", "New progress")

        assertFalse(result)
    }

    @Test
    fun `updateProgressCounter should increment counter`() = runBlocking {
        fs.writeString("todolist.md", "Progress: (5/∞)\nOther content")

        todoManager.updateProgressCounter()

        val content = fs.getTodoContents()
        assertTrue(content.contains("Progress: (6/∞)"))
    }

    @Test
    fun `updateProgressCounter should handle numeric denominator`() = runBlocking {
        fs.writeString("todolist.md", "Progress: (3/10)\nOther content")

        todoManager.updateProgressCounter()

        val content = fs.getTodoContents()
        assertTrue(content.contains("Progress: (4/10)"))
    }

    @Test
    fun `markPlanItemDoneByTags should check matching item`() = runBlocking {
        val content = """
            ## Plan
            - [ ] Step 1: Navigate to page #action:navigateto
            - [ ] Step 2: Click button #action:click
        """.trimIndent()
        fs.writeString("todolist.md", content)

        todoManager.markPlanItemDoneByTags(setOf("#action:navigateto"))

        val updated = fs.getTodoContents()
        assertTrue(updated.contains("${ToDoManager.MARKER_CHECKED} Step 1"))
        assertTrue(updated.contains("${ToDoManager.MARKER_UNCHECKED} Step 2"))
    }

    @Test
    fun `markPlanItemDoneByTags should be case insensitive`() = runBlocking {
        val content = """
            ## Plan
            - [ ] Step 1: Navigate #ACTION:NAVIGATETO
        """.trimIndent()
        fs.writeString("todolist.md", content)

        todoManager.markPlanItemDoneByTags(setOf("#action:navigateto"))

        val updated = fs.getTodoContents()
        assertTrue(updated.contains(ToDoManager.MARKER_CHECKED))
    }

    @Test
    fun `markPlanItemDoneByTags should not modify if no tags match`() = runBlocking {
        val content = """
            ## Plan
            - [ ] Step 1: Navigate #action:navigateto
        """.trimIndent()
        fs.writeString("todolist.md", content)

        todoManager.markPlanItemDoneByTags(setOf("#action:click"))

        val updated = fs.getTodoContents()
        assertTrue(updated.contains(ToDoManager.MARKER_UNCHECKED))
        assertFalse(updated.contains(ToDoManager.MARKER_CHECKED))
    }

    @Test
    fun `markPlanItemDoneByTags should do nothing for empty tags`() = runBlocking {
        val content = "- [ ] Step 1: Navigate"
        fs.writeString("todolist.md", content)

        todoManager.markPlanItemDoneByTags(emptySet())

        val updated = fs.getTodoContents()
        assertEquals(content, updated)
    }

    @Test
    fun `buildTags should create action and domain tags`() {
        val toolCall = ToolCall(domain = "driver", method = "click")

        val tags = todoManager.buildTags(toolCall, "https://example.com/page")

        assertTrue(tags.contains("#action:click"))
        assertTrue(tags.contains("#domain:example.com"))
    }

    @Test
    fun `buildTags should handle URL without scheme`() {
        val toolCall = ToolCall(domain = "driver", method = "type")

        val tags = todoManager.buildTags(toolCall, "example.com/page")

        assertTrue(tags.contains("#domain:example.com"))
    }

    @Test
    fun `buildTags should return empty set for null toolCall`() {
        val tags = todoManager.buildTags(null, "https://example.com")

        assertTrue(tags.isEmpty())
    }

    @Test
    fun `buildTags should handle null URL`() {
        val toolCall = ToolCall(domain = "driver", method = "click")

        val tags = todoManager.buildTags(toolCall, null)

        assertEquals(1, tags.size)
        assertTrue(tags.contains("#action:click"))
    }

    @Test
    fun `buildTags should handle invalid URL`() {
        val toolCall = ToolCall(domain = "driver", method = "click")

        val tags = todoManager.buildTags(toolCall, "not a valid url :::")

        assertEquals(1, tags.size)
        assertTrue(tags.contains("#action:click"))
    }

    @Test
    fun `onTaskCompletion should append completion marker`() = runBlocking {
        fs.writeString("todolist.md", "Progress: (5/∞)\n")

        todoManager.onTaskCompletion("Complete the task")

        val content = fs.getTodoContents()
        assertTrue(content.contains("${ToDoManager.MARKER_OK}"))
        assertTrue(content.contains("task.complete"))
        assertTrue(content.contains("Complete the task"))
        assertTrue(content.contains("Progress: (6/∞)"))
    }

    @Test
    fun `getProgressCount should return current count`() = runBlocking {
        fs.writeString("todolist.md", "Progress: (7/∞)\n")

        val count = todoManager.getProgressCount()

        assertEquals(7, count)
    }

    @Test
    fun `getProgressCount should return 0 if not found`() = runBlocking {
        fs.writeString("todolist.md", "No progress line here")

        val count = todoManager.getProgressCount()

        assertEquals(0, count)
    }

    @Test
    fun `getPlan should return plan items`() = runBlocking {
        val content = """
            # Header
            ## Plan
            - [ ] Step 1: First step
            - [x] Step 2: Second step
            - [ ] Step 3: Third step
            
            ## Progress Log
            Some progress
        """.trimIndent()
        fs.writeString("todolist.md", content)

        val plan = todoManager.getPlan()

        assertEquals(3, plan.size)
        assertTrue(plan[0].contains("Step 1"))
        assertTrue(plan[1].contains("Step 2"))
        assertTrue(plan[2].contains("Step 3"))
    }

    @Test
    fun `getPlan should return empty list if no plan section`() = runBlocking {
        fs.writeString("todolist.md", "No plan section here")

        val plan = todoManager.getPlan()

        assertTrue(plan.isEmpty())
    }

    @Test
    fun `clearCompletedPlanItems should remove checked items`() = runBlocking {
        val content = """
            ## Plan
            - [ ] Step 1: Pending
            - [x] Step 2: Completed
            - [ ] Step 3: Pending
            - [x] Step 4: Also completed
        """.trimIndent()
        fs.writeString("todolist.md", content)

        val removedCount = todoManager.clearCompletedPlanItems()

        assertEquals(2, removedCount)
        val updated = fs.getTodoContents()
        assertFalse(updated.contains("Step 2"))
        assertFalse(updated.contains("Step 4"))
        assertTrue(updated.contains("Step 1"))
        assertTrue(updated.contains("Step 3"))
    }

    @Test
    fun `clearCompletedPlanItems should return 0 if nothing to remove`() = runBlocking {
        val content = """
            ## Plan
            - [ ] Step 1: Pending
            - [ ] Step 2: Also pending
        """.trimIndent()
        fs.writeString("todolist.md", content)

        val removedCount = todoManager.clearCompletedPlanItems()

        assertEquals(0, removedCount)
    }

    @Test
    fun `companion object constants should have correct values`() {
        assertEquals("## Plan", ToDoManager.SECTION_PLAN)
        assertEquals("## Progress Log", ToDoManager.SECTION_PROGRESS_LOG)
        assertEquals("## Notes", ToDoManager.SECTION_NOTES)
        assertEquals("- [OK]", ToDoManager.MARKER_OK)
        assertEquals("- [ ]", ToDoManager.MARKER_UNCHECKED)
        assertEquals("- [x]", ToDoManager.MARKER_CHECKED)
    }
}
