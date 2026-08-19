package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.agentic.skills.DefinitionBackedSkill
import ai.platon.pulsar.agentic.skills.SkillContext
import ai.platon.pulsar.agentic.skills.SkillDefinition
import ai.platon.pulsar.agentic.skills.SkillRegistry
import ai.platon.pulsar.api.AbstractBrowser
import ai.platon.pulsar.api.AbstractWebDriver
import ai.platon.pulsar.boot.plugin.PluginInfo
import ai.platon.pulsar.boot.plugin.PluginService
import ai.platon.pulsar.boot.skill.SkillService
import ai.platon.pulsar.browser.privacy.PrivacyManager
import ai.platon.pulsar.common.collect.DelayUrl
import ai.platon.pulsar.common.collect.UrlCache
import ai.platon.pulsar.common.collect.UrlPool
import ai.platon.pulsar.loop.TaskLoop
import ai.platon.pulsar.loop.TaskLoops
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager
import ai.platon.pulsar.rest.api.service.ScrapeService
import ai.platon.pulsar.rest.session.ManagedSession
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.rest.session.SessionKind
import ai.platon.pulsar.rest.session.SessionStatus
import ai.platon.pulsar.skeleton.context.PulsarContext
import ai.platon.pulsar.skeleton.plugin.PluginManifest
import ai.platon.pulsar.skeleton.session.PulsarSession
import ai.platon.pulsar.skeleton.workflow.common.GlobalCache
import kotlinx.coroutines.runBlocking
import java.util.Queue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import java.io.File
import java.nio.file.Path
@Suppress("UNCHECKED_CAST")
class SystemStatusControllerTest {

    @TempDir
    lateinit var tempDir: Path

    private fun context(active: Boolean): PulsarContext {
        val ctx = Mockito.mock(PulsarContext::class.java)
        Mockito.`when`(ctx.isActive).thenReturn(active)
        return ctx
    }

    private fun controller(
        active: Boolean = true,
        sessions: List<ManagedSession> = emptyList(),
        pluginInfos: List<PluginInfo> = emptyList(),
        skillSummaries: List<SkillRegistry.SkillSummary> = emptyList(),
        loggingDir: String = "logs",
        urlPool: UrlPool? = null,
        taskLoops: TaskLoops? = null,
        scrapeSummary: Map<String, Int>? = null,
    ): SystemStatusController {
        val session = Mockito.mock(PulsarSession::class.java)
        val ctx = context(active)
        Mockito.`when`(session.context).thenReturn(ctx)
        if (urlPool != null) {
            val globalCache = Mockito.mock(GlobalCache::class.java)
            Mockito.`when`(ctx.globalCache).thenReturn(globalCache)
            Mockito.`when`(globalCache.urlPool).thenReturn(urlPool)
        }
        if (taskLoops != null) {
            Mockito.`when`(ctx.taskLoops).thenReturn(taskLoops)
        }
        val sessionManager = Mockito.mock(PulsarSessionManager::class.java)
        Mockito.`when`(sessionManager.getAllSessions()).thenReturn(sessions)
        val driverPoolManager = Mockito.mock(WebDriverPoolManager::class.java)
        Mockito.`when`(driverPoolManager.buildStatusString(true)).thenReturn("pool report")
        val privacyManager = Mockito.mock(PrivacyManager::class.java)
        Mockito.`when`(privacyManager.buildStatusString()).thenReturn("privacy report")
        val agenticContext = Mockito.mock(AgenticContext::class.java)
        val pluginService = Mockito.mock(PluginService::class.java)
        Mockito.`when`(pluginService.listPlugins()).thenReturn(pluginInfos)
        val skillService = Mockito.mock(SkillService::class.java)
        Mockito.`when`(skillService.listSkills()).thenReturn(skillSummaries)
        val scrapeService = Mockito.mock(ScrapeService::class.java)
        if (scrapeSummary != null) {
            Mockito.`when`(scrapeService.summary()).thenReturn(scrapeSummary)
        }
        return SystemStatusController(
            session, sessionManager, driverPoolManager, privacyManager, agenticContext,
            pluginService, skillService, scrapeService,
            loggingDir = loggingDir,
        )
    }

