package ai.platon.pulsar.agentic.tools.specs

import ai.platon.pulsar.common.B4LLMUtils
import ai.platon.pulsar.common.B4ProjectUtils
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Rewrites the committed `code-mirror` tool-spec snapshots.
 *
 * Those snapshots are the offline fallback for tool discovery (a JAR without the
 * mirrored sources loads them instead of scanning `WebDriver.kt`), so they must be
 * regenerated whenever a spec's rendered form changes — e.g. the fixed
 * [ai.platon.pulsar.agentic.model.ToolSpec.expression].
 *
 * The generator's own write path ([B4LLMUtils.writeAsResource]) resolves the
 * `code-mirror` directory from the process working directory, which during a test
 * run is the module directory — it finds nothing and silently writes nothing.
 * This regenerator resolves the directory from the project root instead.
 *
 * Usage (from the repository root):
 * ```
 * mvn -pl browser4-agentic test -Dtest=ToolSpecSnapshotRegenerator -DregenerateSpecSnapshots=true
 * ```
 * It is a no-op (skipped) unless `-DregenerateSpecSnapshots=true` is passed, so a
 * normal test run never rewrites tracked files.
 */
class ToolSpecSnapshotRegenerator {

    @Test
    @DisplayName("regenerate the code-mirror spec snapshots (opt-in via -DregenerateSpecSnapshots=true)")
    fun regenerateSnapshots() {
        assumeTrue(
            System.getProperty("regenerateSpecSnapshots") == "true",
            "opt-in regenerator: pass -DregenerateSpecSnapshots=true to rewrite the snapshots"
        )

        val dir = specSnapshotDir()

        val driverSource = B4LLMUtils.readSourceFileFromResource("browser4-core", "WebDriver.kt")
        assertTrue(driverSource.isNotBlank(), "WebDriver.kt source must be readable to regenerate the driver specs")
        write(
            dir.resolve(DRIVER_SNAPSHOT),
            ToolSpecGenerator.extractInterface("tab", driverSource, "WebDriver")
        )

        val agentSource = B4LLMUtils.readSourceFileFromResource("browser4-agentic", "PerceptiveAgent.kt")
        assertTrue(agentSource.isNotBlank(), "PerceptiveAgent.kt source must be readable to regenerate the agent specs")
        write(
            dir.resolve(AGENT_SNAPSHOT),
            ToolSpecGenerator.extractInterface("agent", agentSource, "PerceptiveAgent")
        )
    }

    private fun write(target: Path, specs: List<ai.platon.pulsar.agentic.model.ToolSpec>) {
        val content = ToolSpecGenerator.normalizeToLinuxLineEndings(ToolSpecGenerator.toSnapshotJson(specs))
        Files.writeString(target, content)
        println("Regenerated ${specs.size} specs -> $target")
    }

    private fun specSnapshotDir(): Path {
        // Walk up from the working directory (the module dir under Maven, the repo
        // root under an IDE) until the committed code-mirror directory shows up.
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve(CODE_MIRROR_SOURCE_DIR).resolve(B4ProjectUtils.CODE_MIRROR_DIR)
            if (candidate.exists()) return candidate
            dir = dir.parent
        }
        error("code-mirror source directory not found above ${Path.of("").toAbsolutePath()}")
    }

    private companion object {
        const val CODE_MIRROR_SOURCE_DIR = "browser4-core/browser4-resources/src/main/resources"
        const val DRIVER_SNAPSHOT = "driver-tool-call-specs.json"
        const val AGENT_SNAPSHOT = "agent-tool-call-specs.json"
    }
}
