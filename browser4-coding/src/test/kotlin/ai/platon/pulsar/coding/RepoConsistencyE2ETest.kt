package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end: run [RepoConsistencyCheck] against the REAL Browser4 checkout.
 *
 * The test locates the repo root by walking up from the working directory
 * looking for `VERSION` + root `pom.xml`. It is skipped silently when the
 * working directory is not inside a Browser4 checkout (e.g. CI packaging).
 *
 * Passing requires the live governance invariants to hold: VERSION == root pom
 * version == BOM version, and every default module directory to exist. This is
 * the same check `coding.validate(type="repo-consistency")` performs for the
 * agent.
 */
class RepoConsistencyE2ETest {

    @Test
    @DisplayName("real Browser4 checkout passes repo-consistency")
    fun realCheckoutIsConsistent() {
        val root = findRepoRoot() ?: return
        val versionContent = Files.readString(root.resolve("VERSION"))
        val rootPom = Files.readString(root.resolve("pom.xml"))
        val bomPom = Files.readString(root.resolve("browser4-dependencies/pom.xml"))

        val onDiskModuleDirs = Files.list(root).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .filter { Files.isRegularFile(it.resolve("pom.xml")) }
                .map { root.relativize(it).toString().replace('\\', '/') }
                .sorted()
                .toList()
        }

        val result = RepoConsistencyCheck.check(
            versionContent = versionContent,
            rootPom = rootPom,
            bomPom = bomPom,
            moduleExists = { Files.isDirectory(root.resolve(it)) },
            onDiskModuleDirs = onDiskModuleDirs,
        )
        assertTrue(result.valid, "live repo should be internally consistent, issues: ${result.issues}")
    }

    private fun findRepoRoot(): Path? {
        var dir: Path? = Path.of("").toAbsolutePath()
        repeat(6) {
            val d = dir ?: return null
            if (Files.isRegularFile(d.resolve("VERSION")) && Files.isRegularFile(d.resolve("pom.xml"))) {
                return d
            }
            dir = d.parent
        }
        return null
    }
}
