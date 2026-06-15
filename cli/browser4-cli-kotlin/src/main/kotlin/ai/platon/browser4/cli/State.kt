package ai.platon.browser4.cli

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path

/**
 * Persisted mouse position for restoring cursor state across CLI invocations.
 */
data class MousePosition(
    val x: Double,
    val y: Double,
)

/**
 * CLI session state persisted between invocations under `~/.browser4`.
 *
 * The default session uses `~/.browser4/cli-state.json`; named sessions use
 * `~/.browser4/sessions/<name>.json`.
 */
data class CliState(
    @JsonProperty("sessionId") val sessionId: String? = null,
    @JsonProperty("baseUrl") val baseUrl: String = DEFAULT_BASE_URL,
    @JsonProperty("activeSelector") val activeSelector: String? = null,
    @JsonProperty("sessionName") val sessionName: String? = null,
    @JsonProperty("lastMousePosition") val lastMousePosition: MousePosition? = null,
) {
    companion object {
        const val DEFAULT_BASE_URL = "http://localhost:8182"
    }
}

/**
 * Manages CLI session state persistence on disk.
 *
 * Mirrors the Rust [state.rs] module.
 */
object CliStateManager {

    private val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        configure(SerializationFeature.INDENT_OUTPUT, true)
        registerModule(JavaTimeModule())
    }

    /** Returns the state directory (`~/.browser4` by default). */
    fun resolveDefaultStateDir(): Path {
        val override = System.getenv("BROWSER4_CLI_STATE_DIR")
        if (!override.isNullOrBlank()) return Path.of(override)
        return Path.of(System.getProperty("user.home"), ".browser4")
    }

    /** Reads CLI state for the given session, falling back to defaults. */
    fun readState(stateDir: Path? = null, sessionName: String? = null): CliState {
        val dir = stateDir ?: resolveDefaultStateDir()
        val file = stateFile(dir, sessionName)
        return try {
            mapper.readValue(file.toFile())
        } catch (_: Exception) {
            CliState()
        }
    }

    /** Writes CLI state to disk, creating parent directories as needed. */
    fun writeState(state: CliState, stateDir: Path? = null, sessionName: String? = null) {
        val dir = stateDir ?: resolveDefaultStateDir()
        val file = stateFile(dir, sessionName)
        file.parent?.toFile()?.mkdirs()
        mapper.writeValue(file.toFile(), state)
    }

    /** Removes the persisted state file for the given session. */
    fun clearState(stateDir: Path? = null, sessionName: String? = null) {
        val dir = stateDir ?: resolveDefaultStateDir()
        Files.deleteIfExists(stateFile(dir, sessionName))
    }

    /**
     * Converts `e<N>` element shorthand to `backend:<N>` format expected by
     * the server.  Passes through any already-normalised selector unchanged.
     */
    fun resolveRef(rawRef: String): String {
        val trimmed = rawRef.trim()
        val match = Regex("(?i)^e(\\d+)\$").find(trimmed)
        return if (match != null) "backend:${match.groupValues[1]}" else trimmed
    }

    // ---- private ----

    private fun stateFile(dir: Path, sessionName: String?): Path =
        if (sessionName != null) dir.resolve("sessions").resolve("$sessionName.json")
        else dir.resolve("cli-state.json")
}
