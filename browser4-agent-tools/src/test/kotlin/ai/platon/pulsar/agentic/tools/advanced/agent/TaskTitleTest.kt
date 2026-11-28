package ai.platon.pulsar.agentic.tools.advanced.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("task title: the model's answer becomes a heading or nothing")
class TaskTitleTest {

    @Test
    @DisplayName("a plain answer is kept as is")
    fun plainAnswerIsKept() {
        assertEquals("总结 example.com 首页", normalizeTaskTitle("总结 example.com 首页"))
        assertEquals("Summarize the example.com homepage", normalizeTaskTitle(" Summarize the example.com homepage "))
    }

    @Test
    @DisplayName("nothing usable degrades to no title")
    fun nothingUsableDegradesToNoTitle() {
        assertNull(normalizeTaskTitle(null))
        assertNull(normalizeTaskTitle(""))
        assertNull(normalizeTaskTitle("   \n  \n"))
        assertNull(normalizeTaskTitle("。！？."), "punctuation only")
    }

    @Test
    @DisplayName("wrappers, labels and sentence punctuation are peeled")
    fun wrappersLabelsAndPunctuationArePeeled() {
        assertEquals("打开首页", normalizeTaskTitle("「打开首页」"))
        assertEquals("打开首页", normalizeTaskTitle("“打开首页”。"))
        assertEquals("打开首页", normalizeTaskTitle("**打开首页**"))
        assertEquals("打开首页", normalizeTaskTitle("标题：打开首页"))
        assertEquals("打开首页", normalizeTaskTitle("Title: 打开首页"))
        assertEquals("打开首页", normalizeTaskTitle("## 打开首页"))
        assertEquals("打开首页", normalizeTaskTitle("**标题：打开首页**。"), "wrappers can nest")
        assertEquals("Open the homepage", normalizeTaskTitle("\"Open the homepage.\""))
    }

    @Test
    @DisplayName("a name that happens to end with a hash or dot is not eaten")
    fun aNameEndingInHashOrDotIsNotEaten() {
        assertEquals("修复 C# 构建", normalizeTaskTitle("修复 C# 构建"))
        assertEquals("打开 example.com", normalizeTaskTitle("打开 example.com"))
    }

    @Test
    @DisplayName("an explaining model is answered by its first line")
    fun anExplainingModelIsAnsweredByItsFirstLine() {
        assertEquals("整理搜索结果", normalizeTaskTitle("\n\n整理搜索结果\n\n这个标题概括了任务的目标。"))
    }

    @Test
    @DisplayName("whitespace is collapsed and a long title is cut")
    fun whitespaceIsCollapsedAndALongTitleIsCut() {
        assertEquals("a b c", normalizeTaskTitle("a   b\t\tc"))
        val long = "一二三四五六七八九十".repeat(6)
        val title = normalizeTaskTitle(long)!!
        assertEquals(TASK_TITLE_MAX_CHARS + 1, title.length)
        assertTrue(title.endsWith("…"))
    }

    @Test
    @DisplayName("the prompt carries the task and asks for the title only")
    fun thePromptCarriesTheTaskAndAsksForTheTitleOnly() {
        val prompt = buildTaskTitlePrompt("  打开 example.com  ")
        assertTrue(prompt.endsWith("Task:\n打开 example.com"))
        assertTrue(prompt.contains("Output the title only"))
        assertEquals(2_000, buildTaskTitlePrompt("x".repeat(5_000)).substringAfter("Task:\n").length, "the task is cut")
    }

    @Test
    @DisplayName("a name that merely starts with the word title is not mistaken for a label")
    fun aNameStartingWithTitleIsNotMistakenForALabel() {
        assertEquals("Title-Driven Development", normalizeTaskTitle("Title-Driven Development"))
        assertEquals("整理结果", normalizeTaskTitle("Title - 整理结果"))
    }
}