    private fun managedSession(
        id: String,
        status: SessionStatus = SessionStatus.ACTIVE,
        active: Boolean = true,
        kind: SessionKind = SessionKind.BROWSER4_LAUNCHED,
        browser: AbstractBrowser? = null,
        driver: AbstractWebDriver? = null,
        context: PulsarContext? = null,
    ): ManagedSession {
        val agenticSession = Mockito.mock(AgenticSession::class.java)
        Mockito.`when`(agenticSession.isActive).thenReturn(active)
        if (browser != null) {
            Mockito.`when`(agenticSession.boundBrowser).thenReturn(browser)
        }
        if (driver != null) {
            Mockito.`when`(agenticSession.boundDriver).thenReturn(driver)
        }
        if (context != null) {
            Mockito.`when`(agenticSession.context).thenReturn(context)
        }
        return ManagedSession(
            sessionId = id,
            agenticSession = agenticSession,
            capabilities = null,
            status = status,
            kind = kind,
        )
    }

    @Test
    fun `status returns healthy when context is active`() {
        val result = controller(active = true).status()

        assertEquals("healthy", result["status"])
        @Suppress("UNCHECKED_CAST")
        val health = result["health"] as Map<String, Any?>
        assertEquals(true, health["contextActive"])
        assertEquals("UP", health["check"])
        assertNotNull(result["timestamp"])
    }

    @Test
    fun `status returns unhealthy when context is inactive`() {
        val result = controller(active = false).status()

        assertEquals("unhealthy", result["status"])
        @Suppress("UNCHECKED_CAST")
        val health = result["health"] as Map<String, Any?>
        assertEquals(false, health["contextActive"])
        assertEquals("DOWN", health["check"])
    }

    @Test
    fun `status aggregates session summary`() {
        val sessions = listOf(
            managedSession("session-1", status = SessionStatus.ACTIVE, active = true),
            managedSession("session-2", status = SessionStatus.PAUSED, active = false, kind = SessionKind.SWARM),
        )
        val result = controller(sessions = sessions).status()

        @Suppress("UNCHECKED_CAST")
        val sessionInfo = result["sessions"] as Map<String, Any?>
        assertEquals(2, sessionInfo["total"])
        @Suppress("UNCHECKED_CAST")
        val byStatus = sessionInfo["byStatus"] as Map<String, Int>
        assertEquals(1, byStatus["active"])
        assertEquals(1, byStatus["paused"])

        @Suppress("UNCHECKED_CAST")
        val items = sessionInfo["items"] as List<Map<String, Any?>>
        assertEquals(2, items.size)
        val first = items.first()
        assertEquals("session-1", first["id"])
        assertEquals("active", first["status"])
        assertEquals(SessionKind.BROWSER4_LAUNCHED.name, first["kind"])
        assertEquals(true, first["active"])
        assertEquals(SessionKind.SWARM.name, items[1]["kind"])
    }

    @Test
    fun `status lists log files from logging dir`() {
        val logFile = File(tempDir.toFile(), "pulsar.log")
        logFile.writeText("line1\nline2\n")
        File(tempDir.toFile(), "ignore.txt").writeText("not a log")

        val result = controller(loggingDir = tempDir.toString()).status()

        @Suppress("UNCHECKED_CAST")
        val logs = result["logs"] as Map<String, Any?>
        assertEquals(true, logs["exists"])
        assertEquals(1, logs["count"])
        @Suppress("UNCHECKED_CAST")
        val files = logs["files"] as List<Map<String, Any?>>
        assertEquals("pulsar.log", files[0]["name"])
        assertTrue((files[0]["size"] as Long) > 0)
        assertTrue((files[0]["sizeHuman"] as String).isNotBlank())
    }

    @Test
    fun `status handles missing log directory`() {
        val result = controller(loggingDir = tempDir.resolve("no-such-dir").toString()).status()

        @Suppress("UNCHECKED_CAST")
        val logs = result["logs"] as Map<String, Any?>
        assertEquals(false, logs["exists"])
        assertEquals(0, logs["count"])
        @Suppress("UNCHECKED_CAST")
        val files = logs["files"] as List<*>
        assertTrue(files.isEmpty())
    }

