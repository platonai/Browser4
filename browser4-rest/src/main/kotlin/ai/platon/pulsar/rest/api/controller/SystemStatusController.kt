package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.context.AgenticContext
import ai.platon.pulsar.agentic.skills.DefinitionBackedSkill
import ai.platon.pulsar.agentic.skills.SkillRegistry
import ai.platon.pulsar.api.AbstractWebDriver
import ai.platon.pulsar.boot.plugin.PluginCompatibility
import ai.platon.pulsar.boot.plugin.PluginService
import ai.platon.pulsar.boot.skill.SkillService
import ai.platon.pulsar.browser.privacy.PrivacyManager
import ai.platon.pulsar.external.ChatModelFactory
import ai.platon.pulsar.protocol.browser.driver.WebDriverPoolManager
import ai.platon.pulsar.rest.api.service.ScrapeService
import ai.platon.pulsar.rest.session.ManagedSession
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.rest.session.SessionKind
import ai.platon.pulsar.skeleton.common.metrics.MetricsSystem
import ai.platon.pulsar.skeleton.session.PulsarSession
import com.codahale.metrics.Counter
import com.codahale.metrics.Gauge
import com.codahale.metrics.Histogram
import com.codahale.metrics.Meter
import com.codahale.metrics.Timer
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.info.GitProperties
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.time.Instant
import java.util.Properties

/**
 * Aggregated system status endpoint powering the web status panel at `/status.html`.
 *
 * Unlike the individual `api/system` and `api/doctor` endpoints, this endpoint
 * returns a single JSON document covering health, build info, runtime/JVM state,
 * LLM configuration, live sessions, driver pool, metrics summary and log files —
 * everything a status dashboard needs in one round trip.
 */
