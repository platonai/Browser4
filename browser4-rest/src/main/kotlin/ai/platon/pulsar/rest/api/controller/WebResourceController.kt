package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.entities.W3DocumentRequest
import ai.platon.pulsar.rest.api.service.LoadService
import org.springframework.web.bind.annotation.*

/**
 * Controller for web resource retrieval — raw page content and parsed documents.
 *
 * Merged from [W3DocController] and [W3PageController].
 */
@RestController
@CrossOrigin
class WebResourceController(
    val loadService: LoadService
) {
    @GetMapping("api/w3doc")
    suspend fun getDocument(request: W3DocumentRequest): String {
        val (page, document) = loadService.loadDocument(request.url, request.args)
        return document.prettyHtml
    }

    @GetMapping("api/w3page")
    suspend fun loadContent(@RequestParam url: String): String {
        return loadService.load(url).contentAsString
    }
}
