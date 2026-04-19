package ai.platon.pulsar.rest.api.common

import ai.platon.pulsar.boot.autoconfigure.test.PulsarTestContextInitializer
import ai.platon.pulsar.rest.api.config.MockEcServerConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import org.springframework.web.client.RestTemplate

/**
 * Base test class that automatically starts the mock EC server before tests run.
 * Tests that need to use mock EC server URLs should extend this class.
 */
@Tag("IntegrationTest")
@SpringBootTest
@ContextConfiguration(initializers = [PulsarTestContextInitializer::class])
@Import(MockEcServerConfiguration::class, MockEcServerTestBase.RestTemplateTestConfiguration::class)
abstract class MockEcServerTestBase {

    @TestConfiguration
    class RestTemplateTestConfiguration {
        @Bean
        fun restTemplate(): RestTemplate = RestTemplate()
    }

    @Autowired
    protected lateinit var restTemplate: RestTemplate

    protected val mockServerBaseUrl = "http://localhost:18080"

    // Common mock URLs for testing
    protected val mockProductListUrl = "$mockServerBaseUrl/ec/b?node=1292115012"
    protected val mockProductDetailUrl = "$mockServerBaseUrl/ec/dp/B0E000001"

    @BeforeEach
    open fun setup() {
        // Verify mock server is running
        try {
            val response = restTemplate.getForEntity("$mockServerBaseUrl/ec/", String::class.java)
            if (response.statusCode.isError) {
                throw RuntimeException("Mock EC server is not responding properly: ${response.statusCode}")
            }
        } catch (e: Exception) {
            throw RuntimeException("Mock EC server is not running. Make sure MockEcServerConfiguration is imported.", e)
        }
    }
}
