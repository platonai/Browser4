package ai.platon.browser4.api.model

data class BrowserUseState(
    val browserState: BrowserState,
    val domState: DOMState
) {
    fun getAllInteractiveElements(): InteractiveDOMTreeNodeList {
        return domState.serializableTree.buildInteractiveNodeList()
    }

    fun getInteractiveElements(): InteractiveDOMTreeNodeList {
        // The 0-based viewport to see.
        val scrollState = browserState.scrollState

        // The 0-based viewport to see.
        val processingViewport = scrollState.processingViewport
        val viewportsTotal = scrollState.viewportsTotal

        return domState.serializableTree.buildInteractiveNodeList(
            currentViewportIndex = processingViewport, lastViewportIndex = viewportsTotal
        )
    }

    companion object {
        val DUMMY: BrowserUseState = BrowserUseState(
            BrowserState(""),
            DOMState(SerializableDOMTree())
        )
    }
}
