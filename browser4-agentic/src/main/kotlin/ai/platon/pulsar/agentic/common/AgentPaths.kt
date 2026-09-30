package ai.platon.pulsar.agentic.common

import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.B4ProjectUtils
import ai.platon.pulsar.common.DateTimes
import ai.platon.pulsar.common.RequiredDirectory
import ai.platon.pulsar.common.createRequiredResources
import ai.platon.pulsar.common.getLogger
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Agent-owned directory layout under the application data directory
 * ([AppContext.APP_DATA_DIR], typically `~/.browser4`).
 *
 * Trace/run logs live under `AGENT_TRACE_DIR` (`<data dir>/logs/agent`), NOT
 * under the logback `logs` directory: the data directory is the durable home
 * for non-logback auxiliary logs. In development (when
 * [B4ProjectUtils.findProjectRootDir] resolves a project root), a symbolic
 * link `<project root>/logs/agent -> AGENT_TRACE_DIR` is created so traces
 * stay visible under `./logs` while developing.
 */
object AgentPaths {

    @RequiredDirectory
    val AGENT_BASE_DIR: Path = AppContext.APP_DATA_DIR.resolve("agent")

    @RequiredDirectory
    val SKILLS_DIR: Path = AGENT_BASE_DIR.resolve("skills")

    /** Durable home of agent trace/run logs (cli-prompt, tool trace, events...). */
    @RequiredDirectory
    val AGENT_TRACE_DIR: Path = AppContext.APP_DATA_DIR.resolve("logs").resolve("agent")

    private val logger = getLogger(AgentPaths::class)

    /** One-shot guard so the dev symlink is attempted at most once per JVM. */
    private val traceLinkAttempted = AtomicBoolean(false)

    init {
        createRequiredResources(AgentPaths::class)
    }

    fun resolveTimedDirectory(time: Instant): Path {
        val path = AGENT_BASE_DIR
            .resolve(DateTimes.PATH_SAFE_YEAR.format(time))
            .resolve(DateTimes.PATH_SAFE_MONTH.format(time))
            .resolve(DateTimes.PATH_SAFE_DAY.format(time))

        Files.createDirectories(path)

        return path
    }

    /**
     * Resolve one agent run's trace directory: `<data dir>/logs/agent/<time>/<uuid>`.
     * Keeps the same layout the CLI loop tracer historically used under
     * `./logs/agent`, so development paths (`./logs/agent/...`) do not change.
     */
    fun resolveTraceRunDir(time: Instant, uuid: UUID): Path {
        val path = AGENT_TRACE_DIR.resolve(AppPaths.fromTime(time)).resolve(uuid.toString())
        Files.createDirectories(path)
        // Best-effort dev convenience link; safe to call repeatedly (attempted once).
        linkTraceDirToProjectLogs()
        return path
    }

    /**
     * In development, link `<project root>/logs/agent` to [AGENT_TRACE_DIR] so
     * traces written under the data directory stay visible in `./logs`.
     *
     * No-op (returns null) when:
     * - no project root is resolvable (production packaging), or
     * - a `logs/agent` entry already exists (real directory or previous link), or
     * - the platform refuses symbolic links (e.g. Windows without Developer Mode) —
     *   the failure is logged and the durable data directory remains authoritative.
     */
    fun linkTraceDirToProjectLogs(): Path? {
        val projectRoot = B4ProjectUtils.findProjectRootDir() ?: return null
        if (!traceLinkAttempted.compareAndSet(false, true)) return null
        return createTraceLinkIfPossible(projectRoot, AGENT_TRACE_DIR)
    }

    /**
     * Pure link creation, separated for testing: creates
     * `projectRoot/logs/agent -> traceDir` when the target entry is absent.
     *
     * @param projectRoot project root whose `logs` subdirectory hosts the link.
     * @param traceDir the durable trace directory the link points to.
     * @return the created link path, or null when nothing was created.
     */
    fun createTraceLinkIfPossible(projectRoot: Path, traceDir: Path): Path? {
        val link = projectRoot.resolve("logs").resolve("agent")
        return try {
            Files.createDirectories(traceDir)
            Files.createDirectories(link.parent)
            val alreadyThere = Files.exists(link, LinkOption.NOFOLLOW_LINKS)
            if (alreadyThere) {
                logger.debug("Agent trace link already exists: {}", link)
                null
            } else {
                Files.createSymbolicLink(link, traceDir)
                logger.info("Linked agent trace dir for development: {} -> {}", link, traceDir)
                link
            }
        } catch (e: Exception) {
            logger.warn("Failed to link agent trace dir {} -> {}: {}", link, traceDir, e.message)
            null
        }
    }
}
