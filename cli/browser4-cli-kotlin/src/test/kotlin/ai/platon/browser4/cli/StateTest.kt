package ai.platon.browser4.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class StateTest {

    @TempDir
    lateinit var tempDir: Path

    // ---- resolveRef ----

    @Test
    fun `resolveRef converts e15 to backend 15`() {
        assertEquals("backend:15", CliStateManager.resolveRef("e15"))
    }

    @Test
    fun `resolveRef converts E42 case-insensitively`() {
        assertEquals("backend:42", CliStateManager.resolveRef("E42"))
    }

    @Test
    fun `resolveRef passes through already-normalised selector`() {
        assertEquals("backend:99", CliStateManager.resolveRef("backend:99"))
    }

    @Test
    fun `resolveRef passes through CSS selector`() {
        assertEquals("#search-input", CliStateManager.resolveRef("#search-input"))
    }

    // ---- State round-trip ----

    @Test
    fun `readState returns defaults when file does not exist`() {
        val state = CliStateManager.readState(stateDir = tempDir)
        assertNull(state.sessionId)
        assertEquals(CliState.DEFAULT_BASE_URL, state.baseUrl)
    }

    @Test
    fun `write then read returns same state`() {
        val original = CliState(
            sessionId = "sess-123",
            baseUrl = "http://localhost:9999",
            activeSelector = "backend:5",
            sessionName = "test",
            lastMousePosition = MousePosition(100.0, 200.0),
        )
        CliStateManager.writeState(original, stateDir = tempDir)
        val restored = CliStateManager.readState(stateDir = tempDir)
        assertEquals(original, restored)
    }

    @Test
    fun `named sessions are isolated from default session`() {
        val default = CliState(sessionId = "default-sess")
        val named = CliState(sessionId = "named-sess", sessionName = "mysession")

        CliStateManager.writeState(default, stateDir = tempDir)
        CliStateManager.writeState(named, stateDir = tempDir, sessionName = "mysession")

        val restoredDefault = CliStateManager.readState(stateDir = tempDir)
        val restoredNamed = CliStateManager.readState(stateDir = tempDir, sessionName = "mysession")

        assertEquals("default-sess", restoredDefault.sessionId)
        assertEquals("named-sess", restoredNamed.sessionId)
    }

    @Test
    fun `clearState removes state file`() {
        val state = CliState(sessionId = "sess-clear")
        CliStateManager.writeState(state, stateDir = tempDir)
        CliStateManager.clearState(stateDir = tempDir)

        val restored = CliStateManager.readState(stateDir = tempDir)
        assertNull(restored.sessionId)
    }

    @Test
    fun `mouse position round-trips correctly`() {
        val state = CliState(
            sessionId = "sess-mouse",
            lastMousePosition = MousePosition(1920.0, 1080.0),
        )
        CliStateManager.writeState(state, stateDir = tempDir)
        val restored = CliStateManager.readState(stateDir = tempDir)

        assertNotNull(restored.lastMousePosition)
        assertEquals(1920.0, restored.lastMousePosition!!.x)
        assertEquals(1080.0, restored.lastMousePosition!!.y)
    }

    @Test
    fun `default state dir uses user home`() {
        val dir = CliStateManager.resolveDefaultStateDir()
        assertTrue(dir.endsWith(".browser4"))
    }
}
