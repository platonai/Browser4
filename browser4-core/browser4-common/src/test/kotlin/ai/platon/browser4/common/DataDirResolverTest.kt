package ai.platon.browser4.common

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Verifies that [DataDirResolver] walks its compatibility chain correctly,
 * derives every sub-directory from the chosen root, and honours the
 * per-system override properties when they are absolute paths.
 */
class DataDirResolverTest {

    private val props = listOf(
        DataDirResolver.PROPERTY_ROOT,
        DataDirResolver.PROPERTY_APP_DATA_DIR,
        DataDirResolver.PROPERTY_LEGACY_PARENT,
        DataDirResolver.PROPERTY_KNOWLEDGE_DIR,
        DataDirResolver.PROPERTY_CRAWL_DIR,
        DataDirResolver.PROPERTY_AGENT_DIR,
        DataDirResolver.PROPERTY_SWARM_DIR,
        DataDirResolver.PROPERTY_LOGS_DIR,
    )

    @BeforeEach
    fun clearAll() = props.forEach { System.clearProperty(it) }

    @AfterEach
    fun cleanup() = props.forEach { System.clearProperty(it) }

    // ---------------------------------------------------------------------
    // Root resolution — priority chain
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("browser4.data.root wins over everything")
    fun rootPropertyTakesPrecedence() {
        System.setProperty(DataDirResolver.PROPERTY_ROOT, "D:/b4-root")
        System.setProperty(DataDirResolver.PROPERTY_APP_DATA_DIR, "D:/app-data")
        System.setProperty(DataDirResolver.PROPERTY_LEGACY_PARENT, "D:/legacy-parent")

        val root = DataDirResolver.resolveRoot()
        assertEquals("D:\\b4-root", root.toString().replace('/', '\\'))
    }

    @Test
    @DisplayName("app.data.dir used when browser4.data.root is absent")
    fun appDataDirIsTheSecondFallback() {
        System.setProperty(DataDirResolver.PROPERTY_APP_DATA_DIR, "D:/app-data")
        System.setProperty(DataDirResolver.PROPERTY_LEGACY_PARENT, "D:/legacy-parent")

        val root = DataDirResolver.resolveRoot()
        assertEquals("D:\\app-data", root.toString().replace('/', '\\'))
    }

    @Test
    @DisplayName("legacy browser4.data.dir treated as parent of .browser4/ (historical behaviour)")
    fun legacyParentPropertyBecomesDotBrowser4Suffix() {
        System.setProperty(DataDirResolver.PROPERTY_LEGACY_PARENT, "D:/legacy-parent")

        val root = DataDirResolver.resolveRoot()
        // Must append .browser4 — that is what all the pre-4.17 callers did by hand
        assertTrue(root.toString().endsWith(".browser4"), "expected root under .browser4, got $root")
        assertTrue(root.toString().startsWith("D:"), "expected D drive, got $root")
    }

    @Test
    @DisplayName("empty or blank property values are skipped")
    fun blankPropertyTreatedAsUnset() {
        System.setProperty(DataDirResolver.PROPERTY_ROOT, "   ")
        System.setProperty(DataDirResolver.PROPERTY_APP_DATA_DIR, "")

        val root = DataDirResolver.resolveRoot()
        // Falls back to user.home/.browser4
        assertTrue(root.fileName?.toString() == ".browser4")
    }

    // ---------------------------------------------------------------------
    // Sub-directory derivation from root
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("crawlDir/agentDir/swarmDir/knowledgeDir all sit under the resolved root")
    fun subDirsDeriveFromRoot() {
        System.setProperty(DataDirResolver.PROPERTY_ROOT, "D:/b4")

        assertEquals("D:\\b4\\data\\crawl", DataDirResolver.crawlDir().toString())
        assertEquals("D:\\b4\\data\\agent", DataDirResolver.agentDir().toString())
        assertEquals("D:\\b4\\data\\swarm", DataDirResolver.swarmDir().toString())
        assertEquals("D:\\b4\\knowledge", DataDirResolver.knowledgeDir().toString())
        assertEquals("D:\\b4\\logs", DataDirResolver.logsDir().toString())
        assertEquals("D:\\b4\\memory", DataDirResolver.memoryDir().toString())
        assertEquals("D:\\b4\\browser", DataDirResolver.browserDir().toString())
        assertEquals("D:\\b4\\config", DataDirResolver.configDir().toString())
        assertEquals("D:\\b4\\coworker", DataDirResolver.coworkerDir().toString())
    }

