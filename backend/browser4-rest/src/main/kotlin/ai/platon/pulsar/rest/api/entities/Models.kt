package ai.platon.pulsar.rest.api.entities

import ai.platon.pulsar.agentic.tools.high.crawl.PageVisitRequest
import ai.platon.pulsar.skeleton.common.options.LoadOptions

// Command-related types are now in browser4-tools module.
// These typealiases maintain backward compatibility for REST layer consumers.
typealias CommandRequest = PageVisitRequest
typealias CommandResult = ai.platon.pulsar.agentic.tools.high.command.CommandResult
typealias CommandAgentState = ai.platon.pulsar.agentic.tools.high.command.CommandAgentState
typealias CommandAgentHistory = ai.platon.pulsar.agentic.tools.high.command.CommandAgentHistory
typealias InstructResult = ai.platon.pulsar.agentic.tools.high.command.InstructResult
typealias CommandStatus = ai.platon.pulsar.agentic.tools.high.command.CommandStatus

/**
 * Request for chat
 *
 * @property url The page url
 * @property prompt The prompt, e.g. "Tell me something about the page"
 * @property args The load arguments
 * @property actions Instructs, e.g. "click the button with id 'submit'", [actions]  are performed after the active DOM is ready
 * */
data class PromptRequest(
    /**
     * The page url
     * */
    var url: String,
    /**
     * The prompt, e.g. "Tell me something about the page"
     * */
    var prompt: String? = null,
    /**
     * The load arguments
     *
     * @see [LoadOptions]
     * */
    var args: String? = null,
    /**
     * Actions, e.g. "click the button with id 'submit'", [actions] are performed after the active DOM is ready
     * */
    var actions: List<String>? = null
)

data class ScrapeStatusRequest(
    val id: String,
)

/**
 * W3 resources
 * */
data class W3DocumentRequest(
    var url: String,
    val args: String? = null,
)

data class NavigateRequest(
    var url: String,
)

data class ScreenshotRequest(
    var id: String
)
