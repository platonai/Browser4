package ai.platon.pulsar.rest.api.controller.webdriver

import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets

/**
 * Controller to serve the OpenAPI specification file.
 */
@RestController
@CrossOrigin
class OpenApiController {
    
    private val logger = LoggerFactory.getLogger(OpenApiController::class.java)

    /**
     * Serve the OpenAPI specification file.
     * GET /openapi.yaml
     */
    @GetMapping(
        "/openapi.yaml",
        produces = ["application/x-yaml", "text/yaml", MediaType.TEXT_PLAIN_VALUE]
    )
    fun getOpenApiSpec(): ResponseEntity<String> {
        logger.debug("Serving OpenAPI specification")
        
        return try {
            val resource: Resource = ClassPathResource("openapi/openapi.yaml")
            val content = resource.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            ResponseEntity.ok(content)
        } catch (e: Exception) {
            logger.error("Failed to load OpenAPI spec", e)
            ResponseEntity.notFound().build()
        }
    }

    /**
     * Serve the OpenAPI specification as JSON (optional conversion).
     * GET /openapi.json
     */
    @GetMapping(
        "/openapi.json",
        produces = [MediaType.APPLICATION_JSON_VALUE]
    )
    fun getOpenApiSpecJson(): ResponseEntity<Map<String, Any>> {
        logger.debug("Serving OpenAPI specification as JSON")
        
        // For simplicity, return a redirect hint or basic info
        // Full YAML to JSON conversion would require a YAML parser
        return ResponseEntity.ok(mapOf(
            "message" to "Use /openapi.yaml for the full specification",
            "specUrl" to "/openapi.yaml"
        ))
    }
}
