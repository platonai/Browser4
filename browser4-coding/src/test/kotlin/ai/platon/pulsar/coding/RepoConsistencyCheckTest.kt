package ai.platon.pulsar.coding

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [RepoConsistencyCheck] — repo-governance invariants
 * (VERSION vs root pom vs BOM vs module registration). Pure string analysis.
 */
class RepoConsistencyCheckTest {

    private val version = "4.13.4-SNAPSHOT"

    private val rootPom = """
        <project>
            <parent>
                <groupId>ai.platon</groupId>
                <artifactId>pulsar-parent</artifactId>
                <version>4.5.0</version>
            </parent>
            <artifactId>browser4</artifactId>
            <version>4.13.4-SNAPSHOT</version>
            <packaging>pom</packaging>
            <modules>
                <module>browser4-dependencies</module>
                <module>browser4-core</module>
                <module>browser4-rest</module>
            </modules>
        </project>
    """.trimIndent()

    private val bomPom = """
        <project>
            <artifactId>browser4-dependencies</artifactId>
            <version>4.13.4-SNAPSHOT</version>
            <packaging>pom</packaging>
        </project>
    """.trimIndent()

    @Test
    @DisplayName("consistent repo passes with no issues")
    fun consistentRepo() {
        val result = RepoConsistencyCheck.check(
            version, rootPom, bomPom,
            moduleExists = { it in setOf("browser4-dependencies", "browser4-core", "browser4-rest") })
        assertTrue(result.valid, "issues: ${result.issues}")
        assertEquals(0, result.issues.size)
    }

    @Test
    @DisplayName("missing VERSION is an error")
    fun missingVersion() {
        val result = RepoConsistencyCheck.check(null, rootPom, bomPom)
        assertFalse(result.valid)
        assertTrue(result.issues.any { it.severity == Severity.ERROR && it.message.contains("VERSION") })
    }

    @Test
    @DisplayName("malformed VERSION is an error")
    fun malformedVersion() {
        val result = RepoConsistencyCheck.check("not-a-version", rootPom, bomPom)
        assertFalse(result.valid)
        assertTrue(result.issues.any { it.message.contains("not a plausible version") })
    }

    @Test
    @DisplayName("root pom version mismatch with VERSION is an error")
    fun rootPomVersionMismatch() {
        val badPom = rootPom.replace("4.13.4-SNAPSHOT", "4.13.5-SNAPSHOT")
        val result = RepoConsistencyCheck.check(version, badPom, bomPom)
        assertFalse(result.valid)
        assertTrue(result.issues.any { it.message.contains("Root pom version") }, "issues: ${result.issues}")
    }

    @Test
    @DisplayName("BOM version mismatch with VERSION is an error")
    fun bomVersionMismatch() {
        val badBom = bomPom.replace("4.13.4-SNAPSHOT", "4.12.0")
        val result = RepoConsistencyCheck.check(version, rootPom, badBom)
        assertFalse(result.valid)
        assertTrue(result.issues.any { it.message.contains("BOM version") }, "issues: ${result.issues}")
    }

    @Test
    @DisplayName("missing root pom is an error")
    fun missingRootPom() {
        val result = RepoConsistencyCheck.check(version, null, bomPom)
        assertFalse(result.valid)
        assertTrue(result.issues.any { it.message.contains("root pom.xml") })
    }

    @Test
    @DisplayName("missing BOM is an error")
    fun missingBom() {
        val result = RepoConsistencyCheck.check(version, rootPom, null)
        assertFalse(result.valid)
        assertTrue(result.issues.any { it.message.contains("BOM") })
    }

    @Test
    @DisplayName("default module without a directory is an error")
    fun registeredModuleMissingDir() {
        val result = RepoConsistencyCheck.check(
            version, rootPom, bomPom,
            moduleExists = { it != "browser4-core" })
        assertFalse(result.valid)
        assertTrue(
            result.issues.any { it.message.contains("browser4-core") && it.message.contains("does not exist") },
            "issues: ${result.issues}"
        )
    }

    @Test
    @DisplayName("on-disk module not registered anywhere is a warning")
    fun onDiskModuleUnregistered() {
        val result = RepoConsistencyCheck.check(
            version, rootPom, bomPom,
            moduleExists = { true },
            onDiskModuleDirs = listOf("browser4-rest", "browser4-ghost"),
        )
        assertTrue(result.valid, "unregistered module should be a warning, not an error")
        assertTrue(
            result.issues.any { it.severity == Severity.WARNING && it.message.contains("browser4-ghost") },
            "issues: ${result.issues}"
        )
        assertFalse(result.issues.any { it.message.contains("browser4-rest") }, "registered module must not warn")
    }

    @Test
    @DisplayName("only the first modules block is checked for existence")
    fun profileModulesNotChecked() {
        val pomWithProfile = rootPom.replace("</modules>", "</modules>\n<profiles><profile><modules>" +
            "<module>browser4-never-existed</module></modules></profile></profiles>")
        val result = RepoConsistencyCheck.check(
            version, pomWithProfile, bomPom,
            moduleExists = { true })
        // The profile-scoped module is not validated for existence.
        assertFalse(result.issues.any { it.message.contains("browser4-never-existed") }, "issues: ${result.issues}")
    }
}