    @Test
    fun `status includes build runtime metrics driver and privacy sections`() {
        val result = controller().status()

        @Suppress("UNCHECKED_CAST")
        val build = result["build"] as Map<String, Any?>
        assertTrue(build.containsKey("version"))

        @Suppress("UNCHECKED_CAST")
        val runtime = result["runtime"] as Map<String, Any?>
        assertTrue((runtime["uptimeSeconds"] as Long) >= 0)
        @Suppress("UNCHECKED_CAST")
        val memory = runtime["memory"] as Map<String, Any?>
        assertTrue((memory["heapUsed"] as Long) >= 0)
        assertTrue((memory["heapMax"] as Long) > 0)

        @Suppress("UNCHECKED_CAST")
        val metrics = result["metrics"] as Map<String, Any?>
        assertTrue((metrics["total"] as Int) >= 0)
        assertTrue(metrics.containsKey("timers"))

        @Suppress("UNCHECKED_CAST")
        val llm = result["llm"] as Map<String, Any?>
        assertTrue(llm.containsKey("configured"))
        assertTrue(llm["configured"] is Boolean)

        @Suppress("UNCHECKED_CAST")
        val drivers = result["drivers"] as Map<String, Any?>
        assertEquals("pool report", drivers["report"])

        @Suppress("UNCHECKED_CAST")
        val privacy = result["privacy"] as Map<String, Any?>
        assertEquals("privacy report", privacy["report"])
    }

    @Test
    fun `status reports plugins with load and compatibility state`() {
        val manifest = PluginManifest(
            name = "browser4-wordcount",
            version = "1.0.0",
            description = "Count words",
            sdkVersion = "4.14.0",
        )
        val pluginInfos = listOf(
            PluginInfo(
                fileName = "browser4-wordcount-1.0.0.jar",
                fileSize = 1024,
                path = "/plugins/browser4-wordcount-1.0.0.jar",
                manifest = manifest,
                loaded = true,
                defaultEnabled = true,
                enabled = true,
            ),
            PluginInfo(
                fileName = "legacy.jar",
                fileSize = 512,
                path = "/plugins/legacy.jar",
                manifest = null,
                loaded = false,
                defaultEnabled = true,
                enabled = false,
            ),
        )

        val result = controller(pluginInfos = pluginInfos).status()

        @Suppress("UNCHECKED_CAST")
        val plugins = result["plugins"] as Map<String, Any?>
        assertEquals(2, plugins["total"])
        assertEquals(1, plugins["loaded"])
        assertEquals(1, plugins["enabled"])
        assertEquals(Path.of("/plugins/browser4-wordcount-1.0.0.jar").parent.toString(), plugins["directory"])

        @Suppress("UNCHECKED_CAST")
        val items = plugins["items"] as List<Map<String, Any?>>
        val first = items[0]
        assertEquals("browser4-wordcount", first["name"])
        assertEquals("1.0.0", first["version"])
        assertEquals("4.14.0", first["sdkVersion"])
        assertEquals(true, first["loaded"])
        assertEquals(true, first["enabled"])
        assertEquals(1024L, first["fileSize"])

        @Suppress("UNCHECKED_CAST")
        val compatibility = first["compatibility"] as Map<String, Any?>
        assertEquals("compatible", compatibility["verdict"])

        val second = items[1]
        assertEquals("legacy.jar", second["fileName"])
        assertEquals(false, second["loaded"])
        assertEquals(false, second["enabled"])
        assertEquals(null, second["compatibility"])
    }

    @Test
    fun `status handles empty plugin list`() {
        val result = controller(pluginInfos = emptyList()).status()

        @Suppress("UNCHECKED_CAST")
        val plugins = result["plugins"] as Map<String, Any?>
        assertEquals(0, plugins["total"])
        assertEquals(0, plugins["loaded"])
        assertEquals(0, plugins["enabled"])
        assertEquals(null, plugins["directory"])
        @Suppress("UNCHECKED_CAST")
        val items = plugins["items"] as List<*>
        assertTrue(items.isEmpty())
    }

