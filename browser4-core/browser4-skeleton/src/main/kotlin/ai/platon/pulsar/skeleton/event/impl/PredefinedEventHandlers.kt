package ai.platon.pulsar.skeleton.event.impl

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.skeleton.event.WebPageWebDriverEventHandler
import ai.platon.pulsar.skeleton.workflow.fetch.driver.WebDriver
import ai.platon.pulsar.persist.WebPage
import kotlinx.coroutines.delay
import java.time.Duration

open class LoginHandler(
    val loginUrl: String,
    val usernameSelector: String,
    val username: String,
    val passwordSelector: String,
    val password: String,
    val submitSelector: String,
    val warnUpUrl: String? = null,
    val activateSelector: String? = null,
    val activateTimeout: Duration = Duration.ofMinutes(3),
) : WebPageWebDriverEventHandler() {

    private val logger = getLogger(this)

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override suspend fun invoke(page: WebPage, driver: WebDriver): Any? {
        logger.info("Navigating to login page ... | {}", loginUrl)

        driver.navigate(loginUrl)
        driver.waitForNavigation()
        if (!driver.currentUrl().contains("login")) {
            logger.info("Already logged in")
            return null
        }

        driver.waitUntil { driver.evaluate("document.body.scrollHeight", 0) > 1000 }

        warnUpUrl?.let {
            driver.navigate(it)
            driver.waitForNavigation(timeout = Duration.ofSeconds(10))
        }

        if (!driver.currentUrl().contains("login")) {
            logger.info("Already logged in")
            return null
        }

        if (activateSelector != null) {
            logger.info("Waiting for login panel ... | {}", activateSelector)

            val time = driver.waitForSelector(activateSelector, activateTimeout)
            if (time.isNegative) {
                logger.info("Can not active login panel in {}", activateTimeout)
                return null
            }
            driver.bringToFront()
            driver.click(activateSelector)
        }

        driver.bringToFront()
        driver.type(username, usernameSelector)
        driver.type(password, passwordSelector)
        driver.click(submitSelector, count = 2)

        logger.info("Cookies before login: {}", driver.getCookies())
        driver.waitForNavigation()
        logger.info("Cookies after login: {}", driver.getCookies())

        return null
    }
}

class CloseMaskLayerHandler(
    val closeSelector: String
) : WebPageWebDriverEventHandler() {
    private val logger = getLogger(this)

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override suspend fun invoke(page: WebPage, driver: WebDriver): Any? {
        logger.info("Closing mask layer... | {}", closeSelector)
        var n = 5
        while (n-- > 0 && driver.exists(closeSelector)) {
            driver.bringToFront()
            driver.click(closeSelector)
            delay(1_000)
        }
        return null
    }
}
