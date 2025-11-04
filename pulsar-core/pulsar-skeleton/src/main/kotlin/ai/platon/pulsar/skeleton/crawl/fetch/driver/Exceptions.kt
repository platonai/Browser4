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

open class EvaluationException(
    message: String? = null,
    cause: Throwable? = null,
    driver: WebDriver? = null,
    val expression: String? = null,
    val args: List<Any?>? = null
): WebDriverException(message, cause, driver)

open class EvaluationScriptException(
    message: String? = null,
    cause: Throwable? = null,
    driver: WebDriver? = null,
    expression: String? = null,
    args: List<Any?>? = null
): EvaluationException(message, cause, driver, expression, args)

open class EvaluationSerializationException(
    message: String? = null,
    cause: Throwable? = null,
    driver: WebDriver? = null,
    expression: String? = null,
    args: List<Any?>? = null
): EvaluationException(message, cause, driver, expression, args)

open class EvaluationTimeoutException(
    message: String? = null,
    cause: Throwable? = null,
    driver: WebDriver? = null,
    expression: String? = null,
    args: List<Any?>? = null
): EvaluationException(message, cause, driver, expression, args)