    @Test
    fun `status reports browsers with open tab counts`() {
        val browser = Mockito.mock(AbstractBrowser::class.java)
        val driver = Mockito.mock(AbstractWebDriver::class.java)
        Mockito.`when`(browser.drivers).thenReturn(mapOf("tab-1" to driver, "tab-2" to driver))
        val session = managedSession("session-1", browser = browser, driver = driver)

        val result = controller(sessions = listOf(session)).status()

        @Suppress("UNCHECKED_CAST")
        val browsers = result["browsers"] as Map<String, Any?>
        assertEquals(1, browsers["total"])
        assertEquals(2, browsers["tabTotal"])
        @Suppress("UNCHECKED_CAST")
        val items = browsers["items"] as List<Map<String, Any?>>
        val first = items[0]
        assertEquals("session-1", first["sessionId"])
        assertEquals("active", first["sessionStatus"])
        assertEquals(true, first["hasBrowser"])
        assertEquals(true, first["hasDriver"])
        assertEquals(2, first["tabCount"])
    }

    @Test
    fun `status reports sessions without browsers`() {
        val session = managedSession("session-1", status = SessionStatus.STOPPED)

        val result = controller(sessions = listOf(session)).status()

        @Suppress("UNCHECKED_CAST")
        val browsers = result["browsers"] as Map<String, Any?>
        assertEquals(0, browsers["total"])
        assertEquals(0, browsers["tabTotal"])
        @Suppress("UNCHECKED_CAST")
        val items = browsers["items"] as List<Map<String, Any?>>
        assertEquals(false, items[0]["hasBrowser"])
        assertEquals(false, items[0]["hasDriver"])
        assertEquals(null, items[0]["tabCount"])
    }

    @Test
    fun `tabs endpoint returns live tab details`() = runBlocking {
        val browser = Mockito.mock(AbstractBrowser::class.java)
        val driver = Mockito.mock(AbstractWebDriver::class.java)
        Mockito.`when`(driver.guid).thenReturn("tab-1")
        runBlocking { Mockito.`when`(driver.title()).thenReturn("Example") }
        runBlocking { Mockito.`when`(driver.currentUrl()).thenReturn("https://example.com") }
        runBlocking { Mockito.`when`(browser.listDrivers()).thenReturn(listOf(driver)) }
        Mockito.`when`(browser.frontDriver).thenReturn(driver)
        val session = managedSession("session-1", browser = browser)

        val result = controller(sessions = listOf(session)).tabs(sessionId = null)

        assertEquals(1, result["total"])
        assertEquals(1, result["tabTotal"])
        @Suppress("UNCHECKED_CAST")
        val items = result["items"] as List<Map<String, Any?>>
        assertEquals("session-1", items[0]["sessionId"])
        assertEquals(1, items[0]["tabCount"])
        @Suppress("UNCHECKED_CAST")
        val tabs = items[0]["tabs"] as List<Map<String, Any?>>
        assertEquals(1, tabs.size)
        assertEquals("tab-1", tabs[0]["guid"])
        assertEquals("Example", tabs[0]["title"])
        assertEquals("https://example.com", tabs[0]["url"])
        assertEquals(true, tabs[0]["active"])
    }

    @Test
    fun `tabs endpoint skips sessions without a bound browser`() = runBlocking {
        val session = managedSession("session-1")
        val result = controller(sessions = listOf(session)).tabs(sessionId = null)

        assertEquals(0, result["total"])
        assertEquals(0, result["tabTotal"])
        @Suppress("UNCHECKED_CAST")
        val items = result["items"] as List<*>
        assertTrue(items.isEmpty())
    }

