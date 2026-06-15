package ai.platon.browser4.cli

/**
 * Parsed global flags that appear before the command name.
 *
 * Mirrors the Rust [GlobalFlags] struct.
 */
data class GlobalFlags(
    /** `-s` / `--session` requested session identifier */
    val sessionName: String? = null,
    /** `--server=<url>` server override */
    val serverUrl: String? = null,
    /** `--json` — emit machine-parseable JSON to stdout */
    val json: Boolean = false,
    /** `-q` / `--quiet` — suppress normal output */
    val quiet: Boolean = false,
    /** `--proxy=<url>` — manual HTTP proxy for downloads */
    val proxyUrl: String? = null,
    /** Remaining arguments (command + its args/options) */
    val args: List<String> = emptyList(),
)

/**
 * Parses global flags that may appear before the command name.
 *
 * Recognises:
 * - `-s=<name>`, `-s <name>`, `--session=<name>`, `--session <name>` → session name
 * - `--server=<url>` or `--server <url>` → server URL override
 * - `--json` → emit machine-parseable JSON to stdout (before command only)
 * - `-q` / `--quiet` → suppress normal output
 * - `--proxy=<url>` or `--proxy <url>` → manual HTTP proxy override
 * - Everything else is forwarded unchanged in `args`
 */
fun parseGlobalFlags(argv: List<String>): GlobalFlags {
    var sessionName: String? = System.getenv("BROWSER4_CLI_SESSION")?.takeIf { it.isNotBlank() }
    var serverUrl: String? = null
    var json = false
    var quiet = false
    var proxyUrl: String? = null
    val remaining = mutableListOf<String>()
    var seenCommand = false

    var i = 0
    while (i < argv.size) {
        val arg = argv[i]
        when {
            arg.startsWith("-s=") -> sessionName = arg.removePrefix("-s=")
            arg.startsWith("--session=") -> sessionName = arg.removePrefix("--session=")
            arg == "-s" || arg == "--session" -> {
                if (i + 1 < argv.size && !argv[i + 1].startsWith("-")) {
                    i++; sessionName = argv[i]
                }
            }
            !seenCommand && arg == "--json" -> json = true
            !seenCommand && (arg == "-q" || arg == "--quiet") -> quiet = true
            arg.startsWith("--server=") -> serverUrl = arg.removePrefix("--server=")
            arg == "--server" -> {
                if (i + 1 < argv.size && !argv[i + 1].startsWith("-")) {
                    i++; serverUrl = argv[i]
                }
            }
            !seenCommand && arg.startsWith("--proxy=") -> proxyUrl = arg.removePrefix("--proxy=")
            !seenCommand && arg == "--proxy" -> {
                if (i + 1 < argv.size && !argv[i + 1].startsWith("-")) {
                    i++; proxyUrl = argv[i]
                }
            }
            else -> {
                if (!arg.startsWith("-")) seenCommand = true
                remaining.add(arg)
            }
        }
        i++
    }

    return GlobalFlags(sessionName, serverUrl, json, quiet, proxyUrl, remaining)
}

/**
 * Parses raw CLI tokens into a `Map<String, String>`.
 *
 * - Positional args are stored under key `"_"` as comma-separated values.
 * - `--key=value` or `--key value` → `"key" -> "value"`.
 * - `--flag` (boolean) → `"flag" -> "true"`.
 * - Short options are resolved via `shortToLong` mapping.
 */
fun parseRawArgs(
    rawArgs: List<String>,
    shortToLong: Map<String, String> = emptyMap(),
): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val positional = mutableListOf<String>()

    var i = 0
    while (i < rawArgs.size) {
        val arg = rawArgs[i]
        when {
            arg.startsWith("--") -> {
                val rest = arg.removePrefix("--")
                val eqIdx = rest.indexOf('=')
                if (eqIdx >= 0) {
                    result[rest.substring(0, eqIdx)] = rest.substring(eqIdx + 1)
                } else {
                    if (i + 1 < rawArgs.size && !rawArgs[i + 1].startsWith("--")) {
                        i++; result[rest] = rawArgs[i]
                    } else {
                        result[rest] = "true"
                    }
                }
            }
            arg.startsWith("-") -> {
                val rest = arg.removePrefix("-")
                val eqIdx = rest.indexOf('=')
                val shortKey = if (eqIdx >= 0) rest.substring(0, eqIdx) else rest
                val longName = shortToLong[shortKey]
                if (longName != null) {
                    if (eqIdx >= 0) {
                        result[longName] = rest.substring(eqIdx + 1)
                    } else {
                        if (i + 1 < rawArgs.size && !rawArgs[i + 1].startsWith("-")) {
                            i++; result[longName] = rawArgs[i]
                        } else {
                            result[longName] = "true"
                        }
                    }
                } else {
                    positional.add(arg)
                }
            }
            else -> positional.add(arg)
        }
        i++
    }

    result["_"] = positional.joinToString(",")
    return result
}

/**
 * Maps positional arguments to named parameter slots from [argNames].
 *
 * The first positional arg (index 0) is assumed to be the command name and
 * is skipped.  Subsequent positional values are assigned to the corresponding
 * [argNames] entry.
 */
fun buildCommandArgs(raw: Map<String, String>, argNames: List<String>): Map<String, String> {
    val result = raw.toMutableMap()
    val positionalStr = raw["_"] ?: ""
    val positional = if (positionalStr.isNotEmpty()) positionalStr.split(",") else emptyList()

    if (positional.size > argNames.size + 1) {
        throw IllegalArgumentException(
            "Too many arguments: expected ${argNames.size}, got ${positional.size - 1}"
        )
    }

    for ((idx, name) in argNames.withIndex()) {
        val pos = idx + 1 // skip command name at index 0
        if (pos < positional.size) {
            result[name] = positional[pos]
        }
    }

    return result
}
