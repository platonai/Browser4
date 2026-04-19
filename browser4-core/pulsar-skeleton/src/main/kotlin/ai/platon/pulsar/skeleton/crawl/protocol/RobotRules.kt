package ai.platon.pulsar.skeleton.crawl.protocol

import java.net.URL

/**
 * This class holds the rules which were parsed from a robots.txt file, and can
 * test paths against those rules.
 */
interface RobotRules {
    /**
     * Get expire time
     */
    val expireTime: Long

    /**
     * Get Crawl-Delay, in milliseconds. This returns -1 if not set.
     */
    val crawlDelay: Long

    /**
     * Returns `false` if the `robots.txt` file prohibits us
     * from accessing the given `url`, or `true` otherwise.
     */
    fun isAllowed(url: URL): Boolean
}
