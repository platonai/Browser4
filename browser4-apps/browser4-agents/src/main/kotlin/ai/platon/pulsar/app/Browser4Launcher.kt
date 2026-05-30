package ai.platon.pulsar.app

import ai.platon.pulsar.agentic.mcp.server.runBrowser4MCPServer

/**
 * Unified Browser4 entrypoint for the packaged executable jar.
 *
 * Use `--app=agents` to start the Spring Boot agents application, or
 * `--app=mcp` to start the MCP server over STDIO. When omitted, Browser4 keeps
 * the existing agents default for backward compatibility.
 */
object Browser4Launcher {
    private const val APP_OPTION = "--app"

    fun main(args: Array<String>) {
        val request = parseLaunchRequest(args)

        when (request.app) {
            Browser4App.AGENTS -> runBrowser4AgentsApplication(request.remainingArgs)
            Browser4App.MCP -> runBrowser4MCPServer(request.remainingArgs)
        }
    }

    fun parseLaunchRequest(args: Array<String>): Browser4LaunchRequest {
        var app = Browser4App.AGENTS
        var appSpecified = false
        val remainingArgs = mutableListOf<String>()

        var index = 0
        while (index < args.size) {
            val arg = args[index]
            when {
                arg.startsWith("$APP_OPTION=") -> {
                    require(!appSpecified) {
                        "Duplicate $APP_OPTION option. Use $APP_OPTION=agents or $APP_OPTION=mcp."
                    }
                    app = parseAppName(arg.substringAfter('='))
                    appSpecified = true
                }
                arg == APP_OPTION -> {
                    require(!appSpecified) {
                        "Duplicate $APP_OPTION option. Use $APP_OPTION=agents or $APP_OPTION=mcp."
                    }
                    require(index + 1 < args.size) {
                        "Missing value after $APP_OPTION. Use $APP_OPTION agents or $APP_OPTION mcp."
                    }
                    app = parseAppName(args[++index])
                    appSpecified = true
                }
                else -> remainingArgs += arg
            }
            index++
        }

        return Browser4LaunchRequest(app, remainingArgs.toTypedArray())
    }

    private fun parseAppName(value: String): Browser4App =
        when (value.lowercase()) {
            "agents" -> Browser4App.AGENTS
            "mcp" -> Browser4App.MCP
            else -> throw IllegalArgumentException(
                "Unsupported Browser4 app '$value'. Use $APP_OPTION=agents or $APP_OPTION=mcp."
            )
        }
}

enum class Browser4App {
    AGENTS,
    MCP,
}

data class Browser4LaunchRequest(
    val app: Browser4App,
    val remainingArgs: Array<String>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Browser4LaunchRequest

        if (app != other.app) return false
        if (!remainingArgs.contentEquals(other.remainingArgs)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = app.hashCode()
        result = 31 * result + remainingArgs.contentHashCode()
        return result
    }
}

fun main(args: Array<String>) = Browser4Launcher.main(args)