    @Test
    fun `status reports underlying pulsar sessions`() {
        val agenticSession = Mockito.mock(AgenticSession::class.java)
        Mockito.`when`(agenticSession.isActive).thenReturn(true)
        Mockito.`when`(agenticSession.id).thenReturn(42L)
        Mockito.`when`(agenticSession.uuid).thenReturn("uuid-1")
        Mockito.`when`(agenticSession.label).thenReturn("default")
        Mockito.`when`(agenticSession.display).thenReturn("DEFAULT")
        val ctx = Mockito.mock(PulsarContext::class.java)
        Mockito.`when`(ctx.id).thenReturn(7)
        Mockito.`when`(ctx.isActive).thenReturn(true)
        val loops = Mockito.mock(TaskLoops::class.java)
        Mockito.`when`(loops.isRunning).thenReturn(true)
        val loop = Mockito.mock(TaskLoop::class.java)
        Mockito.`when`(loop.report).thenReturn("loop report")
        Mockito.`when`(loops.loops).thenReturn(mutableListOf(loop))
        Mockito.`when`(ctx.taskLoops).thenReturn(loops)
        Mockito.`when`(agenticSession.context).thenReturn(ctx)
        val session = ManagedSession("session-1", agenticSession, null)

        val result = controller(sessions = listOf(session)).status()

        @Suppress("UNCHECKED_CAST")
        val pulsar = result["pulsarSessions"] as Map<String, Any?>
        assertEquals(1, pulsar["total"])
        @Suppress("UNCHECKED_CAST")
        val items = pulsar["items"] as List<Map<String, Any?>>
        val first = items[0]
        assertEquals("session-1", first["managedSessionId"])
        assertEquals(42L, first["id"])
        assertEquals("uuid-1", first["uuid"])
        assertEquals("default", first["label"])
        assertEquals("DEFAULT", first["display"])
        assertEquals(true, first["isActive"])
        assertEquals(7, first["contextId"])
        assertEquals(true, first["contextActive"])
        assertEquals(true, first["mainLoopRunning"])
        assertEquals("loop report", first["mainLoopReport"])
    }

