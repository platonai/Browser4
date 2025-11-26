package ai.platon.pulsar.rest.api.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * SpringDoc OpenAPI configuration.
 * Configures Swagger UI and OpenAPI documentation for the WebDriver-compatible API.
 */
@Configuration
class OpenApiConfig {

    /**
     * Configures the OpenAPI documentation.
     */
    @Bean
    fun customOpenAPI(): OpenAPI {
        return OpenAPI()
            .info(
                Info()
                    .title("Browser4 WebDriver-Compatible API")
                    .description(
                        """
                        A WebDriver-compatible HTTP API with selector-first extensions for browser automation.
                        This API provides session management, navigation, element interactions, and event handling.
                        All responses follow the WebDriver JSON wire protocol format: `{ "value": ... }`.
                        
                        **Note**: This is a mock implementation for testing and development purposes.
                        No real browser integration is included yet.
                        """.trimIndent()
                    )
                    .version("1.0.0")
                    .contact(
                        Contact()
                            .name("Platon AI")
                            .url("https://github.com/platonai/Browser4")
                    )
                    .license(
                        License()
                            .name("Apache 2.0")
                            .url("https://www.apache.org/licenses/LICENSE-2.0")
                    )
            )
            .addServersItem(
                Server()
                    .url("http://localhost:8182")
                    .description("Local development server")
            )
    }
}
