package ai.platon.pulsar.rest.config

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.never
import org.mockito.Mockito.anyLong
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer

class WebMvcAsyncConfigTest {
    @Test
    fun configureAsyncSupportUsesConfiguredTimeoutSeconds() {
        val configurer = mock(AsyncSupportConfigurer::class.java)

        WebMvcAsyncConfig(asyncTimeoutSeconds = 600).configureAsyncSupport(configurer)

        verify(configurer).setDefaultTimeout(600_000L)
    }

    @Test
    fun configureAsyncSupportSkipsTimeoutWhenNonPositive() {
        val configurer = mock(AsyncSupportConfigurer::class.java)

        // -1 = unlimited per application.properties contract
        WebMvcAsyncConfig(asyncTimeoutSeconds = -1).configureAsyncSupport(configurer)

        verify(configurer, never()).setDefaultTimeout(anyLong())
    }
}
