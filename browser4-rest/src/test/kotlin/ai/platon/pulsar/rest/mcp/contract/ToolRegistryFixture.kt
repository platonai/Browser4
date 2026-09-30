package ai.platon.pulsar.rest.mcp.contract

import ai.platon.pulsar.agent.tool.CommandToolExecutor
import ai.platon.pulsar.agent.tool.CrawlToolExecutor
import ai.platon.pulsar.agent.tool.HTMLSnapshotToolExecutor
import ai.platon.pulsar.agent.tool.SkillMCPToolExecutor
import ai.platon.pulsar.agent.tool.WebDbToolExecutor
import ai.platon.pulsar.agentic.memory.MemoryToolExecutor
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.BatchToolExecutor
import ai.platon.pulsar.agentic.tools.builtin.BrowserTabToolExecutor
import ai.platon.pulsar.agentic.tools.builtin.BrowserToolExecutor
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.agentic.tools.experience.ExperienceToolExecutor
import ai.platon.pulsar.agentic.tools.specs.ToolSpecGenerator
import ai.platon.pulsar.boot.skill.SkillService
import ai.platon.pulsar.rest.api.service.crawl.CrawlService
import ai.platon.pulsar.rest.session.PulsarSessionManager
import org.mockito.kotlin.mock

/**
 * The tool registry as the product advertises it, for the tests that must see
 * **every** domain: the generated reference, the contract matrix and the lint.
 *
 * The executor list is the one a running server merges (built-in executors plus the
 * plugin/business domains registered through `ToolMount`), with the collaborators
 * mocked because these tests only read specs — no browser, no session, no LLM.
 */
object ToolRegistryFixture {

    /** Every executor whose specs are advertised, in a stable order. */
    fun executors(): List<ToolExecutor> = listOf(
        BrowserTabToolExecutor(),
        BrowserToolExecutor(),
        MemoryToolExecutor(),
        ExperienceToolExecutor(),
        CrawlToolExecutor(mock<CrawlService>()),
        CommandToolExecutor(),
        WebDbToolExecutor(mock<PulsarSessionManager>()),
        HTMLSnapshotToolExecutor(mock<PulsarSessionManager>()),
        SkillMCPToolExecutor(mock<SkillService>()),
        BatchToolExecutor(),
    )

    /** The specs of every advertised tool, in a stable order. */
    fun specs(): List<ToolSpec> {
        // The tab domain's specs come from the mirrored WebDriver source; generating
        // them once keeps every consumer looking at the same set.
        ToolSpecGenerator.generateAllOnce()
        return executors().flatMap { it.getToolSpecs().values }
    }

    /** The advertised MCP tool name of a spec (`crawl.submit` → `crawl_submit`). */
    fun toolName(spec: ToolSpec): String =
        ai.platon.pulsar.agentic.mcp.McpToolNames.toMcpToolName(spec.domain, spec.method)
}
