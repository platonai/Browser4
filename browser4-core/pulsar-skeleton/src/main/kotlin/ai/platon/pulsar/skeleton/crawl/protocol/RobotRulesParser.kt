package ai.platon.pulsar.skeleton.crawl.protocol

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.skeleton.crawl.common.LazyConfigurable
import com.google.common.io.Files
import crawlercommons.robots.BaseRobotRules
import crawlercommons.robots.SimpleRobotRules
import crawlercommons.robots.SimpleRobotRules.RobotRulesMode
import crawlercommons.robots.SimpleRobotRulesParser
import org.jetbrains.annotations.NotNull
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileReader
import java.io.LineNumberReader
import java.net.URI
import java.net.URL
import java.util.*
import kotlin.system.exitProcess

/**
 * This class uses crawler-commons for handling the parsing of `robots.txt` files. It emits SimpleRobotRules objects,
 * which describe the download permissions as described in SimpleRobotRulesParser.
 */
abstract class RobotRulesParser(
    override var conf: ImmutableConfig
) : LazyConfigurable {
    protected lateinit var agentNames: String

    override fun configure(conf1: ImmutableConfig) {
        this.conf = conf1

        // Grab the agent names we advertise to robots files.
        val ua = conf["http.agent.name", ""].trim { it <= ' ' }
        if (ua.isEmpty()) {
            // LOG.warn("Agent name not configured!")
        }

        agentNames = ua
        // If there are any other agents specified, append those to the list of agents
        val otherAgents = conf["http.robots.agents"]
        if (otherAgents != null && otherAgents.trim { it <= ' ' }.isNotEmpty()) {
            val tok = StringTokenizer(otherAgents, ",")
            val sb = StringBuilder(agentNames)
            while (tok.hasMoreTokens()) {
                val str = tok.nextToken().trim { it <= ' ' }
                if (str == "*" || str == agentNames) {
                    // skip wildcard "*" or agent name itself
                } else {
                    sb.append(",").append(str)
                }
            }

            agentNames = sb.toString()
        }
    }

    /**
     * Parses the robots content using the [SimpleRobotRulesParser] from
     * crawler commons
     *
     * @param url         A string containing url
     * @param content     Contents of the robots file in a byte array
     * @param contentType The content type of the robots file
     * @param robotName   A string containing all the robots agent names used by parser for
     * matching
     * @return BaseRobotRules object
     */
    @NotNull
    fun parseRules(url: String, content: ByteArray, contentType: String, robotName: String): BaseRobotRules {
        return robotParser.parseContent(url, content, contentType, robotName)
    }

    @NotNull
    fun getRobotRulesSet(protocol: Protocol, url: String): BaseRobotRules {
        val u = try {
            URI.create(url).toURL()
        } catch (e: Exception) {
            return EMPTY_RULES
        }
        return getRobotRulesSet(protocol, u)
    }

    @NotNull
    abstract fun getRobotRulesSet(protocol: Protocol, url: URL): BaseRobotRules

    companion object {
        val LOG = LoggerFactory.getLogger(RobotRulesParser::class.java)
        /**
         * A [BaseRobotRules] object appropriate for use when the
         * `robots.txt` file is empty or missing; all requests are allowed.
         */
        @JvmField
        val EMPTY_RULES: BaseRobotRules = SimpleRobotRules(RobotRulesMode.ALLOW_ALL)
        val CACHE = Hashtable<String, BaseRobotRules>()
        /**
         * A [BaseRobotRules] object appropriate for use when the
         * `robots.txt` file is not fetched due to a `403/Forbidden`
         * response; all requests are disallowed.
         */
        var FORBID_ALL_RULES: BaseRobotRules = SimpleRobotRules(RobotRulesMode.ALLOW_NONE)
        private val robotParser = SimpleRobotRulesParser()
        /**
         * command-line main for testing
         */
        @JvmStatic
        fun main(argv: Array<String>) {
            if (argv.size != 3) {
                System.err.println("Usage: RobotRulesParser <robots-file> <url-file> <agent-names>\n")
                System.err.println("    <robots-file> - Input robots.txt file which will be parsed.")
                System.err.println("    <url-file>    - Contains input URLs (1 per line) which are tested against the rules.")
                System.err.println("    <agent-names> - Input agent names. Multiple agent names can be provided using")
                System.err.println("                    comma as a delimiter without any spaces.")
                exitProcess(-1)
            }

            try {
                val robotsBytes = Files.toByteArray(File(argv[0]))
                val rules = robotParser.parseContent(argv[0], robotsBytes,
                        "text/plain", argv[2])
                val testsIn = LineNumberReader(FileReader(argv[1]))
                var testPath: String? = testsIn.readLine().trim { it <= ' ' }
                while (testPath != null) {
                    println((if (rules.isAllowed(testPath)) "allowed" else "not allowed") + ":\t" + testPath)
                    testPath = testsIn.readLine()
                }
                testsIn.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
