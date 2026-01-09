package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.entities.W3DocumentRequest
import ai.platon.pulsar.rest.api.service.LoadService
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The controller to handle www resources
 * */
@RestController
@CrossOrigin
@RequestMapping("api/w3doc")
class W3DocController(
    val loadService: LoadService
) {
    @GetMapping
    fun get(request: W3DocumentRequest): String {
        val (page, document) = loadService.loadDocument(request.url, request.args)
        return document.prettyHtml
    }
}
