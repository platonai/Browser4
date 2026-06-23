package ai.platon.pulsar.rest.mcp.controller

/**
 * Browser-side JavaScript scripts used by MCP tool implementations.
 */
const val CLEAR_SESSION_STORAGE_SCRIPT = """
    (() => {
        const result = {
            localStorageCleared: false,
            sessionStorageCleared: false,
            errors: []
        };
        try {
            window.localStorage.clear();
            result.localStorageCleared = true;
        } catch (error) {
            result.errors.push("localStorage: " + error);
        }
        try {
            window.sessionStorage.clear();
            result.sessionStorageCleared = true;
        } catch (error) {
            result.errors.push("sessionStorage: " + error);
        }
        return JSON.stringify(result);
    })()
"""
