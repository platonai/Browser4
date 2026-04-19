
package ai.platon.pulsar.skeleton.crawl.parse

import ai.platon.pulsar.common.CheckState
import ai.platon.pulsar.skeleton.crawl.parse.html.ParseContext
import java.util.concurrent.atomic.AtomicInteger

abstract class AbstractParseFilter(
        final override val id: Int = instanceSequencer.incrementAndGet(),
        override var parent: ParseFilter? = null
): ParseFilter {
    companion object {
        val instanceSequencer = AtomicInteger()
    }

    override val children = mutableListOf<ParseFilter>()

    override fun initialize() {
    }

    override fun isRelevant(parseContext: ParseContext): CheckState {
        val status = parseContext.page.protocolStatus
        return if (status.isSuccess) {
            CheckState()
        } else {
            CheckState(status.minorCode, message = status.toString())
        }
    }

    override fun filter(parseContext: ParseContext): FilterResult {
        if (isRelevant(parseContext).isNotOK) {
            return FilterResult()
        }

        onBeforeFilter(parseContext)
        var result = doFilter(parseContext)
        onAfterFilter(parseContext)

        if (result.shouldContinue) {
            children.forEach {
                if (result.shouldContinue) {
                    result = it.filter(parseContext)
                }
            }
        }

        return result
    }

    protected abstract fun doFilter(parseContext: ParseContext): FilterResult

    override fun onBeforeFilter(parseContext: ParseContext) {

    }

    override fun onAfterFilter(parseContext: ParseContext) {

    }

    override fun addFirst(child: ParseFilter) {
        child.parent = this
        children.add(0, child)
    }

    override fun addLast(child: ParseFilter) {
        child.parent = this
        children.add(child)
    }

    override fun close() {
        children.forEach { it.close() }
        children.clear()
        super.close()
    }
}
