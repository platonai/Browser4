package ai.platon.pulsar.chrome.session

import ai.platon.pulsar.common.AppPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * The profile reaper reclaims the per-launch context directories that used to
 * accumulate in the temp tree, and must never touch a profile that has to
 * survive the close.
 */
@DisplayName("Session profile reaper")
class SessionProfileReaperTest {

    private val rotationDir: Path
        get() = AppPaths.CONTEXT_BASE_DIR
            .resolve("groups")
            .resolve("default")
            .resolve("PULSAR_CHROME")
            .resolve("cx.900001")

    private val namedDir: Path
        get() = AppPaths.getContextBaseDir(
            "named",
            ai.platon.pulsar.common.browser.BrowserType.PULSAR_CHROME
        ).resolve("cx.0b23b091-faec-4d0d-b858-2060b5db4c6d")

    @Test
    @DisplayName("a sequential rotation directory under the context tree is disposable")
    fun rotationDirectoryIsDisposable() {
        assertTrue(SessionProfileReaper.isDisposableContextDir(rotationDir), rotationDir.toString())
    }

    @Test
    @DisplayName("a named session profile is kept even though its directory starts with cx.")
    fun namedSessionProfileIsKept() {
        assertFalse(
            SessionProfileReaper.isDisposableContextDir(namedDir),
            "a named session's profile is stable across restarts: $namedDir"
        )
    }

    @Test
    @DisplayName("the persistent default, prototype and browser home are never disposable")
    fun persistentRootsAreKept() {
        listOf(
            AppPaths.CONTEXT_DEFAULT_DIR,
            AppPaths.CHROME_DATA_DIR_PROTOTYPE,
            AppPaths.BROWSER_DATA_DIR,
            AppPaths.CONTEXT_DEFAULT_DIR.resolve("cx.123"),
        ).forEach { path ->
            assertFalse(SessionProfileReaper.isDisposableContextDir(path), "kept: $path")
        }
    }

    @Test
    @DisplayName("an explicitly pinned context dir is kept even inside the context tree")
    fun explicitCapabilityWins() {
        assertTrue(SessionProfileReaper.isDisposableContextDir(rotationDir))
        assertFalse(
            SessionProfileReaper.isDisposableContextDir(rotationDir, protectedPaths = setOf(rotationDir)),
            "a session that pinned this directory owns it"
        )
        // The pin is compared on the normalized absolute path.
        assertFalse(
            SessionProfileReaper.isDisposableContextDir(
                rotationDir,
                protectedPaths = setOf(rotationDir.toAbsolutePath().normalize()),
            )
        )
    }

    @Test
    @DisplayName("a directory that is not a rotation or temp context is kept")
    fun unknownLayoutIsKept() {
        val groupBase = AppPaths.CONTEXT_BASE_DIR.resolve("groups").resolve("team-a").resolve("PULSAR_CHROME")
        assertFalse(SessionProfileReaper.isDisposableContextDir(groupBase.resolve("keep-me")))
        assertFalse(SessionProfileReaper.isDisposableContextDir(null))
        // Outside the context tree entirely (an `--profile <path>` browser).
        assertFalse(SessionProfileReaper.isDisposableContextDir(Path.of(System.getProperty("java.io.tmpdir"), "my-profile")))
    }

    @Test
    @DisplayName("deleteIfDisposable removes the rotation directory and reports the reclaimed bytes")
    fun deletesRotationDirectory() {
        Files.createDirectories(rotationDir.resolve("PULSAR_CHROME"))
        Files.writeString(rotationDir.resolve("PULSAR_CHROME").resolve("Cookies"), "x".repeat(2048))
        Files.writeString(rotationDir.resolve("launcher.pid"), "4242")

        val report = SessionProfileReaper.deleteIfDisposable(rotationDir)

        assertEquals(ProfileCleanup.DELETED, report.outcome)
        assertTrue(report.isGone)
        assertFalse(Files.exists(rotationDir), "the rotation dir must be gone")
        assertTrue(report.freedBytes >= 2048, "reclaimed ${report.freedBytes} bytes")
        assertTrue(report.summary.contains("reclaimed"), report.summary)
    }

    @Test
    @DisplayName("deleteIfDisposable keeps a named session profile and says why")
    fun keepsNamedProfileOnDelete() {
        Files.createDirectories(namedDir)
        Files.writeString(namedDir.resolve("Cookies"), "login state")

        val report = SessionProfileReaper.deleteIfDisposable(namedDir)

        assertEquals(ProfileCleanup.RETAINED, report.outcome)
        assertFalse(report.isGone)
        assertTrue(Files.exists(namedDir.resolve("Cookies")), "the named profile must survive")
        assertTrue(report.reason.isNotBlank(), report.summary)
    }

    @Test
    @DisplayName("a disposable directory that is already gone is reported as missing")
    fun alreadyGoneIsMissing() {
        val missing = AppPaths.CONTEXT_BASE_DIR
            .resolve("groups")
            .resolve("default")
            .resolve("PULSAR_CHROME")
            .resolve("cx.900005")
        assertFalse(Files.exists(missing), "the test needs a path that does not exist")

        val report = SessionProfileReaper.deleteIfDisposable(missing)

        assertEquals(ProfileCleanup.MISSING, report.outcome)
        assertTrue(report.isGone)
    }

    @Test
    @DisplayName("an unknown path is never deleted")
    fun unknownPathIsNeverDeleted(@TempDir root: Path) {
        val dir = Files.createDirectories(root.resolve("cx.900003"))
        Files.writeString(dir.resolve("Cookies"), "keep")

        val report = SessionProfileReaper.deleteIfDisposable(dir)

        assertEquals(ProfileCleanup.RETAINED, report.outcome)
        assertTrue(Files.exists(dir.resolve("Cookies")))
    }
}
