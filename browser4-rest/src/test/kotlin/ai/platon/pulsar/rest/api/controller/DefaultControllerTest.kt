package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.skeleton.session.PulsarSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus

class DefaultControllerTest {

    @Test
    @DisplayName("statusPanel returns a real HTTP 302 redirect to the status.html page")
    fun statusPanelRedirectsToStatusHtmlPage() {
        val session = Mockito.mock(PulsarSession::class.java)
        val controller = DefaultController(session)

        val response = controller.statusPanel()

        assertEquals(HttpStatus.FOUND, response.statusCode)
        assertEquals("/status.html", response.headers.location?.path)
        // The legacy broken behavior returned the literal body "redirect:/status.html";
        // a redirect must have no body.
        assertNull(response.body)
    }
}
