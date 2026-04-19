
package ai.platon.pulsar.skeleton.crawl.protocol.http

import ai.platon.pulsar.skeleton.crawl.protocol.ProtocolException

open class HttpException : ProtocolException {
    constructor() : super() {}
    constructor(message: String) : super(message) {}
    constructor(message: String, cause: Throwable) : super(message, cause) {}
    constructor(cause: Throwable) : super(cause) {}
}
