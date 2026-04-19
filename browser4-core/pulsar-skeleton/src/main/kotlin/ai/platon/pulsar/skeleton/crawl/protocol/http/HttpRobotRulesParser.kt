package ai.platon.pulsar.skeleton.crawl.protocol.http

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.skeleton.crawl.protocol.Protocol
import ai.platon.pulsar.skeleton.crawl.protocol.Response
import ai.platon.pulsar.skeleton.crawl.protocol.RobotRulesParser
import crawlercommons.robots.BaseRobotRules
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URL
import java.util.*

/**
 * This class is used for parsing robots for urls belonging to HTTP protocol. It
 * extends the generic [RobotRulesParser] class and contains Http protocol
 * specific implementation for obtaining the robots file.
 */
open class HttpRobotRulesParser(
    conf: ImmutableConfig
) : RobotRulesParser(conf) {
    private val allowForbidden = conf.getBoolean("http.robots.403.allow", false)

    /**
     * Get the rules from robots.txt which applies for the given `url`.
     * Robot rules are cached for a unique combination of host, protocol, and
     * port. If no rules are found in the cache, a HTTP request is send to fetch
     * {{protocol://host:port/robots.txt}}. The robots.txt is then parsed and the
     * rules are cached to avoid re-fetching and re-parsing it again.
     *
     * @param protocol The [Protocol] object
     * @param url  URL robots.txt applies to
     * @return [BaseRobotRules] holding the rules from robots.txt
     */
    override fun getRobotRulesSet(protocol: Protocol, url: URL): BaseRobotRules {
        val volatileConfig = conf.toVolatileConfig()
        val cacheKey = getCacheKey(url)
        var robotRules = CACHE[cacheKey]
        var cacheRule = true
        if (robotRules == null) { // cache miss
            var redir: URL? = null
            if (LOG.isTraceEnabled) {
                LOG.trace("cache miss $url")
            }

            try {
                val http = (protocol as? AbstractHttpProtocol) ?: return EMPTY_RULES
                val page = GoraWebPage.newWebPage(url.toURI().resolve("/robots.txt").toString(), volatileConfig)
                var response: Response? = http.getResponse(page, true) ?: return EMPTY_RULES

                // try one level of redirection ?
                if (response != null && (response.httpCode == 301 || response.httpCode == 302)) {
                    var redirection = response.getHeader("Location")
                    if (redirection == null) { // some versions of MS IIS are known to mangle this header
                        redirection = response.getHeader("location")
                    }
                    if (redirection != null) {
                        redir = URI.create(redirection).toURL()
                        response = http.getResponse(GoraWebPage.newWebPage(redir.toString(), volatileConfig), true)
                    }
                }

                val content = response?.pageDatum?.content
                if (response != null && content != null) {
                    if (response.httpCode == 200) // found rules: parse them
                        robotRules = parseRules(
                            url.toString(), content, response.getHeader("Content-Type") ?: "", agentNames
                        ) else if (response.httpCode == 403 && !allowForbidden) robotRules =
                        FORBID_ALL_RULES // use forbid all
                    else if (response.httpCode >= 500) {
                        cacheRule = false
                        robotRules = EMPTY_RULES
                    } else {
                        robotRules = EMPTY_RULES
                    }
                }
            } catch (t: Throwable) {
                if (LOG.isInfoEnabled) {
                    LOG.info("Couldn't get robots.txt for $url: $t")
                }
                cacheRule = false
                robotRules = EMPTY_RULES
            }
            if (cacheRule) {
                CACHE[cacheKey] = robotRules // cache rules for host
                if (redir != null && !redir.host.equals(url.host, ignoreCase = true)) {
                    // cache also for the redirected host
                    CACHE[getCacheKey(redir)] = robotRules
                }
            }
        }

        return robotRules ?: EMPTY_RULES
    }

    companion object {
        val LOG = LoggerFactory.getLogger(HttpRobotRulesParser::class.java)

        /**
         * Compose unique key to store and access robot rules in cache for given URL
         */
        protected fun getCacheKey(url: URL): String {
            val protocol = url.protocol.lowercase(Locale.getDefault()) // normalize to lower
            // case
            val host = url.host.lowercase(Locale.getDefault()) // normalize to lower case
            var port = url.port
            if (port == -1) {
                port = url.defaultPort
            }/*
         * Robot rules apply only to host, protocol, and port where robots.txt is
         * hosted (cf. PULSAR-1752). Consequently
         */
            return "$protocol:$host:$port"
        }
    }
}
