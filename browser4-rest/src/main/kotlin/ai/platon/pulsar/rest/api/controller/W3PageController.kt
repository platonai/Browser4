package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.service.LoadService
import org.springframework.web.bind.annotation.*

/**
 * The controller to handle www resources
 * */
@RestController
@CrossOrigin
@RequestMapping("api/w3page")
class W3PageController(
    val loadService: LoadService
) {
    @GetMapping
    fun loadContent(@RequestParam url: String): String {
        return loadService.load(url).contentAsString
    }
}
