package ai.platon.pulsar.rest.mcp.controller

object MCPConstants {
    const val ERROR_NO_ACTIVE_SESSION = """No active session. Run "browser4-cli open" first."""
    const val ERROR_SESSION_NOT_FOUND = "Session not found: "
    const val ERROR_MISSING_OP = "Missing 'op' in batch step"
    const val ERROR_MISSING_TOOL = "Batch tool step is missing 'tool'."
    const val ERROR_MISSING_KEY = "Batch press step is missing 'key'."
    const val ERROR_UNSUPPORTED_OP = "Unsupported batch step op: "
    const val ERROR_BATCH_NON_DOM_OP = "Batch command only supports DOM operations. Op '%s' is not allowed. Please execute open/close operations separately."
    
    const val KEY_OP = "op"
    const val KEY_TOOL = "tool"
    const val KEY_ARGUMENTS = "arguments"
    const val KEY_SELECTOR = "selector"
    const val KEY_REF = "ref"
    const val KEY_SESSION_ID = "sessionId"
    const val KEY_KEY = "key"
    
    const val KEY_PRE_FOCUS_SELECTOR = "preFocusSelector"
    const val KEY_PRE_MOUSE_POSITION = "preMousePosition"
    
    const val OP_OPEN = "open"
    const val OP_CLOSE = "close"
    const val OP_TOOL = "tool"
    const val OP_SNAPSHOT = "snapshot"
    const val OP_SCREENSHOT = "screenshot"
    const val OP_PDF = "pdf"
    
    const val TOOL_PAGE_URL = "page_url"
    const val TOOL_PAGE_TITLE = "page_title"
    const val TOOL_BROWSER_EVALUATE = "browser_evaluate"
    
    const val SESSION_OPENED_PREFIX = "Session opened: "
    const val SESSION_ALREADY_OPEN_PREFIX = "Session already open: "
    const val SESSION_CLOSED = "Session closed."
}