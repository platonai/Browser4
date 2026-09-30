package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.skeleton.session.PulsarSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class DefaultControllerTest {

    @Test
    fun `statusPanel redirects to status html page`() {
        val session = Mockito.mock(PulsarSession::class.java)
        val controller = DefaultController(session)

        assertEquals("redirect:/status.html", controller.statusPanel())
    }
}
