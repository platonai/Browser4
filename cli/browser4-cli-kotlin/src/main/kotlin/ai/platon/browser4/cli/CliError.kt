package ai.platon.browser4.cli

/**
 * Normalised exit codes reported by every command path.
 */
enum class ExitCode(val code: Int) {
    /** Command completed successfully. */
    Success(0),
    /** Catch-all for unexpected internal errors. */
    General(1),
    /** Invalid arguments, unknown command, bad URL, missing required args. */
    Usage(2),
    /** No active session, session expired, session conflict. */
    Session(3),
    /** Server unreachable, health-check timeout, daemon startup failure. */
    Server(4),
    /** One or more commands in a batch failed (processing itself succeeded). */
    BatchPartial(5),
}

/**
 * Normalised error type that pairs a machine-readable exit code with a
 * human-readable message.
 */
class CliError(val code: ExitCode, override val message: String) : RuntimeException(message) {
    override fun toString(): String = "CliError(code=$code, message='$message')"
}
