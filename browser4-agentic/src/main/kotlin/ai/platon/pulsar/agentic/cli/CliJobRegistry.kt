package ai.platon.pulsar.agentic.cli

import ai.platon.pulsar.common.getLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * Registry of long-running CLI jobs (design §4.1.1). A foreground command that
 * exceeds the yield window becomes a job: [start] returns a job id, [status] /
 * [await] / [kill] manage it, and [close] tears everything down fail-loud.
 */
class CliJobRegistry(
    private val manager: CliProcessManager,
    private val scope: CoroutineScope,
) {
    private val logger = getLogger(this)

    enum class JobState { RUNNING, COMPLETED, FAILED, CANCELLED }

    data class CliJob(
        val id: String,
        val request: CliRunRequest,
        val state: JobState,
        val startedAt: Instant,
        val finishedAt: Instant? = null,
        val result: CliResult? = null,
    )

    private data class JobMeta(
        var state: JobState,
        val request: CliRunRequest,
        val startedAt: Instant,
        val cancelToken: Job,
        var finishedAt: Instant? = null,
        var result: CliResult? = null,
    )

    private val jobs = ConcurrentHashMap<String, Deferred<CliResult>>()
    private val meta = ConcurrentHashMap<String, JobMeta>()

    /** Start a CLI job in the background; returns its job id. */
    fun start(request: CliRunRequest, backendBaseUrl: String? = null): String {
        val id = UUID.randomUUID().toString()
        val cancelToken = Job()
        val m = JobMeta(JobState.RUNNING, request, Instant.now(), cancelToken)
        meta[id] = m
        jobs[id] = scope.async {
            // Cancellation goes through the manager's own token so the process
            // tree is killed and an aborted CliResult is produced (not a thrown
            // CancellationException that would strand the meta in RUNNING).
            val result = manager.run(request, backendBaseUrl, cancelToken = cancelToken)
            m.state = when {
                result.aborted -> JobState.CANCELLED
                result.isSuccess -> JobState.COMPLETED
                else -> JobState.FAILED
            }
            m.finishedAt = Instant.now()
            m.result = result
            result
        }
        return id
    }

    fun status(id: String): CliJob? = meta[id]?.let {
        CliJob(id, it.request, it.state, it.startedAt, it.finishedAt, it.result)
    }

    fun kill(id: String): Boolean {
        return meta[id]?.cancelToken?.let { token ->
            if (token.isActive) {
                token.cancel()
                true
            } else {
                false
            }
        } ?: false
    }

    fun list(): List<CliJob> = meta.entries.map { (id, m) ->
        CliJob(id, m.request, m.state, m.startedAt, m.finishedAt, m.result)
    }

    suspend fun await(id: String, timeoutMs: Long): CliResult? =
        withTimeoutOrNull(timeoutMs.milliseconds) { jobs[id]?.await() }

    /**
     * Kill all live jobs, await their teardown, and fail-loud when anything is
     * left running (orphan alert).
     */
    fun close() {
        meta.values.forEach { m -> if (m.cancelToken.isActive) m.cancelToken.cancel() }
        runBlocking {
            withTimeoutOrNull(30_000.milliseconds) {
                jobs.values.forEach { runCatching { it.await() } }
            }
        }
        val stillRunning = jobs.values.count { it.isActive }
        if (stillRunning > 0) {
            logger.error("CliJobRegistry close: {} job(s) still active after teardown — orphan risk", stillRunning)
        }
        jobs.clear()
        meta.clear()
    }
}
