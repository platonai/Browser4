package ai.platon.pulsar.rest.api.webdriver.controller

import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
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
     * Serve the OpenAPI YAML specification.
     */
    @GetMapping("/openapi.yaml", produces = ["text/yaml", "application/x-yaml"])
    fun getOpenApiYaml(): ResponseEntity<String> {
        logger.debug("Serving OpenAPI YAML specification")
        return try {
            val resource = ClassPathResource("openapi/openapi.yaml")
            val content = resource.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/yaml"))
                .body(content)
        } catch (e: Exception) {
            logger.error("Failed to load OpenAPI specification", e)
            ResponseEntity.notFound().build()
        }
    }
}
