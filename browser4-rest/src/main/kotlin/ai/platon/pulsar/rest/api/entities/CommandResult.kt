package ai.platon.pulsar.rest.api.entities

/**
 * Command result
 *
 * @property pageSummary The summary of the page.
 * @property fields The extracted fields from the page.
 * @property links The extracted links from the page.
 * @property xsqlResultSet The result set from the X-SQL query.
 * @property keyFindings The agent's completion key findings, when it reported any.
 * @property nextSuggestions Up to three follow-up tasks the agent proposed in its
 *   completion report. A client (the CLI's interactive session) turns them into a
 *   "likely next steps" menu; they describe work, not commands.
 * @property filesChanged The files the agent wrote or changed, as it reported them.
 * @property problems Open problems the agent's completion report named.
 * @property workspace The directory the agent's change landed in. For a coding task
 *   this is the **main repository** (the checkout the patch was applied to); for a
 *   browsing task it is the per-task workspace the tools wrote into. Deliverables
 *   that are not inlined in [summary]/[fields]/[keyFindings] live here — without it
 *   a caller had to search the backend's app-data tree by hand.
 * @property isolatedWorkspace The isolated git worktree a coding task ran in, when
 *   isolation was active. Informational: the change belongs to [workspace] once
 *   [merge] is `applied`; when [merge] is `refused` this is where it exists.
 * @property isolation How the coding workspace was provided (`worktree` / `mirror`
 *   / `none`), or null for a browsing task.
 * @property merge Whether the isolated change reached the main work tree:
 *   `applied` / `refused` / `pending`. `refused` means **nothing was applied** —
 *   the run's files live in [isolatedWorkspace] only.
 * @property patch The applied patch kept for `b4w recover --revert <taskId>`.
 * @property errorCode Machine-readable failure reason (`TOKEN_BUDGET`, `NOOP_LIMIT`,
 *   `CANCELLED`, `TOOL_ERROR`), so a client can branch instead of parsing prose.
 * @property resumable Whether `agent run --resume <id>` can continue this task.
 * @property effectiveTokenBudget The **cache-miss** token budget this run actually
 *   enforced (`0` = unlimited, `null` = unknown): uncached prompt + output, with
 *   prompt-cache reads excluded. A failed run that aborted on the budget must say
 *   which budget, or the caller cannot tell "too small" from "runaway" — and cannot
 *   offer the doubled-budget "continue".
 */
data class CommandResult(
    var summary: String? = null,
    var pageSummary: String? = null,
    var fields: Map<String, String>? = null,
    var links: List<String>? = null,
    var xsqlResultSet: List<Map<String, Any?>>? = null,
    var keyFindings: List<String>? = null,
    var nextSuggestions: List<String>? = null,
    var filesChanged: List<String>? = null,
    var problems: List<String>? = null,
    var workspace: String? = null,
    var isolatedWorkspace: String? = null,
    var isolation: String? = null,
    var merge: String? = null,
    var patch: String? = null,
    var errorCode: String? = null,
    var resumable: Boolean? = null,
    var effectiveTokenBudget: Long? = null,
)
