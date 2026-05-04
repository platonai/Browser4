package ai.platon.browser4.driver.common

object B4JsUtils {

    /**
     * Convert any given JS snippet to an evaluable expression compatible with Chrome DevTools Protocol.
     *
     * Notes:
     * - Single-line: returns the trimmed line, but normalizes function/arrow/object literals via [toIIFEOrNull] when needed.
     * - Multi-line: attempts to wrap as IIFE if it looks like a function/arrow/object literal; otherwise returns original.
     */
    fun toCDPCompatibleExpression(script: String): String {
        // remove the heading `\s*return `
        val script = script.replaceFirst(Regex("^\\s*return\\s+"), "")

        val lines = script.split('\n').map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""

        return if (lines.size == 1) {
            val line = lines[0]
            toIIFEOrNull(line) ?: line
        } else {
            toIIFEOrNull(script) ?: script
        }
    }

    /**
     * Convert any given JS snippet to an evaluable expression.
     * - Single-line: returns as-is (trimmed) for quick eval.
     * - Multi-line: attempts to wrap as IIFE if it looks like a function/arrow/object literal; otherwise returns original.
     */
    fun toExpression(script: String): String {
        val lines = script.split('\n').map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size == 1) return lines[0]
        return toIIFEOrNull(script) ?: script
    }

    /**
     * Convert a JS function/arrow/object literal to IIFE (Immediately Invoked Function Expression).
     * Returns empty string if it cannot be converted.
     */
    fun toIIFE(jsFunctionCode: String, args: String = ""): String {
        return toIIFEOrNull(jsFunctionCode, args) ?: ""
    }

    /**
     * Convert a JS function/arrow/object literal to IIFE (Immediately Invoked Function Expression).
     * Returns null if the input doesn't look like a convertible function-like snippet.
     */
    fun toIIFEOrNull(jsFunctionCode: String, args: String = ""): String? {
        val trimmed = jsFunctionCode.trim { it.isWhitespace() || it == ';' }
        if (trimmed.isEmpty()) return null

        // Already-invoked IIFE
        if (isAlreadyInvokedIIFE(trimmed)) {
            return ensureSemicolon(trimmed)
        }

        // Arrow function
        if ("=>" in trimmed) {
            return "(${trimmed})(${args});"
        }

        // Paren-wrapped object literal e.g. ({ a: 1 })
        if (isParenWrappedObjectLiteral(trimmed)) {
            return ensureSemicolon(trimmed)
        }

        // Raw object literal: produce expression, not invocation
        if (trimmed.startsWith("{")) {
            return "(${trimmed});"
        }

        // Function expressions or groupings that should be invoked
        if (
            trimmed.startsWith("function") ||
            trimmed.startsWith("(") ||
            trimmed.startsWith("async")
        ) {
            return "(${trimmed})(${args});"
        }

        return null
    }

    // Heuristic: detect an already-invoked IIFE like: ( ... ) ( ... ) with optional trailing semicolon
    private fun isAlreadyInvokedIIFE(code: String): Boolean {
        if (!code.startsWith("(")) return false
        return ")(" in code || IIFE_REGEX.matches(code)
    }

    private fun isParenWrappedObjectLiteral(code: String): Boolean {
        return PAREN_OBJECT_REGEX.matches(code)
    }

    private fun ensureSemicolon(s: String): String = if (s.trimEnd().endsWith(';')) s else "$s;"

    private val IIFE_REGEX = Regex("^\\s*\\(.*\\)\\s*\\([^)]*\\)\\s*;?\\s*$", RegexOption.DOT_MATCHES_ALL)
    private val PAREN_OBJECT_REGEX = Regex("^\\s*\\(\\s*\\{.*}\\s*\\)\\s*;?\\s*$", RegexOption.DOT_MATCHES_ALL)
}
