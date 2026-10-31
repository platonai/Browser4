package ai.platon.pulsar.agentic.tools.builtin

import org.junit.jupiter.api.Assertions.assertFalse
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

    /**
     * An absolute path outside [workspace], spelled for the host: `C:/…` is a *relative* path on
     * a POSIX machine, so a hard-coded Windows path tested nothing there (and the nightly proved
     * it — `upload "C:/Windows/win.ini"` is resolved inside the workspace on Linux, correctly).
     */
    private fun outsidePath(workspace: Path, name: String): String =
        workspace.resolve("..").resolve(name).toAbsolutePath().normalize().toString().replace('\\', '/')

    /** `file://` URL for [path] — three slashes for both `C:/x` and `/x`. */
    private fun fileUrl(path: String): String =
        "file://" + if (path.startsWith("/")) path else "/$path"

    @Test
    @DisplayName("a file:// URL outside the workspace is refused")
    fun fileUrlOutsideIsRefused(@TempDir tmp: Path) {
        val refusal = externalPathRefusal("""goto "${fileUrl(outsidePath(tmp, "notes.txt"))}"""", tmp, allowExternalAccess = false)

        assertNotNull(refusal, "an external file:// read must be refused")
        assertTrue(refusal!!.contains("PATH_OUTSIDE_WORKSPACE"), refusal)
        assertTrue(refusal.contains("b4.run"), refusal)
        // P4-3: the hint says what is allowed instead of naming a JVM switch.
        assertTrue(refusal.contains("external access is disabled"), refusal)
        assertFalse(refusal.contains("-Dbrowser4"), refusal)
    }

    @Test
    @DisplayName("a file:// URL inside the workspace is allowed")
    fun fileUrlInsideIsAllowed(@TempDir tmp: Path) {
        Files.writeString(tmp.resolve("page.html"), "<html></html>")

        val allowed = externalPathRefusal(
            "goto \"${fileUrl(tmp.resolve("page.html").toString().replace('\\', '/'))}\"",
            tmp,
            allowExternalAccess = false,
        )

        assertNull(allowed)
    }

    @Test
    @DisplayName("uploading an absolute path outside the workspace is refused")
    fun uploadOutsideIsRefused(@TempDir tmp: Path) {
        val refusal = externalPathRefusal(
            """upload "#file" "${outsidePath(tmp, "win.ini")}"""", tmp, allowExternalAccess = false,
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
    @DisplayName("htmlsnapshot --output-file outside the workspace is refused")
    fun outputFileOutsideIsRefused(@TempDir tmp: Path) {
        val refusal = externalPathRefusal(
            """htmlsnapshot query --sql @query.sql --output-file "${outsidePath(tmp, "out.json")}"""",
            tmp,
            allowExternalAccess = false,
        )

        assertNotNull(refusal, "a write outside the workspace must be refused")
        assertTrue(refusal!!.contains("PATH_OUTSIDE_WORKSPACE"), refusal)
    }

    @Test
    @DisplayName("har stop --path outside the workspace is refused")
    fun harPathOutsideIsRefused(@TempDir tmp: Path) {
        val refusal = externalPathRefusal(
            """har stop --path "${outsidePath(tmp, "trace.har")}"""", tmp, allowExternalAccess = false,
        )

        assertNotNull(refusal, "har stop writes the HAR to the given path")
        assertTrue(refusal!!.contains("PATH_OUTSIDE_WORKSPACE"), refusal)
    }

    @Test
    @DisplayName("--output-file inside the workspace is allowed, in both --key=value and -o forms")
    fun outputFileInsideIsAllowed(@TempDir tmp: Path) {
        val target = tmp.resolve("out.json").toString().replace('\\', '/')

        assertNull(externalPathRefusal("""htmlsnapshot query --sql @query.sql --output-file=$target""", tmp, allowExternalAccess = false))
        assertNull(externalPathRefusal("""htmlsnapshot query --sql @query.sql -o "$target"""", tmp, allowExternalAccess = false))
        assertNull(externalPathRefusal("""htmlsnapshot query --sql @query.sql -o out.json""", tmp, allowExternalAccess = false))
    }

    @Test
    @DisplayName("nothing is refused when external access is enabled")
    fun externalAccessOptOut(@TempDir tmp: Path) {
        assertNull(externalPathRefusal("""goto "${fileUrl(outsidePath(tmp, "notes.txt"))}"""", tmp, allowExternalAccess = true))
        assertNull(externalPathRefusal("""har stop --path "${outsidePath(tmp, "trace.har")}"""", tmp, allowExternalAccess = true))
    }
}
