package ai.platon.pulsar.agentic.tools.high.crawl.common

import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.urls.URLUtils

open class NormXSQL(
    val url: String,
    val args: String,
    val sql: String
) {
    val configuredUrl get() = URLUtils.mergeUrlArgs(url, args)
}

class DegenerateXSQL(uuid: String, sql: String) : NormXSQL("${AppConstants.EXAMPLE_URL}/$uuid", "", sql)