@RestController
@CrossOrigin
@RequestMapping("api/system")
class SystemStatusController(
    val session: PulsarSession,
    val sessionManager: PulsarSessionManager,
    val driverPoolManager: WebDriverPoolManager,
    val privacyManager: PrivacyManager,
    val agenticContext: AgenticContext,
    val pluginService: PluginService,
    val skillService: SkillService,
    val scrapeService: ScrapeService,
    val gitProperties: GitProperties? = null,
    @Value("\${logging.dir:logs}") private val loggingDir: String = "logs",
) {
    private val logger = LoggerFactory.getLogger(SystemStatusController::class.java)

    @GetMapping("status")
    fun status(): Map<String, Any?> {
        val contextActive = session.context.isActive
        return mapOf(
            "status" to if (contextActive) "healthy" else "unhealthy",
            "timestamp" to Instant.now().toString(),
            "health" to mapOf(
                "contextActive" to contextActive,
                "check" to (if (contextActive) "UP" else "DOWN"),
            ),
            "build" to buildInfo(),
            "runtime" to runtimeInfo(),
            "llm" to llmInfo(),
            "sessions" to sessionsInfo(),
            "pulsarSessions" to pulsarSessionsInfo(),
            "swarm" to swarmInfo(),
            "urlPool" to urlPoolInfo(),
            "browsers" to browsersInfo(),
            "drivers" to mapOf("report" to driverPoolManager.buildStatusString(verbose = true)),
            "privacy" to mapOf("report" to privacyManager.buildStatusString()),
            "plugins" to pluginsInfo(),
            "skills" to skillsInfo(),
            "metrics" to metricsSummary(),
            "logs" to logFilesInfo(),
        )
    }

    private fun buildInfo(): Map<String, Any?> {
        return mapOf(
            "version" to readVersion(),
            "gitCommitId" to gitProperties?.commitId,
            "gitCommitIdAbbrev" to gitProperties?.shortCommitId,
            "gitBranch" to gitProperties?.branch,
            "gitCommitTime" to gitProperties?.commitTime?.toString(),
        )
    }

    private fun runtimeInfo(): Map<String, Any?> {
        val runtime = Runtime.getRuntime()
        val osMx = ManagementFactory.getOperatingSystemMXBean()
        val threadMx = ManagementFactory.getThreadMXBean()
        val memoryMx = ManagementFactory.getMemoryMXBean()
        val usedHeap = runtime.totalMemory() - runtime.freeMemory()
        return mapOf(
            "uptimeSeconds" to ManagementFactory.getRuntimeMXBean().uptime / 1000,
            "processors" to runtime.availableProcessors(),
            "systemLoadAverage" to osMx.systemLoadAverage,
            "threadCount" to threadMx.threadCount,
            "memory" to mapOf(
                "heapUsed" to usedHeap,
                "heapCommitted" to runtime.totalMemory(),
                "heapMax" to runtime.maxMemory(),
                "heapUsedPercent" to if (runtime.maxMemory() > 0) usedHeap * 100 / runtime.maxMemory() else 0,
                "nonHeapUsed" to memoryMx.nonHeapMemoryUsage.used,
            ),
        )
    }

    private fun llmInfo(): Map<String, Any?> {
        val envVars = LLM_ENV_VARS.filter { System.getenv(it) != null }
        val properties = LLM_PROPERTIES.filter { System.getProperty(it) != null }

        // Primary check: same path used by DoctorController and the standalone app —
        // reads the Pulsar SDK ImmutableConfig loaded from ~/.browser4/config/conf-enabled/.
        val factoryConfigured = try {
            ChatModelFactory.isModelConfigured(agenticContext.configuration, verbose = false)
        } catch (e: Exception) {
            logger.warn("ChatModelFactory.isModelConfigured threw: {}", e.message)
            null
        }

        val detectedVia = when {
            factoryConfigured == true -> "config_file"
            envVars.isNotEmpty() || properties.isNotEmpty() -> "env_or_property"
            else -> null
        }
        val configured = detectedVia != null

        return mapOf(
            "configured" to configured,
            "detectedVia" to detectedVia,
            "foundEnvVars" to envVars,
            "foundProperties" to properties,
            "message" to if (configured) {
                null
            } else {
                "LLM is not configured, you can only use non-LLM commands. " +
                    "Set OPENROUTER_API_KEY or other LLM keys to enable LLM features."
            },
        )
    }

    private fun sessionsInfo(): Map<String, Any?> {
        val items = sessionManager.getAllSessions().map { s ->
            mapOf(
                "id" to s.sessionId,
                "status" to s.status.wire,
                "kind" to s.kind.name,
                "url" to s.url,
                "active" to s.agenticSession.isActive,
                "capabilities" to (s.capabilities ?: emptyMap<String, String?>()),
                "createdAt" to s.createdAt,
                "lastAccessedAt" to s.lastAccessedAt,
            )
        }
        val byStatus = items.groupingBy { it["status"] as String }.eachCount()
        return mapOf(
            "total" to items.size,
            "byStatus" to byStatus,
            "items" to items,
        )
    }

    /**
     * Underlying SDK [PulsarSession] state per managed session — identity
     * (id/uuid/label/display), liveness, owning context, and main loop status.
     * All in-memory, no CDP.
     */
    private fun pulsarSessionsInfo(): Map<String, Any?> {
        val items = sessionManager.getAllSessions().map { s ->
            try {
                val ps = s.agenticSession
                val ctx = ps.context
                val taskLoops = ctx.taskLoops
                mapOf(
                    "managedSessionId" to s.sessionId,
                    "id" to ps.id,
                    "uuid" to ps.uuid,
                    "label" to ps.label,
                    "display" to ps.display,
                    "isActive" to ps.isActive,
                    "contextId" to ctx.id,
                    "contextActive" to ctx.isActive,
                    "mainLoopRunning" to taskLoops?.isRunning,
                    "mainLoopReport" to taskLoops?.loops?.firstOrNull()?.report,
                )
            } catch (e: Exception) {
                mapOf("managedSessionId" to s.sessionId, "error" to (e.message ?: "query failed"))
            }
        }
        return mapOf("total" to items.size, "items" to items)
    }

    /**
     * SWARM session report — the shared swarm session (kind [SessionKind.SWARM])
     * plus a summary of tracked swarm tasks.
     */
    private fun swarmInfo(): Map<String, Any?> {
        val swarmSession = sessionManager.getAllSessions().firstOrNull { it.kind == SessionKind.SWARM }
        val taskSummary = try {
            scrapeService.summary()
        } catch (e: Exception) {
            null
        }
        return mapOf(
            "session" to swarmSession?.let { s ->
                mapOf(
                    "id" to s.sessionId,
                    "status" to s.status.wire,
                    "active" to s.agenticSession.isActive,
                    "url" to s.url,
                    "createdAt" to s.createdAt,
                    "lastAccessedAt" to s.lastAccessedAt,
                )
            },
            "tasks" to taskSummary,
        )
    }

    /**
     * URL pool state of the active context — total queued URLs, real-time and
     * delay queues, and per-priority cache sizes. All in-memory, no CDP.
     */
    private fun urlPoolInfo(): Map<String, Any?> {
        return try {
            val ctx = session.context
            val urlPool = ctx.globalCache.urlPool
            mapOf(
                "contextId" to ctx.id,
                "contextActive" to ctx.isActive,
                "id" to urlPool.id,
                "totalCount" to urlPool.totalCount,
                "realTime" to urlPool.realTimeCache.size,
                "delay" to urlPool.delayCache.size,
                "caches" to urlPool.orderedCaches.mapValues { (_, cache) ->
                    mapOf("name" to cache.name, "size" to cache.size)
                },
            )
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: "query failed"))
        }
    }

    /**
     * Lightweight browser report — pure in-memory metadata, no CDP round trips.
     * Per session: whether a browser and a driver are bound, and the number of
     * open tabs (from the browser's in-memory driver registry).
     */
    private fun browsersInfo(): Map<String, Any?> {
        val items = sessionManager.getAllSessions().map { s ->
            val browser = s.agenticSession.boundBrowser
            val tabCount = try {
                browser?.drivers?.size
            } catch (e: Exception) {
                null
            }
            mapOf(
                "sessionId" to s.sessionId,
                "sessionStatus" to s.status.wire,
                "active" to s.agenticSession.isActive,
                "url" to s.url,
                "hasBrowser" to (browser != null),
                "hasDriver" to (s.agenticSession.boundDriver != null),
                "tabCount" to tabCount,
            )
        }
        return mapOf(
            "total" to items.count { it["hasBrowser"] == true },
            "tabTotal" to items.sumOf { (it["tabCount"] as? Int) ?: 0 },
            "items" to items,
        )
    }

    /**
     * On-demand tab report. Queries live tab details (guid, title, url, active)
     * for every session with a bound browser — or only for [sessionId] when
     * given. These are CDP round trips, so this endpoint is intentionally NOT
     * part of the auto-refreshing status payload; call it when the panel needs
     * tab-level detail. Each session query is bounded by [TABS_QUERY_TIMEOUT_MS]
     * so a busy session never blocks the report.
     */
    @GetMapping("tabs")
    suspend fun tabs(@RequestParam(required = false) sessionId: String?): Map<String, Any?> {
        val sessions = if (sessionId.isNullOrBlank()) {
            sessionManager.getAllSessions()
        } else {
            listOfNotNull(sessionManager.getSession(sessionId))
        }
        val items = sessions.mapNotNull { s -> sessionTabs(s) }
        return mapOf(
            "total" to items.size,
            "tabTotal" to items.sumOf { (it["tabCount"] as? Int) ?: 0 },
            "items" to items,
        )
    }

    private suspend fun sessionTabs(s: ManagedSession): Map<String, Any?>? {
        val browser = s.agenticSession.boundBrowser ?: return null
        return withTimeoutOrNull(TABS_QUERY_TIMEOUT_MS) {
            val frontGuid = (browser.frontDriver as? AbstractWebDriver)?.guid
            val tabs = mutableListOf<Map<String, Any?>>()
            try {
                val drivers = browser.listDrivers().filterIsInstance<AbstractWebDriver>()
                for ((index, wd) in drivers.withIndex()) {
                    try {
                        tabs += mapOf(
                            "index" to index,
                            "guid" to wd.guid,
                            "title" to wd.title(),
                            "url" to wd.currentUrl(),
                            "active" to (wd.guid == frontGuid),
                        )
                    } catch (e: Exception) {
                        tabs += mapOf(
                            "index" to index,
                            "guid" to wd.guid,
                            "title" to "<unavailable>",
                            "url" to "",
                            "active" to false,
                            "error" to (e.message ?: "query failed"),
                        )
                    }
                }
                mapOf("sessionId" to s.sessionId, "status" to s.status.wire, "tabCount" to tabs.size, "tabs" to tabs)
            } catch (e: Exception) {
                mapOf(
                    "sessionId" to s.sessionId,
                    "status" to s.status.wire,
                    "error" to (e.message ?: "query failed"),
                    "tabCount" to 0,
                    "tabs" to emptyList<Any>(),
                )
            }
        }
    }

    private fun metricsSummary(): Map<String, Any?> {
        val metrics = MetricsSystem.reg.metrics
        return mapOf(
            "total" to metrics.size,
            "gauges" to metrics.values.count { it is Gauge<*> },
            "counters" to metrics.values.count { it is Counter },
            "meters" to metrics.values.count { it is Meter },
            "histograms" to metrics.values.count { it is Histogram },
            "timers" to metrics.values.count { it is Timer },
        )
    }

    private fun logFilesInfo(): Map<String, Any?> {
        val logDir = File(loggingDir)
        val files = if (logDir.isDirectory) {
            logDir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?.take(MAX_LOG_FILES)
                ?.map { f ->
                    mapOf(
                        "name" to f.name,
                        "size" to f.length(),
                        "sizeHuman" to formatFileSize(f.length()),
                        "lastModified" to f.lastModified(),
                    )
                }
                ?: emptyList()
        } else {
            emptyList()
        }
        return mapOf(
            "directory" to logDir.absolutePath,
            "exists" to logDir.isDirectory,
            "count" to files.size,
            "files" to files,
        )
    }

    private fun pluginsInfo(): Map<String, Any?> {
        val plugins = pluginService.listPlugins()
        val items = plugins.map { p ->
            val compatibility = p.manifest?.let { PluginCompatibility.check(it) }
            mapOf(
                "fileName" to p.fileName,
                "name" to p.manifest?.name,
                "version" to p.manifest?.version,
                "sdkVersion" to p.manifest?.sdkVersion,
                "description" to p.manifest?.description,
                "loaded" to p.loaded,
                "enabled" to p.enabled,
                "defaultEnabled" to p.defaultEnabled,
                "fileSize" to p.fileSize,
                "fileSizeHuman" to formatFileSize(p.fileSize),
                "compatibility" to compatibility?.let { c ->
                    mapOf(
                        "verdict" to when (c) {
                            is PluginCompatibility.Compatible -> "compatible"
                            is PluginCompatibility.Warn -> "warn"
                            is PluginCompatibility.Blocked -> "blocked"
                        },
                        "reason" to when (c) {
                            is PluginCompatibility.Compatible -> null
                            is PluginCompatibility.Warn -> c.reason
                            is PluginCompatibility.Blocked -> c.reason
                        },
                    )
                },
            )
        }
        val verdictOf: (Map<String, Any?>) -> String? = { item ->
            (item["compatibility"] as? Map<*, *>)?.get("verdict") as? String
        }
        return mapOf(
            "directory" to plugins.firstOrNull()?.path?.let { Path.of(it).parent?.toString() },
            "total" to items.size,
            "loaded" to items.count { it["loaded"] == true },
            "enabled" to items.count { it["enabled"] == true },
            "warnings" to items.count { verdictOf(it) == "warn" },
            "blocked" to items.count { verdictOf(it) == "blocked" },
            "items" to items,
        )
    }

    /**
     * Registered skill report — a summary layer (total + origin distribution:
     * classpath / filesystem / programmatic) plus one item per registered skill
     * (id, name, version, description, tags, origin). All in-memory registry
     * reads, no file I/O, so it is safe for the auto-refreshing status panel.
     */
    private fun skillsInfo(): Map<String, Any?> {
        return try {
            val registry = SkillRegistry.instance
            val originBySkillId = registry.getAll().associate { skill ->
                skill.metadata.id to (skill as? DefinitionBackedSkill)?.origin
            }
            val items = skillService.listSkills().map { s ->
                val origin = originBySkillId[s.id]
                // Build the origin string explicitly: the data-class subclasses
                // of Origin override toString(), so `origin?.toString()` would
                // yield `Classpath(resourceBase=...)` instead of a readable path.
                val (originKind, originString) = when (origin) {
                    is DefinitionBackedSkill.Origin.Classpath ->
                        "classpath" to "classpath:${origin.resourceBase}"
                    is DefinitionBackedSkill.Origin.FileSystem ->
                        "filesystem" to "filesystem:${origin.directory}"
                    null -> "programmatic" to null
                }
                mapOf(
                    "id" to s.id,
                    "name" to s.name,
                    "version" to s.version,
                    "description" to s.description,
                    "tags" to s.tags.toList(),
                    "originKind" to originKind,
                    "origin" to originString,
                )
            }
            mapOf(
                "total" to items.size,
                "byOrigin" to items.groupingBy { it["originKind"] as String }.eachCount(),
                "items" to items,
            )
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: "query failed"))
        }
    }

    private fun readVersion(): String? {
        return try {
            val properties = Properties()
            val resource = Thread.currentThread().contextClassLoader
                .getResourceAsStream("META-INF/maven/ai.platon.pulsar/browser4-rest/pom.properties")
            resource?.use { properties.load(it) }
            properties.getProperty("version")
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private val LLM_ENV_VARS = listOf(
            "OPENROUTER_API_KEY",
            "DEEPSEEK_API_KEY",
            "VOLCENGINE_API_KEY",
            "OPENAI_API_KEY",
            "LLM_API_KEY",
        )
        private val LLM_PROPERTIES = listOf(
            "llm.api.key",
            "openrouter.api.key",
            "volcengine.api.key",
            "deepseek.api.key",
            "openai.api.key",
        )
        private const val MAX_LOG_FILES = 20
        private const val TABS_QUERY_TIMEOUT_MS = 3000L

        private fun formatFileSize(bytes: Long): String {
            val units = arrayOf("B", "KB", "MB", "GB")
            var size = bytes.toDouble()
            var unitIndex = 0
            while (size >= 1024 && unitIndex < units.size - 1) {
                size /= 1024
                unitIndex++
            }
            return "%.1f %s".format(size, units[unitIndex])
        }
    }
}