    @Test
    @DisplayName("knowledgeDir matches the legacy knowledge.dir property when explicit")
    fun knowledgeOverrideHonoursLegacyProperty() {
        System.setProperty(DataDirResolver.PROPERTY_KNOWLEDGE_DIR, "E:/pem-store")

        assertEquals("E:\\pem-store", DataDirResolver.knowledgeDir().toString())
    }

    // ---------------------------------------------------------------------
    // Per-system overrides — put one subsystem on a different disk
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("crawl can be relocated to another drive while everything else stays on root")
    fun subdirOverrideCanRelocateOneSystemIndependently() {
        System.setProperty(DataDirResolver.PROPERTY_ROOT, "C:/Users/me/.browser4")
        System.setProperty(DataDirResolver.PROPERTY_CRAWL_DIR, "D:/b4-crawl")

        assertEquals("D:\\b4-crawl", DataDirResolver.crawlDir().toString())
        assertEquals("C:\\Users\\me\\.browser4\\data\\agent", DataDirResolver.agentDir().toString())
        assertEquals("C:\\Users\\me\\.browser4\\data\\swarm", DataDirResolver.swarmDir().toString())
        assertEquals("C:\\Users\\me\\.browser4\\knowledge", DataDirResolver.knowledgeDir().toString())
    }

    @Test
    @DisplayName("all five overrides can be set independently")
    fun everySubdirOverrideWorks() {
        System.setProperty(DataDirResolver.PROPERTY_ROOT, "C:/Users/me/.browser4")
        System.setProperty(DataDirResolver.PROPERTY_CRAWL_DIR, "D:/crawl")
        System.setProperty(DataDirResolver.PROPERTY_AGENT_DIR, "D:/agent")
        System.setProperty(DataDirResolver.PROPERTY_SWARM_DIR, "E:/swarm")
        System.setProperty(DataDirResolver.PROPERTY_KNOWLEDGE_DIR, "E:/knowledge")
        System.setProperty(DataDirResolver.PROPERTY_LOGS_DIR, "F:/logs")

        assertEquals("D:\\crawl", DataDirResolver.crawlDir().toString())
        assertEquals("D:\\agent", DataDirResolver.agentDir().toString())
        assertEquals("E:\\swarm", DataDirResolver.swarmDir().toString())
        assertEquals("E:\\knowledge", DataDirResolver.knowledgeDir().toString())
        assertEquals("F:\\logs", DataDirResolver.logsDir().toString())
    }

    // ---------------------------------------------------------------------
    // Real-path test against a temp directory
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("resolveRoot against an existing temp directory produces a usable path")
    fun resolveRootAgainstExistingDir(@TempDir temp: Path) {
        System.setProperty(DataDirResolver.PROPERTY_ROOT, temp.toString())
        val root = DataDirResolver.resolveRoot()
        assertTrue(root.toFile().exists())
        assertTrue(root.toFile().isDirectory)
    }

    @Test
    @DisplayName("crawlDir.absolute 子路径可解析")
    fun crawlSubPathResolves(@TempDir temp: Path) {
        System.setProperty(DataDirResolver.PROPERTY_ROOT, temp.toString())
        val crawlJsonl = DataDirResolver.crawlDir().resolve("crawl-tasks.jsonl")
        val checkpoints = DataDirResolver.crawlDir().resolve("checkpoints")
        assertTrue(crawlJsonl.toString().endsWith("crawl-tasks.jsonl"))
        assertTrue(checkpoints.toString().endsWith("checkpoints"))
    }
}
