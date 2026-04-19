package ai.platon.pulsar.skeleton.crawl.parse

open class ParseException : Exception {
    constructor() : super() {}
    constructor(message: String) : super(message) {}
    constructor(message: String, cause: Throwable) : super(message, cause) {}
    constructor(cause: Throwable) : super(cause) {}
}

class ParserNotFound : ParseException {
    var url: String? = null
        private set
    var contentType: String? = null
        private set

    constructor(message: String) : super(message)

    @JvmOverloads
    constructor(url: String,
                contentType: String, message: String = "Parser not found for $contentType | $url") : super(message) {
        this.url = url
        this.contentType = contentType
    }

    companion object {
        private const val serialVersionUID = 23993993939L
    }
}
