package ai.platon.pulsar.rest.api.entities

/**
 * Command result
 *
 * @property pageSummary The summary of the page.
 * @property fields The extracted fields from the page.
 * @property links The extracted links from the page.
 * @property xsqlResultSet The result set from the X-SQL query.
 * @property keyFindings The agent's completion key findings, when it reported any.
 * @property filesChanged The files the agent wrote or changed, as it reported them.
 * @property problems Open problems the agent's completion report named.
 * @property workspace The directory the agent's tools wrote into. Deliverables that
 *   are not inlined in [summary]/[fields]/[keyFindings] live here — without it a
 *   caller had to search the backend's app-data tree by hand.
 */
data class CommandResult(
    var summary: String? = null,
    var pageSummary: String? = null,
    var fields: Map<String, String>? = null,
    var links: List<String>? = null,
    var xsqlResultSet: List<Map<String, Any?>>? = null,
    var keyFindings: List<String>? = null,
    var filesChanged: List<String>? = null,
    var problems: List<String>? = null,
    var workspace: String? = null,
)
