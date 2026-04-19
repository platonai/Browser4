package ai.platon.pulsar.skeleton.crawl.protocol.http

import ai.platon.pulsar.persist.ProtocolStatus
import ai.platon.pulsar.persist.ProtocolStatus.Companion.ARG_HTTP_CODE
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes
import ai.platon.pulsar.persist.metadata.ProtocolStatusCodes.REQUEST_TIMEOUT
import org.apache.http.HttpStatus

object ProtocolStatusTranslator {

    fun translateHttpCode(httpCode: Int): ProtocolStatus {
        return when (httpCode) {
            200 -> ProtocolStatus.STATUS_SUCCESS
            304 -> ProtocolStatus.STATUS_NOTMODIFIED
            in 300..399 -> {
                // handle redirect
                // some broken servers, such as MS IIS, use lowercase header name...
                val code = when (httpCode) {
                    HttpStatus.SC_MULTIPLE_CHOICES -> ProtocolStatusCodes.MOVED_PERMANENTLY
                    HttpStatus.SC_MOVED_PERMANENTLY, HttpStatus.SC_USE_PROXY -> ProtocolStatusCodes.MOVED_PERMANENTLY
                    HttpStatus.SC_MOVED_TEMPORARILY, HttpStatus.SC_SEE_OTHER, HttpStatus.SC_TEMPORARY_REDIRECT -> ProtocolStatusCodes.MOVED_TEMPORARILY
                    else -> ProtocolStatusCodes.MOVED_PERMANENTLY
                }
                // handle redirection in the higher layer.
                // page.getMetadata().set(ARG_REDIRECT_TO_URL, url.toString());
                ProtocolStatus.failed(code, ARG_HTTP_CODE, httpCode)
            }
            HttpStatus.SC_BAD_REQUEST -> {
                ProtocolStatus.failed(ProtocolStatusCodes.GONE, ARG_HTTP_CODE, httpCode)
            }
            HttpStatus.SC_UNAUTHORIZED -> { // requires authorization, but no valid auth provided.
                ProtocolStatus.failed(ProtocolStatusCodes.UNAUTHORIZED, ARG_HTTP_CODE, httpCode)
            }
            HttpStatus.SC_NOT_FOUND -> { // GONE
                ProtocolStatus.failed(ProtocolStatusCodes.SC_NOT_FOUND, ARG_HTTP_CODE, httpCode)
            }
            HttpStatus.SC_REQUEST_TIMEOUT -> { // TIMEOUT
                ProtocolStatus.failed(REQUEST_TIMEOUT, ARG_HTTP_CODE, httpCode)
            }
            HttpStatus.SC_GONE -> { // permanently GONE
                ProtocolStatus.failed(ProtocolStatusCodes.GONE, ARG_HTTP_CODE, httpCode)
            }
            else -> {
                ProtocolStatus.failed(ProtocolStatusCodes.EXCEPTION, ARG_HTTP_CODE, httpCode)
            }
        }
    }
}
