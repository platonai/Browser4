package ai.platon.pulsar.rest.config

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
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

        // -1 = unlimited per the application.properties contract.  Spring's setter
        // takes a primitive long, so "no limit" is the Long.MAX_VALUE sentinel
        // rather than leaving the container's own (much shorter) default in place.
        WebMvcAsyncConfig(asyncTimeoutSeconds = -1).configureAsyncSupport(configurer)

        verify(configurer).setDefaultTimeout(Long.MAX_VALUE)
    }
}
