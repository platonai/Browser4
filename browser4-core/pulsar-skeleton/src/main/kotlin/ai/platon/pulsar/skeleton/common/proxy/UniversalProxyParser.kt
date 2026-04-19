package ai.platon.pulsar.skeleton.common.proxy

import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.proxy.ProxyEntry
import ai.platon.pulsar.common.proxy.ProxyParser
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.skeleton.context.PulsarContexts
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.runBlocking
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class UniversalProxyParser : ProxyParser() {
    private val logger = getLogger(this)
    private val session = PulsarContexts.createSession()
    private val prompt = """
Extract proxies from the text, and return them in JSON format:

```json
[
    {
        status: "the status of the response, it can be one of the following: [success, failure]",
        host: "the extracted host, it can be an IP address or a domain name",
        port: "the extracted port, it should be an integer",
        username: "[Optional] the extracted username",
        password: "[Optional] the extracted password",
        type: "[Optional] the proxy type, it can be one of the following: [HTTP, HTTPS, SOCKS, SOCKS4, SOCKS5]",
        expireAt: "[Optional] the expire time of the proxy, keep it as a string",
    },
    {
        status: "...",
        host: "...",
        port: "...",
        username: "[Optional]",
        password: "[Optional]",
        type: "[Optional]",
        expireAt: "[Optional]"
    }
]
```

Your response should contains ONLY the JSON object, and nothing else.

    """.trimIndent()

    override val name: String
        get() = "UniversalProxyParser"

    override fun parse(text: String, format: String): List<ProxyEntry> {
        val response = runBlocking { session.chat(prompt + "\n" + text).content }
        if (response == "LLM not available") {
            logger.warn(response)
            return listOf()
        }

        val jsonText = response.substringAfter("```json").substringBeforeLast("```")

        val proxies = mutableListOf<ProxyEntry>()
        val json = pulsarObjectMapper().readTree(jsonText)
        if (json.isObject) {
            parseProxyEntryFromJSONObject(json, response)?.let { proxies.add(it) }
        } else if (json.isArray) {
            json.filter { it.isObject }.mapNotNullTo(proxies) { parseProxyEntryFromJSONObject(it, response) }
        }

        return proxies
    }

    override fun parse(path: Path, format: String): List<ProxyEntry> {
        if (Files.notExists(path)) {
            return listOf()
        }

        val text = Files.readString(path)
        return parse(text, format)
    }

    private fun parseProxyEntryFromJSONObject(json: JsonNode, response: String): ProxyEntry? {
        require(json.isObject) { "The input should be a JSON object, actual: " + json.nodeType }

        if (json.has("status") && json.has("host") && json.has("port")) {
            val status = json.get("status")?.asText() ?: return null
            val host = json.get("host")?.asText() ?: return null
            val port = json.get("port")?.asText() ?: return null
            val username: String? = json.get("username")?.asText()
            val password: String? = json.get("password")?.asText()
            val type: String? = json.get("type")?.asText()?.uppercase()
            val expireAt: String? = json.get("expireAt")?.asText()
            val declaredTTL = Instant.now() + Duration.ofMinutes(30)

            val type2 = when (type) {
                "HTTP" -> Proxy.Type.HTTP
                "HTTPS" -> Proxy.Type.HTTP
                "SOCKS" -> Proxy.Type.SOCKS
                "SOCKS4" -> Proxy.Type.SOCKS
                "SOCKS5" -> Proxy.Type.SOCKS
                else -> Proxy.Type.SOCKS
            }

            if (status == "success" && Strings.isNumericLike(port)) {
                val proxyEntry = ProxyEntry(host, port.toInt(), username = username, password = password).also {
                    it.type = type2
                    it.declaredTTL = declaredTTL
                }
                return proxyEntry
            } else {
                logger.warn("Invalid proxy entry: $response")
            }
        } else {
            logger.warn("Failed to extract proxy entry: $response")
        }

        return null
    }
}