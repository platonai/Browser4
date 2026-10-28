package ai.platon.pulsar.agentic.tools.builtin

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `b4.run` must obey the workspace boundary while the task may not read outside it.
 *
 * Origin: `coding.*` file tools refused a path outside the workspace, yet the same run
 * reached it through the browser (`goto file:///D:/…`) and `upload` (D4, 2026-10-28).
 */
@DisplayName("b4.run external path guard")
class B4CliExternalPathTest {

    @Test
    @DisplayName("a file:// URL outside the workspace is refused")
    fun fileUrlOutsideIsRefused(@TempDir tmp: Path) {
        val refusal = externalPathRefusal("""goto "file:///D:/secret/notes.txt"""", tmp, allowExternalAccess = false)

        assertNotNull(refusal, "an external file:// read must be refused")
        assertTrue(refusal!!.contains("PATH_OUTSIDE_WORKSPACE"), refusal)
        assertTrue(refusal.contains("b4.run"), refusal)
        assertTrue(refusal.contains("allowExternalAccess"), refusal)
    }

    @Test
    @DisplayName("a file:// URL inside the workspace is allowed")
    fun fileUrlInsideIsAllowed(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("page.html"), "<html></html>")

        val allowed = externalPathRefusal(
            "goto \"file:///${tmp.resolve("page.html").toString().replace('\\', '/')}\"",
            tmp,
            allowExternalAccess = false,
        )

        assertNull(allowed)
    }

    @Test
    @DisplayName("uploading an absolute path outside the workspace is refused")
    fun uploadOutsideIsRefused(@TempDir tmp: Path) {
        val refusal = externalPathRefusal(
            """upload "#file" "C:/Windows/win.ini"""", tmp, allowExternalAccess = false,
        )

        assertNotNull(refusal, "an external upload must be refused")
        assertTrue(refusal!!.contains("PATH_OUTSIDE_WORKSPACE"), refusal)
    }

    @Test
    @DisplayName("a relative upload path stays allowed")
    fun uploadRelativeIsAllowed(@TempDir tmp: Path) {
        assertNull(externalPathRefusal("""upload "#file" "data/report.csv"""", tmp, allowExternalAccess = false))
    }

    @Test
    @DisplayName("nothing is refused when external access is enabled")
    fun externalAccessOptOut(@TempDir tmp: Path) {
        assertNull(externalPathRefusal("""goto "file:///D:/secret/notes.txt"""", tmp, allowExternalAccess = true))
    }
}
