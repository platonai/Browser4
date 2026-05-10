package ai.platon.pulsar.agentic.tools.specs

import ai.platon.pulsar.agentic.model.ToolSpec

/**
 * Provides tool-call specifications (signatures) that can be rendered into prompts so the LLM can
 * perceive available tools.
 */
interface ToolCallSpecificationProvider {

    /**
     * Returns tool-call specs that should be exposed to the model.
     */
    fun getToolCallSpecifications(): List<ToolSpec>
}

