package ai.platon.pulsar.rest.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Configures Spring MVC async support.
 *
 * The default timeout is configurable via `browser4.async.timeout-seconds`
 * (default 600s = 10 min) because long-running tool calls (scrape, crawl,
 * pptx generation) can exceed the previous hard-coded 5-minute limit and get
 * killed with a raw 503.
 *
 * Set the property to `-1` to disable the timeout entirely (passes `null`
 * to [AsyncSupportConfigurer.setDefaultTimeout]).
 */
@Configuration
class WebMvcAsyncConfig(
    @Value("\${browser4.async.timeout-seconds:600}") private val asyncTimeoutSeconds: Long,
) : WebMvcConfigurer {

    override fun configureAsyncSupport(configurer: AsyncSupportConfigurer) {
        if (asyncTimeoutSeconds > 0) {
            configurer.setDefaultTimeout(asyncTimeoutSeconds * 1000L)
        } else {
            // -1 (or any non-positive value) means "no timeout"; Spring's
            // setDefaultTimeout takes a primitive long, so we use Long.MAX_VALUE
            // (≈292 million years) as the practical "no limit" sentinel.
            configurer.setDefaultTimeout(Long.MAX_VALUE)
        }
    }
}
