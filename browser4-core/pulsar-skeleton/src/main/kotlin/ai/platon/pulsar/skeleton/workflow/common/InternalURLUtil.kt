package ai.platon.pulsar.skeleton.workflow.common

import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.common.urls.URLUtils.getURLOrNull
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException

/**
 * Utility class for URL analysis.
 *
 * protocol://username:password@hostname:port/pathname?search#hash
 * -----------------------------href------------------------------
 *                              -----host----
 * -----------      origin      -------------
 *
 * protocol - protocol scheme of the URL, including the final ':'
 * hostname - domain name
 * port - port number
 * pathname - /pathname
 * search - ?parameters
 * hash - #fragment_identifier
 * username - username specified before the domain name
 * password - password specified before the domain name
 * href - the entire URL
 * origin - protocol://hostname:port
 * host - hostname:port
 *
 */
object InternalURLUtil {
    private val logger = LoggerFactory.getLogger(InternalURLUtil::class.java)

    fun getHost(url: String): String? {
        val u = getURLOrNull(url) ?: return null
        return getHost(u, GroupMode.BY_HOST)
    }

    fun getHost(url: String, groupMode: GroupMode): String? {
        val u = getURLOrNull(url) ?: return null
        return getHost(u, groupMode)
    }

    fun getHost(url: URL, groupMode: GroupMode): String? {
        var host: String?
        if (groupMode == GroupMode.BY_IP) {
            host = try {
                val addr = InetAddress.getByName(url.host)
                addr.hostAddress
            } catch (e: UnknownHostException) { // unable to resolve it, so don't fall back to host name
                logger.warn("Unable to resolve: " + url.host + ", skipping.")
                return ""
            }
        } else if (groupMode == GroupMode.BY_DOMAIN) {
            host = URLUtils.getTopPrivateDomain(url)
        } else {
            host = url.host
            if (host == null) {
                logger.warn("Unknown host for url: $url, using URL string as key")
                host = url.toExternalForm()
            }
        }
        return host
    }

    /**
     * @see InternalURLUtil
     */
    enum class GroupMode {
        BY_IP, BY_DOMAIN, BY_HOST
    }
}
