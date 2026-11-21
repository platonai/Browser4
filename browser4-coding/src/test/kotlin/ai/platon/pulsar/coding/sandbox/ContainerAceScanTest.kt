package ai.platon.pulsar.coding.sandbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Plan G9 — spotting the one DACL entry that makes a tree invisible to `windows-low-il`.
 *
 * Measured 2026-11-19 (run `4125acaa`): a directory carrying a concrete AppContainer package SID is
 * unreadable to a low-integrity restricted token, and the leftover grant that caused it came from
 * this very module. The scan is diagnostic-only, so every test here is about *what it reports* —
 * never about it editing an ACL.
 */
@DisplayName("container ACE scan")
class ContainerAceScanTest {

    @TempDir
    lateinit var tempDir: Path

    /** The SID observed on the broken npm global root (2026-11-19). */
    private val packageSid =
        "S-1-15-2-740000081-2787761786-2739701453-4027781610-3709442335-2178988983-1814727619"

    @Test
    @DisplayName("only a concrete package SID triggers the block")
    fun packageSidClassification() {
        assertTrue(ContainerAceScan.isPackageSid(packageSid))
        assertTrue(ContainerAceScan.isPackageSid(packageSid.lowercase()), "SIDs are case-insensitive")
        assertFalse(
            ContainerAceScan.isPackageSid(ContainerAceScan.ALL_APPLICATION_PACKAGES),
            "ALL APPLICATION PACKAGES is in every container token and never blocks a read",
        )
        assertFalse(ContainerAceScan.isPackageSid("S-1-5-21-1-2-3-1001"), "an ordinary SID is not a container")
        assertFalse(ContainerAceScan.isPackageSid(""), "blank is not a SID")
    }

    @Test
    @DisplayName("an icacls dump yields only the concrete package SIDs")
    fun parsesIcaclsOutput() {
        // The shape that broke the npm global root: one explicit package-SID entry plus inherited rows.
        val dump = """
            D:\MovedFolders\npm\node_modules\browser4-cli $packageSid:(OI)(CI)(RX)
                                                          NT AUTHORITY\SYSTEM:(I)(OI)(CI)(F)
                                                          ALTAIR\pereg:(I)(OI)(CI)(F)
                                                          S-1-15-2-1:(OI)(CI)(RX)
        """.trimIndent()

        assertEquals(setOf(packageSid), ContainerAceScan.packageSidsIn(dump))
        assertTrue(ContainerAceScan.packageSidsIn("ALTAIR\\pereg:(I)(F)").isEmpty())
    }

    @Test
    @DisplayName("the remediation command is the one an operator can paste")
    fun removalCommandShape() {
        // A relative path keeps the expectation identical on both separators.
        val directory = Path.of("toolchain", "pkg")
        assertEquals(
            listOf("icacls", directory.toString(), "/remove", "*S-1-15-2-9"),
            ContainerAceScan.removeCommand(directory, "s-1-15-2-9"),
            "the SID is upper-cased like icacls prints it",
        )
    }

    @Test
    @DisplayName("candidates cover toolchain roots, PATH entries and npm package directories")
    fun candidateRootsCoverNpmPackages() {
        val toolchain = Files.createDirectories(tempDir.resolve("jdk"))
        val npm = Files.createDirectories(tempDir.resolve("npm"))
        val packageDir = Files.createDirectories(npm.resolve("node_modules").resolve("browser4-cli"))
        val plain = Files.createDirectories(tempDir.resolve("bin"))

        val roots = ContainerAceScan.candidateRoots(
            toolchainRoots = listOf(toolchain),
            pathDirs = listOf(npm, plain, tempDir.resolve("missing")),
        )

        assertTrue(toolchain in roots, "toolchain roots are checked: $roots")
        assertTrue(npm in roots, "PATH entries are checked: $roots")
        assertTrue(plain in roots, "PATH entries are checked: $roots")
        assertTrue(
            roots.any { it == packageDir.toAbsolutePath().normalize() },
            "the leaked entry sat on a package directory under node_modules: $roots",
        )
        assertFalse(
            roots.any { it.fileName.toString() == "missing" },
            "a PATH entry that does not exist is skipped: $roots",
        )
    }

    @Test
    @DisplayName("scan reports only the directories that really carry a package SID")
    fun scanReportsFindings() {
        val dirty = Files.createDirectories(tempDir.resolve("dirty"))
        val clean = Files.createDirectories(tempDir.resolve("clean"))
        val runner = object : SandboxCommandRunner {
            override fun run(command: List<String>, timeoutSeconds: Long): SandboxCommandResult =
                if (command.last().endsWith("dirty")) SandboxCommandResult(0, "$packageSid:(OI)(CI)(RX)")
                else SandboxCommandResult(0, "NT AUTHORITY\\SYSTEM:(I)(F)")
        }

        val findings = ContainerAceScan.scan(listOf(dirty, clean), runner)

        assertEquals(1, findings.size, "only the dirty directory is reported: $findings")
        assertEquals(dirty.toAbsolutePath().normalize(), findings.single().directory)
        val warning = ContainerAceScan.warning(findings.single())
        assertTrue(warning.contains(packageSid), warning)
        assertTrue(warning.contains("/remove"), "the warning carries the fix: $warning")
        assertTrue(warning.contains("拒绝访问").not(), "the warning stays English, like the rest: $warning")
    }

    @Test
    @DisplayName("a directory icacls refuses is skipped instead of failing the check")
    fun unreadableDirectoryIsSkipped() {
        val runner = object : SandboxCommandRunner {
            override fun run(command: List<String>, timeoutSeconds: Long) =
                SandboxCommandResult(5, "拒绝访问。")
        }

        assertTrue(ContainerAceScan.scan(listOf(tempDir), runner).isEmpty(), "a denial is not a finding")
    }
}
