package ai.platon.pulsar.skeleton.crawl.fetch.driver

import ai.platon.pulsar.common.browser.BrowserErrorCode

open class WebDriverException(
    message: String? = null,
    cause: Throwable? = null,
    val driver: WebDriver? = null,
    val code: Long = -1
): RuntimeException(message, cause)

open class WebDriverCancellationException(
    message: String? = null,
    cause: Throwable? = null,
    driver: WebDriver? = null,
): WebDriverException(message, cause, driver)

open class IllegalWebDriverStateException(
    message: String? = null,
    cause: Throwable? = null,
    driver: WebDriver? = null,
): WebDriverException(message, cause, driver)

open class BrowserUnavailableException(
    message: String? = null,
    cause: Throwable? = null
): IllegalWebDriverStateException(message, cause)

open class BrowserLaunchException(
    message: String? = null,
    cause: Throwable? = null
): BrowserUnavailableException(message, cause)

open class BrowserErrorPageException(
    errorCode: BrowserErrorCode,
    message: String? = null,
    pageContent: String? = null,
    cause: Throwable? = null
): RuntimeException(message, cause)
