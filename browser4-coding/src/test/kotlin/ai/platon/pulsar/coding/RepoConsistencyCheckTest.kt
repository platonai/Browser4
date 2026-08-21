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

    // ---- Plugin SDK versions ----

    private fun pluginManifest(name: String, sdkVersion: String?): String {
        val sdkField = sdkVersion?.let { "\"sdkVersion\": \"$it\"," } ?: ""
        return """
            {
              "name": "$name",
              "version": "1.0.0",
              $sdkField
              "dependsOn": ["browser4-skeleton"]
            }
        """.trimIndent()
    }

    @Test
    @DisplayName("plugin manifest with sdkVersion matching VERSION passes")
    fun pluginSdkVersionMatches() {
        val result = RepoConsistencyCheck.check(
            version, rootPom, bomPom,
            pluginManifestContents = listOf(pluginManifest("browser4-a", version), pluginManifest("browser4-b", version)))
        assertTrue(result.valid, "issues: ${result.issues}")
        assertFalse(result.issues.any { it.message.contains("sdkVersion") }, "issues: ${result.issues}")
    }

    @Test
    @DisplayName("plugin manifest missing sdkVersion is an error")
    fun pluginSdkVersionMissing() {
        val result = RepoConsistencyCheck.check(
            version, rootPom, bomPom,
            pluginManifestContents = listOf(pluginManifest("browser4-a", null)))
        assertFalse(result.valid)
        assertTrue(
            result.issues.any { it.severity == Severity.ERROR && it.message.contains("browser4-a") && it.message.contains("sdkVersion") },
            "issues: ${result.issues}"
        )
    }

    @Test
    @DisplayName("plugin manifest sdkVersion mismatching VERSION is an error")
    fun pluginSdkVersionMismatch() {
        val result = RepoConsistencyCheck.check(
            version, rootPom, bomPom,
            pluginManifestContents = listOf(pluginManifest("browser4-a", "4.12.0")))
        assertFalse(result.valid)
        assertTrue(
            result.issues.any { it.message.contains("does not match VERSION") },
            "issues: ${result.issues}"
        )
    }

    @Test
    @DisplayName("archetype template placeholders are exempt from the sdkVersion check")
    fun pluginSdkVersionTemplatePlaceholdersExempt() {
        val template = """
            {
              "name": "${'$'}{pluginName}",
              "version": "${'$'}{version}",
              "sdkVersion": "${'$'}{browser4-version}",
              "dependsOn": ["browser4-skeleton"]
            }
        """.trimIndent()
        val result = RepoConsistencyCheck.check(
            version, rootPom, bomPom,
            pluginManifestContents = listOf(template))
        assertTrue(result.valid, "template placeholders must not fail governance: ${result.issues}")
    }

    // ---- isPluginManifestPath ----

    @Test
    @DisplayName("ModuleMap source hygiene flags trailing whitespace and overlong lines as warnings")
    fun moduleMapFormatFlagsTrailingWhitespaceAndLongLines() {
        val badSource = "package x\n" +
            "val a = 1  \n" + // trailing whitespace
            "        \"browser4-core/browser4-protocol\" to listOf(\"browser4-plugins/browser4-wordcount\", \"browser4-plugins/browser4-pagetitle\"),\n" // > 120 cols
        val result = RepoConsistencyCheck.check(
            version, rootPom, bomPom,
            moduleMapSource = badSource)
        assertTrue(result.issues.any { it.severity == Severity.WARNING && it.message.contains("trailing whitespace") },
            "issues: ${result.issues}")
        assertTrue(result.issues.any { it.severity == Severity.WARNING && it.message.contains("columns wide") },
            "issues: ${result.issues}")
        // Warnings do not fail the validation.
        assertTrue(result.valid, "format warnings must not fail governance: ${result.issues}")
    }

    @Test
    @DisplayName("clean ModuleMap source yields no format warnings")
    fun moduleMapFormatCleanSourceNoWarnings() {
        val cleanSource = "package x\n        \"browser4-core/browser4-protocol\" to listOf(\n            \"browser4-plugins/browser4-wordcount\",\n        ),\n"
        val result = RepoConsistencyCheck.check(
            version, rootPom, bomPom,
            moduleMapSource = cleanSource)
        assertTrue(result.issues.none { it.message.contains("columns wide") || it.message.contains("trailing whitespace") },
            "clean source must yield no format warnings: ${result.issues}")
    }

    private fun writeManifest(dir: java.nio.file.Path, relPath: String): java.nio.file.Path {
        val file = dir.resolve(relPath)
        file.parent.toFile().mkdirs()
        java.nio.file.Files.writeString(file, """{"name": "x", "version": "1.0.0"}""")
        return file
    }

    @Test
    @DisplayName("isPluginManifestPath accepts manifests outside target and hidden dirs")
    fun isPluginManifestPathAcceptsSourceManifests() {
        val dir = java.nio.file.Files.createTempDirectory("repo-consistency-")
        try {
            val pluginJson = writeManifest(dir, "browser4-plugins/browser4-seo/src/main/resources/META-INF/browser4-plugin.json")
            assertTrue(RepoConsistencyCheck.isPluginManifestPath(pluginJson))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    @DisplayName("isPluginManifestPath rejects build output and hidden dirs")
    fun isPluginManifestPathRejectsTargetAndHiddenDirs() {
        val dir = java.nio.file.Files.createTempDirectory("repo-consistency-")
        try {
            assertFalse(RepoConsistencyCheck.isPluginManifestPath(
                writeManifest(dir, "browser4-pageinfo/target/classes/META-INF/browser4-plugin.json")))
            assertFalse(RepoConsistencyCheck.isPluginManifestPath(
                writeManifest(dir, ".worktrees/other-branch/browser4-plugins/browser4-seo/src/main/resources/META-INF/browser4-plugin.json")))
            assertFalse(RepoConsistencyCheck.isPluginManifestPath(
                writeManifest(dir, ".git/objects/browser4-plugin.json")))
            // A non-manifest file name is rejected regardless of location.
            assertFalse(RepoConsistencyCheck.isPluginManifestPath(
                writeManifest(dir, "browser4-plugins/browser4-seo/src/main/resources/META-INF/other.json")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
