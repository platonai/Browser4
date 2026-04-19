package ai.platon.pulsar.rest.config

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer
import kotlin.time.Duration.Companion.minutes

class WebMvcAsyncConfigTest {
    @Test
    fun configureAsyncSupportSetsFiveMinuteDefaultTimeout() {
        val configurer = mock(AsyncSupportConfigurer::class.java)

        WebMvcAsyncConfig().configureAsyncSupport(configurer)

        verify(configurer).setDefaultTimeout(5.minutes.inWholeMilliseconds)
    }
}
