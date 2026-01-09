package ai.platon.pulsar.manual

import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.common.sql.ResultSetFormatter
import ai.platon.pulsar.skeleton.PulsarSettings

/**
 * Demonstrates how to use X-SQL to query the Web.
 * */
fun main() {
    // Use the default browser which has an isolated profile.
    PulsarSettings.withDefaultBrowser()

    val context = AgenticContexts.create()
    val sql = """
select
      dom_first_text(dom, '#productTitle') as title,
      dom_first_text(dom, '#bylineInfo') as brand,
      dom_first_text(dom, '#price tr td:matches(^Price) ~ td, #corePrice_desktop tr td:matches(^Price) ~ td') as price,
      dom_first_text(dom, '#acrCustomerReviewText') as ratings,
      str_first_float(dom_first_text(dom, '#reviewsMedley .AverageCustomerReviews span:contains(out of)'), 0.0) as score
  from load_and_select('https://www.amazon.com/dp/B08PP5MSVB -i 1s -njr 3', 'body');
            """
    val rs = context.executeQuery(sql)
    println(ResultSetFormatter(rs, withHeader = true))
}
