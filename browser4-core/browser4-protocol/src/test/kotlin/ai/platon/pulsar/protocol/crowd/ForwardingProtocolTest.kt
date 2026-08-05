package ai.platon.pulsar.protocol.crowd

import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.skeleton.workflow.protocol.ForwardingResponse
import ai.platon.pulsar.skeleton.workflow.protocol.crowd.ForwardingProtocol
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("Unit")
@Tag("Fast")
@DisplayName("ForwardingProtocol cache behavior")
class ForwardingProtocolTest {

    private val page = GoraWebPage.NIL

    @Test
    @DisplayName("returns null when cache is empty")
    fun returnsNullWhenCacheEmpty() = runBlocking {
        val protocol = ForwardingProtocol()
        val result = protocol.getResponseDeferred(page, followRedirects = true)
        assertNull(result, "getResponseDeferred should return null when no response was set")
    }

    @Test
    @DisplayName("returns the response that was set for the same URL")
    fun returnsSetResponseForSameUrl() = runBlocking {
        val protocol = ForwardingProtocol()
        val response = ForwardingResponse.canceled(page)
        protocol.setResponse(response)

        val result = protocol.getResponseDeferred(page, followRedirects = true)
        assertNotNull(result, "getResponseDeferred should return the previously set response")
        assertEquals(response, result)
    }

    @Test
    @DisplayName("consumes the response on get (remove semantics)")
    fun consumesResponseOnGet() = runBlocking {
        val protocol = ForwardingProtocol()
        val response = ForwardingResponse.canceled(page)
        protocol.setResponse(response)

        // First get consumes the entry
        val first = protocol.getResponseDeferred(page, followRedirects = true)
        assertNotNull(first)

        // Second get should return null since the entry was removed
        val second = protocol.getResponseDeferred(page, followRedirects = true)
        assertNull(second, "getResponseDeferred should return null after the response was consumed")
    }

    @Test
    @DisplayName("name property is 'forward'")
    fun nameIsForward() {
        val protocol = ForwardingProtocol()
        assertEquals("forward", protocol.name)
    }

    @Test
    @DisplayName("setResponse overwrites previous response for the same URL")
    fun overwritesPreviousResponse() = runBlocking {
        val protocol = ForwardingProtocol()
        val first = ForwardingResponse.canceled(page, "first")
        val second = ForwardingResponse.canceled(page, "second")

        protocol.setResponse(first)
        protocol.setResponse(second)

        val result = protocol.getResponseDeferred(page, followRedirects = true)
        assertNotNull(result)
        // The second response should be the one returned (last-write-wins for same key)
        assertEquals(second, result)
    }
}