    @Test
    fun `status reports swarm session and task summary`() {
        val swarmSession = managedSession("swarm", status = SessionStatus.ACTIVE, kind = SessionKind.SWARM)
        val summary = mapOf("total" to 5, "done" to 3, "running" to 2)

        val result = controller(sessions = listOf(swarmSession), scrapeSummary = summary).status()

        @Suppress("UNCHECKED_CAST")
        val swarm = result["swarm"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val sessionInfo = swarm["session"] as Map<String, Any?>
        assertEquals("swarm", sessionInfo["id"])
        assertEquals("active", sessionInfo["status"])
        assertEquals(true, sessionInfo["active"])
        @Suppress("UNCHECKED_CAST")
        val tasks = swarm["tasks"] as Map<String, Int>
        assertEquals(5, tasks["total"])
        assertEquals(3, tasks["done"])
        assertEquals(2, tasks["running"])
    }

    @Test
    fun `status reports no swarm session when none exists`() {
        val result = controller().status()

        @Suppress("UNCHECKED_CAST")
        val swarm = result["swarm"] as Map<String, Any?>
        assertEquals(null, swarm["session"])
        @Suppress("UNCHECKED_CAST")
        val tasks = swarm["tasks"] as Map<String, Int>
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun `status reports url pool state`() {
        val urlPool = Mockito.mock(UrlPool::class.java)
        Mockito.`when`(urlPool.id).thenReturn("pool-1")
        Mockito.`when`(urlPool.totalCount).thenReturn(10)
        val realTime = Mockito.mock(UrlCache::class.java)
        Mockito.`when`(realTime.name).thenReturn("realtime")
        Mockito.`when`(realTime.size).thenReturn(3)
        Mockito.`when`(urlPool.realTimeCache).thenReturn(realTime)
        val delay = Mockito.mock(Queue::class.java) as Queue<DelayUrl>
        Mockito.`when`(delay.size).thenReturn(2)
        Mockito.`when`(urlPool.delayCache).thenReturn(delay)
        val normal = Mockito.mock(UrlCache::class.java)
        Mockito.`when`(normal.name).thenReturn("normal")
        Mockito.`when`(normal.size).thenReturn(7)
        Mockito.`when`(urlPool.orderedCaches).thenReturn(mutableMapOf(0 to normal))

        val result = controller(urlPool = urlPool).status()

        @Suppress("UNCHECKED_CAST")
        val up = result["urlPool"] as Map<String, Any?>
        assertEquals("pool-1", up["id"])
        assertEquals(10, up["totalCount"])
        assertEquals(3, up["realTime"])
        assertEquals(2, up["delay"])
        @Suppress("UNCHECKED_CAST")
        val caches = up["caches"] as Map<Int, Map<String, Any?>>
        assertEquals("normal", caches[0]?.get("name"))
        assertEquals(7, caches[0]?.get("size"))
    }

    @Test
    fun `status reports url pool error when pool unavailable`() {
        val result = controller(urlPool = null).status()

        // Default mocked context has no globalCache — the pool query fails
        // gracefully and the section carries an error instead of throwing.
        @Suppress("UNCHECKED_CAST")
        val up = result["urlPool"] as Map<String, Any?>
        assertTrue(up.containsKey("error"))
    }

    @Test
    fun `status handles empty skill list`() {
        val result = controller(skillSummaries = emptyList()).status()

        @Suppress("UNCHECKED_CAST")
        val skills = result["skills"] as Map<String, Any?>
        assertEquals(0, skills["total"])
        @Suppress("UNCHECKED_CAST")
        val byOrigin = skills["byOrigin"] as Map<String, Int>
        assertTrue(byOrigin.isEmpty())
        @Suppress("UNCHECKED_CAST")
        val items = skills["items"] as List<*>
        assertTrue(items.isEmpty())
    }

    @Test
    fun `status reports skills with origin classification`() = runBlocking {
        val registry = SkillRegistry.instance
        val context = SkillContext(sessionId = "status-test")
        val classpathSkill = DefinitionBackedSkill(
            definition(
                id = "status-test-classpath",
                name = "Status Test Classpath",
            ),
            DefinitionBackedSkill.Origin.Classpath("skills/status-test-classpath"),
        )
        val fileSystemSkill = DefinitionBackedSkill(
            definition(
                id = "status-test-filesystem",
                name = "Status Test Filesystem",
            ),
            DefinitionBackedSkill.Origin.FileSystem(Path.of("/skills/status-test-filesystem")),
        )
        try {
            registry.register(classpathSkill, context)
            registry.register(fileSystemSkill, context)

            val summaries = listOf(
                SkillRegistry.SkillSummary(
                    id = "status-test-classpath",
                    name = "Status Test Classpath",
                    description = "A classpath-backed test skill",
                    version = "1.0.0",
                    tags = setOf("test"),
                ),
                SkillRegistry.SkillSummary(
                    id = "status-test-filesystem",
                    name = "Status Test Filesystem",
                    description = "A filesystem-backed test skill",
                    version = "2.0.0",
                    tags = setOf("test", "local"),
                ),
            )

            val result = controller(skillSummaries = summaries).status()

            @Suppress("UNCHECKED_CAST")
            val skills = result["skills"] as Map<String, Any?>
            assertEquals(2, skills["total"])
            @Suppress("UNCHECKED_CAST")
            val byOrigin = skills["byOrigin"] as Map<String, Int>
            assertEquals(1, byOrigin["classpath"])
            assertEquals(1, byOrigin["filesystem"])
            @Suppress("UNCHECKED_CAST")
            val items = skills["items"] as List<Map<String, Any?>>
            val first = items.first { it["id"] == "status-test-classpath" }
            assertEquals("Status Test Classpath", first["name"])
            assertEquals("1.0.0", first["version"])
            assertEquals("A classpath-backed test skill", first["description"])
            assertEquals("classpath", first["originKind"])
            assertEquals("classpath:skills/status-test-classpath", first["origin"])
            @Suppress("UNCHECKED_CAST")
            assertEquals(listOf("test"), first["tags"])
            val second = items.first { it["id"] == "status-test-filesystem" }
            assertEquals("filesystem", second["originKind"])
            assertEquals(Path.of("/skills/status-test-filesystem").toString(), (second["origin"] as String).removePrefix("filesystem:"))
        } finally {
            registry.unregister("status-test-classpath", context)
            registry.unregister("status-test-filesystem", context)
        }
    }

    private fun definition(id: String, name: String): SkillDefinition {
        return SkillDefinition(
            skillId = id,
            name = name,
            version = "1.0.0",
            author = "status-test",
            tags = setOf("test"),
            description = "Test skill $id",
            dependencies = emptyList(),
            parameters = emptyMap(),
            examples = emptyList(),
        )
    }
}
