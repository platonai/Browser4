package ai.platon.pulsar.rest.mcp.controller

import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.rest.session.ManagedSession
import ai.platon.pulsar.rest.session.PulsarSessionManager
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.reflect.KClass

/**
 * The private dispatcher and the standard MCP server share this resolution, so a
 * custom-domain tool cannot be given one receiver through `/mcp/call-tool` and a
 * different one through `POST /mcp`.
 */
@DisplayName("Custom tool target resolution")
class CustomToolTargetsTest {

    private fun executor(receiverClass: KClass<*>): ToolExecutor {
        val executor = mock<ToolExecutor>()
        whenever(executor.receiverClass).thenReturn(receiverClass)
        return executor
    }

    @Test
    @DisplayName("a WebDriver-receiver executor gets the addressed session's driver")
    fun webDriverReceiverGetsSessionDriver() {
        val sessionManager = mock<PulsarSessionManager>()
        val managed = mock<ManagedSession>()
        val driver = mock<WebDriver>()
        whenever(sessionManager.getOrRecoverSession("s1")).thenReturn(managed)
        whenever(managed.driver).thenReturn(driver)

        val target = CustomToolTargets(sessionManager).resolve(executor(WebDriver::class), "s1")

        assertSame(driver, target)
    }

    @Test
    @DisplayName("a PulsarSessionManager-receiver executor gets the ManagedSession")
    fun sessionManagerReceiverGetsManagedSession() {
        val sessionManager = mock<PulsarSessionManager>()
        val managed = mock<ManagedSession>()
        whenever(sessionManager.getOrRecoverSession("s1")).thenReturn(managed)

        val target = CustomToolTargets(sessionManager).resolve(executor(PulsarSessionManager::class), "s1")

        assertSame(managed, target)
    }

    @Test
    @DisplayName("a service-backed executor gets the Spring bean of its receiverClass")
    fun serviceReceiverGetsBean() {
        val sessionManager = mock<PulsarSessionManager>()
        val service = Any()
        val targets = CustomToolTargets(sessionManager) { type ->
            if (type == Any::class.java) service else null
        }

        assertSame(service, targets.resolve(executor(Any::class), null))
    }

    @Test
    @DisplayName("an unknown session yields a placeholder instead of failing")
    fun unknownSessionYieldsPlaceholder() {
        val sessionManager = mock<PulsarSessionManager>()
        whenever(sessionManager.getOrRecoverSession("missing")).thenReturn(null)

        val target = CustomToolTargets(sessionManager).resolve(executor(PulsarSessionManager::class), "missing")

        // A placeholder keeps the `receiver: Any` contract for executors that
        // ignore the receiver; the tool itself still reports the real error.
        assertNull(target as? ManagedSession)
    }
}
