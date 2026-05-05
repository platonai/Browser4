package ai.platon.browser4.driver.common

import ai.platon.pulsar.common.getLogger

object B4JsUtils {
    private val logger = getLogger(this)

    /**
     * Convert any given JS snippet to an evaluable expression compatible with Chrome DevTools Protocol.
     *
     * Notes:
     * - Leading `return` keyword is stripped so the snippet can be used as an expression.
     * - Single-line: returns the trimmed line, normalizing function/arrow/object literals via [toIIFEOrNull] when needed.
     * - Multi-line: attempts to wrap as IIFE if it looks like a function/arrow/object literal; otherwise returns original.
     */
    fun toCDPCompatibleExpression(script: String): String {
        // Remove a leading `return` keyword (with or without a following space/parenthesis).
        val stripped = script.replaceFirst(RETURN_PREFIX_REGEX, "")

        val lines = stripped.split('\n').map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""

        return if (lines.size == 1) {
            val line = lines[0]
            toIIFEOrNull(line) ?: line
        } else {
            toIIFEOrNull(stripped) ?: stripped
        }
    }

    /**
     * Convert any given JS snippet to an evaluable expression.
     * - Leading `return` keyword is stripped for consistency with [toCDPCompatibleExpression].
     * - Single-line: returns as-is (trimmed) for quick eval.
     * - Multi-line: attempts to wrap as IIFE if it looks like a function/arrow/object literal; otherwise returns original.
     */
    fun toExpression(script: String): String {
        val stripped = script.replaceFirst(RETURN_PREFIX_REGEX, "")
        val lines = stripped.split('\n').map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return ""
        if (lines.size == 1) return lines[0]
        return toIIFEOrNull(stripped) ?: stripped
    }

    /**
     * Convert a JS function/arrow/object literal to IIFE (Immediately Invoked Function Expression).
     * Returns empty string if it cannot be converted; a warning is logged in that case.
     */
    fun toIIFE(jsFunctionCode: String, args: String = ""): String {
        val result = toIIFEOrNull(jsFunctionCode, args)
        if (result == null) {
            logger.warn("Cannot convert to IIFE, not a recognizable function/arrow/object literal: {}",
                jsFunctionCode.take(MAX_LOG_SNIPPET_LENGTH))
        }
        return result ?: ""
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

        // Arrow function: only match when the expression itself starts as a bare arrow (e.g. `x => expr`).
        // A plain substring check `"=>" in trimmed` would false-positive on `const fn = x => x`.
        // Parenthesized and async arrows (e.g. `(x) => ...`, `async x => ...`) are caught below.
        if (SIMPLE_ARROW_REGEX.containsMatchIn(trimmed)) {
            return "(${trimmed})(${args});"
        }

        // Paren-wrapped object literal e.g. ({ a: 1 })
        if (isParenWrappedObjectLiteral(trimmed)) {
            return ensureSemicolon(trimmed)
        }

        // Raw object literal (distinguished from a code block by heuristic): produce expression, not invocation.
        if (trimmed.startsWith("{") && isObjectLiteralHeuristic(trimmed)) {
            return "(${trimmed});"
        }

        // Function expressions or groupings that should be invoked.
        // Only invoke `(expr)` when the content looks callable (contains a function, arrow, or async expression).
        // Plain parenthesised expressions like `(document.title)` must not be invoked.
        if (trimmed.startsWith("function") || ASYNC_CALLABLE_REGEX.containsMatchIn(trimmed)) {
            return "(${trimmed})(${args});"
        }
        if (trimmed.startsWith("(") &&
            (trimmed.contains("function") || trimmed.contains("=>") || trimmed.startsWith("(async"))) {
            return "(${trimmed})(${args});"
        }
        if (trimmed.startsWith("(")) {
            return ensureSemicolon(trimmed)
        }

        return null
    }

    // Heuristic: detect an already-invoked IIFE like: ( ... ) ( ... ) with optional trailing semicolon.
    // Uses a balanced-parenthesis scan instead of a regex to avoid catastrophic backtracking on
    // deeply nested or malformed input.
    private fun isAlreadyInvokedIIFE(code: String): Boolean {
        if (!code.startsWith("(")) return false
        var depth = 0
        for (i in code.indices) {
            when (code[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        // The main group closed — check that what follows (ignoring whitespace) is `(`
                        val rest = code.substring(i + 1).trimStart()
                        return rest.startsWith("(")
                    }
                }
            }
        }
        return false
    }

    private fun isParenWrappedObjectLiteral(code: String): Boolean {
        return PAREN_OBJECT_REGEX.matches(code)
    }

    /**
     * Heuristic to distinguish a raw object literal `{ key: value }` from a code block `{ stmt; }`.
     * A code block contains semicolons or statement keywords; an object literal has `key: value` pairs.
     */
    private fun isObjectLiteralHeuristic(code: String): Boolean {
        val inner = code.removePrefix("{").removeSuffix("}").trim()
        // Semicolons indicate statements, not object properties.
        if (';' in inner) return false
        // Statement-level keywords indicate a code block.
        if (BLOCK_KEYWORD_REGEX.containsMatchIn(inner)) return false
        // At least one `identifier:` property must be present.
        return OBJECT_KEY_VALUE_REGEX.containsMatchIn(inner)
    }

    private fun ensureSemicolon(s: String): String = if (s.trimEnd().endsWith(';')) s else "$s;"

    // Maximum number of characters from a code snippet included in warning log messages.
    private const val MAX_LOG_SNIPPET_LENGTH = 80

    // Matches a leading `return` keyword followed by optional whitespace (handles both `return expr`
    // and `return(expr)` without consuming the opening parenthesis).
    private val RETURN_PREFIX_REGEX = Regex("^\\s*return\\b\\s*")

    // Matches a bare single-identifier arrow function at the start of the expression, e.g. `x =>`.
    // This deliberately excludes `const fn = x => x` because it starts with `const`, not `x =>`.
    private val SIMPLE_ARROW_REGEX = Regex("^\\w+\\s*=>")

    // Matches real async callable syntax, such as `async () => ...`, `async x => ...`,
    // or `async function() { ... }`, while excluding async-prefixed identifiers like `asyncOperation()`.
    private val ASYNC_CALLABLE_REGEX = Regex("^async(?:\\s+function\\b|\\s+\\([^)]*\\)\\s*=>|\\s+\\w+\\s*=>|\\s+\\()")

    private val PAREN_OBJECT_REGEX = Regex("^\\s*\\(\\s*\\{.*}\\s*\\)\\s*;?\\s*$", RegexOption.DOT_MATCHES_ALL)

    // Statement keywords that unambiguously identify a code block (not an object literal).
    private val BLOCK_KEYWORD_REGEX = Regex("\\b(return|const|let|var|if|for|while|throw|function)\\b")

    // At least one `identifier:` property pattern, the hallmark of an object literal.
    private val OBJECT_KEY_VALUE_REGEX = Regex("\\w+\\s*:")
}
