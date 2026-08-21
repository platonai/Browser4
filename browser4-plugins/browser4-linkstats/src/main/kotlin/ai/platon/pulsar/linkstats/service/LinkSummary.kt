package ai.platon.pulsar.linkstats.service

/**
 * Typed view of the page link distribution produced by summarize.js.
 *
 * @property url the page URL the summary was computed on
 * @property title the page title
 * @property total number of counted anchors (internal + external + mailto + tel)
 * @property internal relative paths or links sharing the page host
 * @property external links to any other host
 * @property mailto anchors with a mailto: href
 * @property tel anchors with a tel: href
 * @property nofollow anchors whose rel attribute contains "nofollow"
 */
data class LinkSummary(
    val url: String = "",
    val title: String = "",
    val total: Int = 0,
    val internal: Int = 0,
    val external: Int = 0,
    val mailto: Int = 0,
    val tel: Int = 0,
    val nofollow: Int = 0,
) {
    companion object {
        /**
         * Build a [LinkSummary] from the flat map produced by [LinkstatsService.summarizeAsMap].
         * Missing or unparsable values fall back to their defaults.
         */
        fun from(map: Map<String, Any?>): LinkSummary = LinkSummary(
            url = map["url"]?.toString() ?: "",
            title = map["title"]?.toString() ?: "",
            total = map["total"].asInt(),
            internal = map["internal"].asInt(),
            external = map["external"].asInt(),
            mailto = map["mailto"].asInt(),
            tel = map["tel"].asInt(),
            nofollow = map["nofollow"].asInt(),
        )
    }
}

/** Coerce a JS number (Number/String/Boolean) to an Int, 0 on anything else. */
private fun Any?.asInt(): Int = when (this) {
    is Number -> toInt()
    is String -> trim().toIntOrNull() ?: 0
    is Boolean -> if (this) 1 else 0
    else -> 0
}