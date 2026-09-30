package ai.platon.pulsar.coding

/**
 * Shell command execution result containing exit code, stdout, stderr, and metadata.
 */
data class ShellResult(
    val sessionId: String,
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val timedOut: Boolean = false,
) {
    val success: Boolean get() = exitCode == 0 && !timedOut

    override fun toString(): String {
        val status = if (success) "SUCCESS" else "FAILED(exitCode=$exitCode, timedOut=$timedOut)"
        return buildString {
            appendLine("[$status] command='$command' (${durationMs}ms)")
            if (stdout.isNotBlank()) appendLine("stdout:\n$stdout")
            if (stderr.isNotBlank()) appendLine("stderr:\n$stderr")
        }.trimEnd()
    }
}
