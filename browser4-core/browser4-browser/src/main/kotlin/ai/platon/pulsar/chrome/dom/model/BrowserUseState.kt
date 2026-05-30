package ai.platon.pulsar.chrome.dom.model

data class BrowserUseState(
    val browserState: BrowserState,
    val domState: DOMState
) {
    fun getAllInteractiveElements(): InteractiveDOMTreeNodeList {
        return domState.serializableTree.buildInteractiveNodeList()
    }

    fun getInteractiveElements(): InteractiveDOMTreeNodeList {
        // The 1-based viewport to see.
        val scrollState = browserState.scrollState

        // The 1-based viewport to see.
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
