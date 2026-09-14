package ai.platon.pulsar.rest.mcp.controller

import ai.platon.pulsar.agentic.mcp.McpToolNames
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.BatchToolExecutor
import ai.platon.pulsar.agentic.tools.builtin.BrowserTabToolExecutor
import ai.platon.pulsar.agentic.tools.builtin.BrowserToolExecutor
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.agentic.tools.experience.ExperienceToolExecutor
import ai.platon.pulsar.agentic.tools.specs.ToolDocGenerator
import ai.platon.pulsar.agentic.tools.specs.ToolSpecGenerator
import ai.platon.pulsar.agent.tool.CommandToolExecutor
import ai.platon.pulsar.agent.tool.CrawlToolExecutor
import ai.platon.pulsar.agent.tool.HTMLSnapshotToolExecutor
import ai.platon.pulsar.agent.tool.SkillMCPToolExecutor
import ai.platon.pulsar.agent.tool.WebDbToolExecutor
import ai.platon.pulsar.agentic.memory.MemoryToolExecutor
import ai.platon.pulsar.boot.skill.SkillService
import ai.platon.pulsar.rest.api.service.CrawlService
import ai.platon.pulsar.rest.session.PulsarSessionManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * The tool reference under `docs/` is generated from the registry, and this test
 * is the drift gate: regenerating must reproduce the committed files byte for
 * byte, so the documentation cannot describe a tool that no longer exists (or
 * miss one that does).
 *
 * Regenerate:
 * ```
 * mvn -pl browser4-rest test -Dtest=ToolDocGeneratorTest -DregenerateToolDocs=true
 * ```
 */
@DisplayName("Generated tool reference")
class ToolDocGeneratorTest {

    private fun executors(): List<ToolExecutor> = listOf(
        BrowserTabToolExecutor(),
        BrowserToolExecutor(),
        MemoryToolExecutor(),
        ExperienceToolExecutor(),
        CrawlToolExecutor(mock<CrawlService>()),
        CommandToolExecutor(),
        WebDbToolExecutor(mock<PulsarSessionManager>()),
        HTMLSnapshotToolExecutor(mock<PulsarSessionManager>()),
        SkillMCPToolExecutor(mock<SkillService>()),
        // The batch primitive both channels advertise (requirement 12).
        BatchToolExecutor(),
    )

    private fun specs(): List<ToolSpec> {
        ToolSpecGenerator.generateAllOnce()
        return executors().flatMap { it.getToolSpecs().values }
    }

    @Test
    @DisplayName("the committed reference matches the registry")
    fun committedReferenceMatchesRegistry() {
        val docs = ToolDocGenerator.buildDocs(specs(), advertisedNames())
        val markdown = ToolDocGenerator.toMarkdown(docs)
        val json = ToolDocGenerator.toJson(docs)

        val docsDir = docsDir()
        val markdownPath = docsDir.resolve(MARKDOWN_NAME)
        val jsonPath = docsDir.resolve(JSON_NAME)

        if (System.getProperty("regenerateToolDocs") == "true") {
            Files.writeString(markdownPath, markdown)
            Files.writeString(jsonPath, json)
            println("Regenerated ${docs.size} tool docs -> $markdownPath")
            return
        }

        assumeTrue(markdownPath.exists() && jsonPath.exists()) {
            "Tool reference missing — regenerate with -DregenerateToolDocs=true"
        }
        assertTrue(docs.isNotEmpty(), "the registry must expose tools")
        assertEquals(
            Files.readString(markdownPath).replace("\r\n", "\n"),
            markdown.replace("\r\n", "\n"),
            "docs/$MARKDOWN_NAME is stale — regenerate with -DregenerateToolDocs=true"
        )
        assertEquals(
            Files.readString(jsonPath).replace("\r\n", "\n"),
            json.replace("\r\n", "\n"),
            "docs/$JSON_NAME is stale — regenerate with -DregenerateToolDocs=true"
        )
    }

    /** Canonical tool names plus the frontend aliases each one is reachable by. */
    private fun advertisedNames(): Map<String, List<String>> {
        val names = linkedMapOf<String, MutableList<String>>()
        for (spec in specs()) {
            names.getOrPut("${spec.domain}.${spec.method}") { mutableListOf() }
                .add(McpToolNames.toMcpToolName(spec.domain, spec.method))
        }
        for (alias in McpToolNames.frontendAliases) {
            val key = "${alias.domain}.${alias.method}"
            names[key]?.let { if (!it.contains(alias.frontendName)) it.add(alias.frontendName) }
        }
        return names
    }

    private fun docsDir(): Path {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("docs")
            if (candidate.exists() && dir.resolve("pom.xml").exists()) return candidate
            dir = dir.parent
        }
        error("docs directory not found above ${Path.of("").toAbsolutePath()}")
    }

    private companion object {
        const val MARKDOWN_NAME = "mcp-tools.md"
        const val JSON_NAME = "mcp-tools.json"
    }
}
