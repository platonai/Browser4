package ai.platon.pulsar.rest.mcp.controller

import ai.platon.pulsar.agentic.mcp.McpToolNames
import ai.platon.pulsar.agentic.model.ToolExample
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.agentic.tools.specs.ToolDocGenerator
import ai.platon.pulsar.agentic.tools.specs.ToolSpecGenerator
import ai.platon.pulsar.rest.mcp.contract.ToolRegistryFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
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

    private fun executors(): List<ToolExecutor> = ToolRegistryFixture.executors()

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

    @Test
    @DisplayName("no example field is dropped on the way into the machine-readable reference")
    fun exampleProjectionIsLossless() {
        val specs = specs()
        val docs = ToolDocGenerator.buildDocs(specs, advertisedNames())
            .associateBy { "${it["domain"]}.${it["method"]}" }

        var checked = 0
        for (spec in specs) {
            if (spec.examples.isEmpty()) continue
            val doc = docs["${spec.domain}.${spec.method}"] ?: continue
            @Suppress("UNCHECKED_CAST")
            val documented = doc["examples"] as List<Map<String, Any?>>
            assertEquals(
                spec.examples.size, documented.size,
                "${spec.domain}.${spec.method} lost examples on the way to the docs"
            )
            spec.examples.zip(documented).forEach { (specExample, docExample) ->
                val where = "${spec.domain}.${spec.method}"
                assertEquals(specExample.title, docExample["title"], "$where: title")
                assertEquals(specExample.args, docExample["args"], "$where: args")
                assertEquals(specExample.code, docExample["code"], "$where: code")
                assertEquals(specExample.notes, docExample["notes"], "$where: notes")
                assertEquals(specExample.expectsError, docExample["expectsError"], "$where: expectsError")
                // Tri-state: set means set, unset must stay unset (not `false`).
                if (specExample.runnable != null) {
                    assertEquals(specExample.runnable, docExample["runnable"], "$where: runnable")
                } else {
                    assertTrue(!docExample.containsKey("runnable"), "$where: runnable must stay unset")
                }
                assertEquals(
                    specExample.executable, docExample["executable"] ?: false,
                    "$where: executable is the field a client counts coverage with"
                )
                checked++
            }
        }
        assertTrue(checked > 0, "the registry must document at least one example")
    }

    @Test
    @DisplayName("a no-argument call is documented as runnable, a snippet is not")
    fun noArgumentExamplesAreRunnable() {
        val callable = ToolSpec(
            domain = "test", method = "noargs",
            description = "A tool that takes no arguments.",
            examples = listOf(ToolExample(title = "Just call it", runnable = true)),
        )
        val snippet = ToolSpec(
            domain = "test", method = "snippet",
            description = "A tool whose KDoc yielded a snippet only.",
            examples = listOf(ToolExample(title = "How it is used", code = "driver.title()")),
        )

        val docs = ToolDocGenerator.buildDocs(listOf(callable, snippet))
        val json = ToolDocGenerator.toJson(docs)

        @Suppress("UNCHECKED_CAST")
        val callableExample = (docs[0]["examples"] as List<Map<String, Any?>>).single()
        assertEquals(true, callableExample["runnable"], "an empty argument list must be declared callable")
        assertEquals(true, callableExample["executable"], "and it must count towards example coverage")

        @Suppress("UNCHECKED_CAST")
        val snippetExample = (docs[1]["examples"] as List<Map<String, Any?>>).single()
        assertTrue(!snippetExample.containsKey("runnable"), "a snippet makes no claim about callability")
        assertEquals(false, snippetExample["executable"] ?: false, "a snippet is not a call")

        // The JSON a CLI/IDE/gateway reads must carry both facts, and the
        // Markdown a human reads must not render an unusable bare bullet.
        assertTrue(json.contains("\"runnable\" : true"), "the runnable flag must reach the JSON artifact")
        assertTrue(json.contains("\"executable\" : true"), "the derived coverage flag must reach the JSON artifact")
        val markdown = ToolDocGenerator.toMarkdown(docs)
        assertTrue(
            markdown.contains("- Just call it: no arguments"),
            "a no-argument example must say so instead of rendering an empty bullet:\n$markdown"
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
