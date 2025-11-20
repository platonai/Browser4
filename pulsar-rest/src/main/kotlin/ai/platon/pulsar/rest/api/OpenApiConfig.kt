package ai.platon.pulsar.rest.api

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.servers.Server
import org.springframework.context.annotation.Configuration

@OpenAPIDefinition(
    info = Info(
        title = "Pulsar REST API",
        version = "v${'$'}{ai.platon.pulsar.version:4.1.0-SNAPSHOT}",
        description = "Browser4 / Pulsar REST endpoints"
    ),
    servers = [
        Server(url = "http://localhost:8182", description = "Local dev"),
        Server(url = "/", description = "Relative")
    ]
)
@Configuration
class OpenApiConfig

