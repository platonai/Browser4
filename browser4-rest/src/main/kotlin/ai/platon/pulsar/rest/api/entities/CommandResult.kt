package ai.platon.pulsar.rest.api.entities

/**
 * Command result
 *
 * @property pageSummary The summary of the page.
 * @property fields The extracted fields from the page.
 * @property links The extracted links from the page.
 * @property xsqlResultSet The result set from the X-SQL query.
 */
data class CommandResult(
    var summary: String? = null,
    var pageSummary: String? = null,
    var fields: Map<String, String>? = null,
    var links: List<String>? = null,
    var xsqlResultSet: List<Map<String, Any?>>? = null,
)
