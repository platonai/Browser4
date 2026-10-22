package ai.platon.pulsar.agentic.tools.advanced.agent

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class CodingTaskDetectorTest {

    @Test
    @DisplayName("file-path based coding tasks are detected")
    fun testFileCodingTasks() {
        assertTrue(CodingTaskDetector.detect("读取 browser4-plugins/browser4-pagetitle/src/main/kotlin/a.kt 并总结"))
        assertTrue(CodingTaskDetector.detect("Write a README.md for the new module"))
        assertTrue(CodingTaskDetector.detect("修改 pom.xml 增加依赖"))
        assertTrue(CodingTaskDetector.detect("编译 browser4-pagetitle 模块并修复错误"))
    }

    @Test
    @DisplayName("scaffold/plugin tasks are coding tasks")
    fun testPluginTasks() {
        assertTrue(CodingTaskDetector.detect("scaffold a new plugin module browser4-weather"))
        assertTrue(CodingTaskDetector.detect("创建新插件"))
    }

    @Test
    @DisplayName("URLs and page verbs always win")
    fun testPageTasksNotCoding() {
        assertFalse(CodingTaskDetector.detect("打开 https://example.com 并总结页面"))
        assertFalse(CodingTaskDetector.detect("打开网页并点击登录按钮"))
        assertFalse(CodingTaskDetector.detect("www.baidu.com 搜索文件"))
        assertFalse(CodingTaskDetector.detect("http://localhost:8080 检查健康状态"))
    }

    @Test
    @DisplayName("blank and ambiguous commands are not coding")
    fun testBlankAndAmbiguous() {
        assertFalse(CodingTaskDetector.detect(""))
        assertFalse(CodingTaskDetector.detect("hello world"))
    }

    @Test
    @DisplayName("mvn build command is a coding task")
    fun testMvnCommand() {
        assertTrue(CodingTaskDetector.detect("mvn -f browser4-plugins/browser4-pagetitle/pom.xml package -q"))
    }

    @Test
    @DisplayName("refactor verbs are coding tasks even without a file path")
    fun testRefactorVerbs() {
        // A terse refactor instruction carries no URL and no file path. Classifying it
        // as a browsing task exposed the browsing tool profile and made the model spend
        // its first rounds on b4.run / system.listTools instead of the source file
        // (observed: "cli-agent tools exposed: … profile=browsing").
        assertTrue(CodingTaskDetector.detect("重构 Browser4WebDriver"))
        assertTrue(CodingTaskDetector.detect("重构登录流程"))
        assertTrue(CodingTaskDetector.detect("把 Browser4WebDriver 拆分成多个文件"))
        assertTrue(CodingTaskDetector.detect("修复 AgentToolManager 的并发问题"))
        assertTrue(CodingTaskDetector.detect("refactor Browser4WebDriver into smaller files"))
        assertTrue(CodingTaskDetector.detect("Refactor the click path"))
    }

    @Test
    @DisplayName("a qualified source name is a coding task")
    fun testQualifiedSourceName() {
        assertTrue(CodingTaskDetector.detect("ai.platon.pulsar.chrome.Browser4WebDriver 有哪些方法"))
    }

    @Test
    @DisplayName("prose with capitalized words is not a coding task")
    fun testProseIsNotCoding() {
        // Bare CamelCase-looking words only count when they end in a code suffix.
        assertFalse(CodingTaskDetector.detect("Summarize the HackerNews front page"))
        assertFalse(CodingTaskDetector.detect("Open the LoginPage and check it"))
        assertFalse(CodingTaskDetector.detect("latest browser news"))
    }

    @Test
    @DisplayName("page tasks still win over refactor verbs")
    fun testPageSignalsWinOverRefactor() {
        assertFalse(CodingTaskDetector.detect("打开 https://example.com 并重构页面布局"))
        assertFalse(CodingTaskDetector.detect("refactor the button on https://example.com"))
        assertFalse(CodingTaskDetector.detect("打开页面并重构布局"))
    }
}
