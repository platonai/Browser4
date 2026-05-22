package ai.platon.pulsar.common

import java.util.regex.Pattern

open class UrlExtractor {
    companion object {
        val URL_PATTERN: Pattern = Patterns.AUTOLINK_WEB_URL
    }

    /**
     * Extract the first url from a line
     * */
    fun extract(line: String): String? {
        val matcher = URL_PATTERN.matcher(line)
        while (matcher.find()) {
            val start = matcher.start(1)
            val end = matcher.end()
            return line.substring(start, end)
        }
        return null
    }

    /**
     * Extract the first url from a line
     * */
    fun extractAll(line: String): MutableSet<String> {
        val urls = mutableSetOf<String>()
        extractTo(line, urls)
        return urls
    }

    /**
     * Extract the first url from a line
     * */
    fun extractAll(line: String, filter: (String) -> Boolean): Set<String> {
        val urls = mutableSetOf<String>()
        extractTo(line, urls, filter)
        return urls
    }

    /**
     * Extract all urls from a line
     * */
    fun extractTo(line: String, urls: MutableSet<String>) {
        val matcher = URL_PATTERN.matcher(line)
        while (matcher.find()) {
            val start = matcher.start(1)
            val end = matcher.end()
            urls.add(line.substring(start, end))
        }
    }

    /**
     * Extract all urls from a line
     * */
    fun extractTo(line: String, urls: MutableSet<String>, filter: (String) -> Boolean) {
        val matcher = URL_PATTERN.matcher(line)
        while (matcher.find()) {
            val start = matcher.start(1)
            val end = matcher.end()
            val url = line.substring(start, end)
            if (filter(url)) {
                urls.add(url)
            }
        }
    }
}
