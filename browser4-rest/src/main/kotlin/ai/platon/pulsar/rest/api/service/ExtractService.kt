package ai.platon.pulsar.rest.api.service

import ai.platon.browser4.common.B4Constants.SWARM_SESSION_ID
import ai.platon.pulsar.agentic.tools.advanced.crawl.common.DEFAULT_INTRODUCE
import ai.platon.pulsar.common.SessionManager
import ai.platon.pulsar.rest.api.entities.PromptRequest
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import org.springframework.stereotype.Service

@Service
class ExtractService(
    val sessionManager: SessionManager,
    val loadService: LoadService,
) {
    val session get() = sessionManager.getOrCreateSession(SWARM_SESSION_ID).agenticSession

    suspend fun extract(request: PromptRequest): String {
        val prompt = request.prompt
        if (prompt.isNullOrBlank()) {
            return DEFAULT_INTRODUCE
        }

        request.args = LoadOptions.mergeArgs(request.args, "-refresh")
        val (page, document) = loadService.loadDocument(request)

        val prompt2 = """
            Extract the following information from the web page:
            $prompt

            ${document.text}

            """.trimIndent()

        return if (page.protocolStatus.isSuccess) {
            session.chat(prompt2).content
        } else {
            // Throw?
            page.protocolStatus.toString()
        }
    }
}
