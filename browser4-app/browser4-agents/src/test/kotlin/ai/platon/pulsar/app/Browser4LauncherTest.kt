package ai.platon.pulsar.app

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class Browser4LauncherTest {
    @Test
    fun defaultsToAgentsMode() {
        val request = Browser4Launcher.parseLaunchRequest(emptyArray())

        assertEquals(Browser4App.AGENTS, request.app)
        assertArrayEquals(emptyArray(), request.remainingArgs)
    }

    @Test
    fun parsesInlineAppOptionAndPassesRemainingArgs() {
        val request = Browser4Launcher.parseLaunchRequest(
            arrayOf("--app=mcp", "--server.port=9090")
        )

        assertEquals(Browser4App.MCP, request.app)
        assertArrayEquals(arrayOf("--server.port=9090"), request.remainingArgs)
    }

    @Test
    fun parsesSeparateAppOptionValue() {
        val request = Browser4Launcher.parseLaunchRequest(
            arrayOf("--app", "agents", "--spring.profiles.active=demo")
        )

        assertEquals(Browser4App.AGENTS, request.app)
        assertArrayEquals(arrayOf("--spring.profiles.active=demo"), request.remainingArgs)
    }

    @Test
    fun rejectsUnknownAppOption() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            Browser4Launcher.parseLaunchRequest(arrayOf("--app=unknown"))
        }

        assertEquals(
            "Unsupported Browser4 app 'unknown'. Use --app=agents or --app=mcp.",
            error.message
        )
    }
}
