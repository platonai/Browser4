package ai.platon.pulsar.rest.api.webdriver.controller

import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Controller to serve the OpenAPI specification file.
 */
@RestController
@CrossOrigin
class OpenApiController {

    private val logger = LoggerFactory.getLogger(OpenApiController::class.java)

    /**
     * Serves the OpenAPI specification YAML file.
     */
    @GetMapping(
        "/openapi.yaml",
        produces = ["application/x-yaml", "text/yaml", MediaType.TEXT_PLAIN_VALUE]
    )
    fun getOpenApiSpec(): ResponseEntity<String> {
        logger.debug("Serving OpenAPI specification")
        return try {
            val resource = ClassPathResource("static/openapi.yaml")
            val content = resource.inputStream.bufferedReader().use { it.readText() }
            ResponseEntity.ok(content)
        } catch (e: Exception) {
            logger.error("Failed to read OpenAPI specification", e)
            ResponseEntity.notFound().build()
        }
    }
}
