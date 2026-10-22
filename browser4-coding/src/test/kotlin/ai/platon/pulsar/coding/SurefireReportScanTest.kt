package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * Regression tests for the "did the test filter actually run anything?" proof
 * used by the `test` verification check.
 *
 * `mvn test -pl <module> -Dtest=FooTest -Dsurefire.failIfNoSpecifiedTests=false`
 * exits 0 when the filter matches nothing — which is how a `--verify
 * --run-tests` verdict used to go green after executing zero tests (a class
 * living in another module than the compiled one). The verdict now proves
 * execution from the surefire reports instead of the exit code.
 */
class SurefireReportScanTest {

    @TempDir
    lateinit var root: Path

    private fun writeReport(module: String, name: String, ageMillis: Long = 0): Path {
        val dir = root.resolve(module).resolve("target").resolve("surefire-reports")
        Files.createDirectories(dir)
        val file = Files.writeString(dir.resolve(name), "<testsuite/>")
        if (ageMillis > 0) {
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - ageMillis))
        }
        return file
    }

    @Test
    @DisplayName("fresh reports prove execution, stale ones are ignored, xml+txt of one class count once")
    fun countsFreshReportsPerClass() {
        writeReport("browser4-rest", "TEST-ai.platon.pulsar.FillCursorTest.xml")
        writeReport("browser4-rest", "ai.platon.pulsar.FillCursorTest.txt")
        writeReport("browser4-rest", "TEST-ai.platon.pulsar.OldTest.xml", ageMillis = 3_600_000)

        val executed = countFreshSurefireReports(root, "browser4-rest", System.currentTimeMillis() - 60_000)

        assertEquals(1, executed)
    }

    @Test
    @DisplayName("a module with no surefire report directory proves zero executed tests")
    fun missingReportDirectoryIsZero() {
        assertEquals(0, countFreshSurefireReports(root, "browser4-rest", 0))
    }

    @Test
    @DisplayName("text-only surefire output (XML report disabled) is still detected")
    fun textOnlyReportsAreDetected() {
        writeReport("browser4-coding", "ai.platon.pulsar.coding.DevTaskPlannerTest.txt")
        assertEquals(1, countFreshSurefireReports(root, "browser4-coding", System.currentTimeMillis() - 60_000))
    }

    @Test
    @DisplayName("an empty report directory is not proof of execution")
    fun emptyReportDirectoryIsZero() {
        Files.createDirectories(root.resolve("m/target/surefire-reports"))
        assertEquals(0, countFreshSurefireReports(root, "m", System.currentTimeMillis() - 60_000))
    }
}
