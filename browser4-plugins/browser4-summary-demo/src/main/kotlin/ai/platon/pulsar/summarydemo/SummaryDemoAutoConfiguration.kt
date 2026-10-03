package ai.platon.pulsar.summarydemo

import ai.platon.pulsar.skeleton.plugin.PageSummaryAlgorithmMount
import ai.platon.pulsar.skeleton.workflow.parse.html.PageSummaryAlgorithm
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Lazy

@AutoConfiguration
@Lazy
class SummaryDemoAutoConfiguration : PageSummaryAlgorithmMount {
    override fun getPageSummaryAlgorithms(): List<PageSummaryAlgorithm> =
        listOf(OutlineSummaryAlgorithm())
}
