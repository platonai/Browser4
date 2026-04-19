package ai.platon.pulsar.skeleton.crawl.common

import java.util.*

object FetchState {
    const val UNKNOWN = -1
    const val DO_NOT_FETCH = 0
    const val NEW_PAGE = 1
    const val EXPIRED = 2
    const val SCHEDULED = 5
    const val REFRESH = 6
    const val CONNECT = 7
    const val NO_CONTENT = 20
    const val SMALL_CONTENT = 21
    const val MISS_FIELD = 30
    const val TEMP_MOVED = 300
    const val RETRY = 301

    val codes = HashMap<Int, String>()
    val symbols = HashMap<Int, String>()

    val refreshCodes = listOf(NEW_PAGE, RETRY, REFRESH, EXPIRED, SCHEDULED, NO_CONTENT, SMALL_CONTENT, MISS_FIELD)

    fun toString(code: Int): String {
        return codes.getOrDefault(code, "unknown")
    }

    fun toSymbol(code: Int): String {
        return symbols.getOrDefault(code, "U")
    }

    init {
        codes[UNKNOWN] = "unknown"
        codes[DO_NOT_FETCH] = "do_not_fetch"
        codes[NEW_PAGE] = "new_page"
        codes[REFRESH] = "refresh"
        codes[EXPIRED] = "expired"
        codes[SCHEDULED] = "scheduled"
        codes[NO_CONTENT] = "no_content"
        codes[SMALL_CONTENT] = "small"
        codes[MISS_FIELD] = "miss_field"
        codes[TEMP_MOVED] = "temp_moved"
        codes[RETRY] = "retry"

        symbols[DO_NOT_FETCH] = ""
        symbols[NEW_PAGE] = "N"
        symbols[REFRESH] = "RR"
        symbols[EXPIRED] = "EX"
        symbols[SCHEDULED] = "SD"
        symbols[RETRY] = "RT"

        symbols[NO_CONTENT] = "NC"
        symbols[SMALL_CONTENT] = "SC"
        symbols[MISS_FIELD] = "MF"
        symbols[TEMP_MOVED] = "TM"
        symbols[UNKNOWN] = "U"
    }
}
