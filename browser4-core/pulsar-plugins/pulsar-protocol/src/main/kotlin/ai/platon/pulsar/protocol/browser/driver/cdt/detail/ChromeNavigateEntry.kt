package ai.platon.pulsar.protocol.browser.driver.cdt.detail

import ai.platon.cdt.kt.protocol.events.network.RequestWillBeSent
import ai.platon.cdt.kt.protocol.events.network.RequestWillBeSentExtraInfo
import ai.platon.cdt.kt.protocol.events.network.ResponseReceived
import ai.platon.cdt.kt.protocol.events.page.FrameNavigated
import ai.platon.cdt.kt.protocol.types.network.ResourceType
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.skeleton.crawl.fetch.driver.NavigateEntry
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class ChromeNavigateEntry(
    private val navigateEntry: NavigateEntry
) {
    private val logger = getLogger(this)

    private val tracer = logger.takeIf { it.isTraceEnabled }

    companion object {
        private val cookieMapper = jacksonObjectMapper()
            .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
    }

    fun updateStateBeforeRequestSent(event: RequestWillBeSent, extraInfo: RequestWillBeSentExtraInfo? = null) {
        updateStateBeforeRequestSent0(event, extraInfo)
    }

    fun updateStateAfterResponseReceived(event: ResponseReceived) {
        updateStateAfterResponseReceived0(event)
    }

    fun updateStateAfterFrameNavigated(event: FrameNavigated) {
        if (event.frame.parentId == null) {
            navigateEntry.mainFrameId = event.frame.id
        }
    }

    /**
     * Check if this is a minor resource.
     *
     * References:
     * * [CDP Resource Check](https://chatgpt.com/s/t_68fcbdcd8c64819180900928074c6ee7)
     * */
    fun isMinorResource(event: RequestWillBeSent): Boolean {
        val type = event.type ?: return true
        return navigateEntry.mainFrameReceived && isMinorResource(type)
    }

    private fun updateStateBeforeRequestSent0(event: RequestWillBeSent, extraInfo: RequestWillBeSentExtraInfo?) {
        val count = navigateEntry.networkRequestCount.incrementAndGet()

        // TODO: handle redirection

        // The first request, it should be the main HTML document
        if (logger.isDebugEnabled && count == 1 && event.type != ResourceType.DOCUMENT) {
            // It might be a redirection, prefetch, or just an image
            var url = event.request.url
            if (url.startsWith("data:")) {
                url = "data:xxx(...ignored)"
            }
            logger.debug(
                "The resource type of the first request is {}, requests: {} | {}",
                event.type, navigateEntry.networkRequestCount, url
            )
        }

        if (isMajorRequestWillBeSent(event)) {
            val headers = mutableMapOf<String, Any>()
            event.request.headers.forEach { (key, value) -> if (value != null) headers[key] = value }
            navigateEntry.updateMainRequest(event.requestId, headers)

            // Extract cookies from extraInfo if available
            extraInfo?.let { info ->
                val cookies = extractCookies(info)
                if (cookies.isNotEmpty()) {
                    navigateEntry.updateMainRequestCookies(cookies)
                }
            }
        }
    }

    private fun extractCookies(extraInfo: RequestWillBeSentExtraInfo): List<Map<String, String>> {
        val cookies = mutableListOf<Map<String, String>>()

        // RequestWillBeSentExtraInfo contains associatedCookies which includes blocked cookies
        // For now, we extract cookies that were actually sent (not blocked)
        extraInfo.associatedCookies.forEach { blockedCookie ->
            // Only include cookies that were not blocked
            if (blockedCookie.blockedReasons.isEmpty()) {
                blockedCookie.cookie.let { cookie ->
                    cookies.add(serializeCookie(cookie))
                }
            }
        }

        return cookies
    }

    private fun serializeCookie(cookie: ai.platon.cdt.kt.protocol.types.network.Cookie): Map<String, String> {
        return cookieMapper.readValue(cookieMapper.writeValueAsString(cookie))
    }

    private fun updateStateAfterResponseReceived0(event: ResponseReceived) {
        val count = navigateEntry.networkResponseCount.incrementAndGet()
        val response = event.response

        // TODO: handle redirection

        // The first response, it should be the main HTML document
        if (logger.isDebugEnabled && count == 1 && event.type != ResourceType.DOCUMENT) {
            var url = response.url
            if (url.startsWith("data:")) {
                url = "data:xxx(...ignored)"
            }
            // It might be a redirection, prefetch, or just an image
            logger.debug("The resource type of the first response is {}, responses: {} | {}",
                event.type, navigateEntry.networkResponseCount, url)
        }

        if (isMajorResponseReceived(event)) {
            tracer?.trace("onResponseReceived | driver, document | {}", event.requestId)
            val headers = mutableMapOf<String, Any>()
            response.headers.forEach { (key, value) -> if (value != null) headers[key] = value }
            navigateEntry.updateMainResponse(response.status, response.statusText, headers)
        }
    }

    private fun isMajorRequestWillBeSent(event: RequestWillBeSent): Boolean {
        return !navigateEntry.mainFrameReceived && event.type == ResourceType.DOCUMENT
    }

    private fun isMajorResponseReceived(event: ResponseReceived): Boolean {
        return !navigateEntry.mainFrameReceived && event.type == ResourceType.DOCUMENT
    }

    private fun isMinorResource(type: ResourceType): Boolean {
        return type in listOf(
            ResourceType.FONT,
            ResourceType.MEDIA,
            ResourceType.IMAGE,
        )
    }
}
