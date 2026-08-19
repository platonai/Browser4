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
}
