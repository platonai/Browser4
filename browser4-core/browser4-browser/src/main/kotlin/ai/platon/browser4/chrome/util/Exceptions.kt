package ai.platon.browser4.chrome.util

open class ChromeDriverException(
    message: String,
    cause: Throwable? = null,
): RuntimeException(message, cause)

/**
 * Thrown when failed to launch Chrome.
 * This is a fatal exception and the browser should be closed.
 * */
open class ChromeLaunchException(message: String, cause: Throwable? = null): ChromeDriverException(message, cause)

open class ChromeProtocolException(
    message: String,
    cause: Throwable? = null,
): ChromeDriverException(message, cause)

/**
 * The return message itself is an error message, for example:
 *
 * - code: -32000 message: "Could not find node with given id"
 * */
open class CDPReturnError(
    val errorCode: Long = 0,
    val errorData: String? = null,
    val errorMessage: String? = null,
    message: String
): ChromeDriverException(message, null)

/**
 * Thrown when the connection to Chrome fails.
 * This is a fatal exception and the browser should be closed.
 * */
open class ChromeIOException(
    message: String,
    cause: Throwable? = null,
    var isOpen: Boolean = true,
) : ChromeProtocolException(message, cause)

open class ChromeServiceException : ChromeProtocolException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable): super(message, cause)
}

open class ChromeRPCException : ChromeProtocolException {
    var code = -1L
    var url: String? = null

    constructor(message: String) : super(message)

    constructor(code: Long, message: String) : super(message) {
        this.code = code
    }

    constructor(message: String, cause: Throwable) : super(message, cause)
}

open class ChromeRPCTimeoutException : ChromeRPCException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable): super(message, cause)
}
