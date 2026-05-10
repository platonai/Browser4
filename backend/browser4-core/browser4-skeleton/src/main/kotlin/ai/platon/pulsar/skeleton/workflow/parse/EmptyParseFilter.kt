package ai.platon.pulsar.skeleton.workflow.parse

import ai.platon.pulsar.skeleton.workflow.parse.html.ParseContext

/**
 * Extension point for DOM-based parsers. Permits one to add additional metadata
 * to parses provided by the html or tika plugins. All plugins found which
 * implement this extension point are run sequentially on the parse.
 */
class EmptyParseFilter : AbstractParseFilter() {
    override fun doFilter(parseContext: ParseContext): FilterResult {
        return FilterResult()
    }
}
