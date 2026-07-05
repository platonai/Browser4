package ai.platon.pulsar.browser.common

import ai.platon.pulsar.common.ResourceLoader
import org.apache.commons.lang3.SystemUtils
import kotlin.random.Random

class UserAgent {
    // Available user agents
    private val userAgents = mutableListOf<String>()

    /**
     * Generate a random user agent
     * */
    fun getRandomUserAgent(): String {
        if (userAgents.isEmpty()) {
            loadUserAgents()
        }

        if (userAgents.isNotEmpty()) {
            return userAgents[Random.nextInt(userAgents.size)]
        }

        return ""
    }

    /**
     * Generate a random user agent,
     * also see <a href='https://github.com/arouel/uadetector'>uadetector</a>
     * */
    fun loadUserAgents() {
        if (userAgents.isNotEmpty()) return

        var cua = ResourceLoader.readAllLines("ua/chrome-user-agents.txt")
            .filter { it.startsWith("Mozilla/5.0") }
        if (SystemUtils.IS_OS_LINUX) {
            cua = cua.filter { it.contains("X11") }
        } else if (SystemUtils.IS_OS_WINDOWS) {
            cua = cua.filter { it.contains("Windows") }
        }

        cua.toCollection(userAgents)
    }
}
