package ai.platon.pulsar.rest.mcp.controller

/**
 * Map of client-facing (frontend) tool names to internal canonical tool names.
 */
val FRONTEND_TOOL_NAME_ALIASES: Map<String, String> = mapOf(
    "browser_navigate" to "navigate",
    "browser_snapshot" to "aria_snapshot",
    "browser_navigate_back" to "go_back",
    "browser_navigate_forward" to "go_forward",
    "browser_reload" to "reload",
    "browser_press_key" to "press",
    "browser_press_sequentially" to "type",
    "browser_keydown" to "keydown",
    "browser_keyup" to "keyup",
    "browser_mouse_move_xy" to "mousemove",
    "browser_mouse_down" to "mousedown",
    "browser_mouse_up" to "mouseup",
    "browser_mouse_wheel" to "mousewheel",
    "browser_drag" to "drag",
    "browser_type" to "fill",
    "browser_hover" to "hover",
    "browser_select_option" to "select_option",
    "browser_file_upload" to "upload",
    "browser_check" to "check",
    "browser_uncheck" to "uncheck",
    "browser_evaluate" to "evaluate_value",
    "browser_generate_locator" to "generate_locator",
    "browser_resize" to "resize",
    "browser_take_screenshot" to "screenshot",
    "browser_pdf_save" to "pdf",
    "browser_save_storage_state" to "save_storage_state",
    "browser_load_storage_state" to "load_storage_state",
)

