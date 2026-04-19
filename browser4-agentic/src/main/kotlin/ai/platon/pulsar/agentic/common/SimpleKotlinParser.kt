package ai.platon.pulsar.agentic.common

import ai.platon.pulsar.agentic.model.ToolCall

class SimpleKotlinParser {

    /**
     * Parses a function call from a text string into its components.
     * Uses a robust state machine to correctly handle:
     * - Strings with commas and escaped quotes/backslashes
     * - Nested parentheses inside arguments
     * - Optional whitespace and trailing commas
     */
    fun parseFunctionExpression(input: String): ToolCall? {
        val s = input.trim().removeSuffix(";")
        if (s.isEmpty()) return null

        // Scan once to find the top-level '(' and its matching ')', respecting quotes/escapes
        var inSingle = false
        var inDouble = false
        var escape = false
        var depth = 0
        var openIdx = -1
        var closeIdx = -1

        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (escape) {
                escape = false
                i++
                continue
            }
            when {
                inSingle -> {
                    if (c == '\\') {
                        escape = true
                    } else if (c == '\'') {
                        inSingle = false
                    }
                }
                inDouble -> {
                    if (c == '\\') {
                        escape = true
                    } else if (c == '"') {
                        inDouble = false
                    }
                }
                else -> {
                    when (c) {
                        '\'' -> inSingle = true
                        '"' -> inDouble = true
                        '(' -> {
                            if (openIdx == -1) {
                                openIdx = i
                                depth = 1
                            } else {
                                depth++
                            }
                        }
                        ')' -> {
                            if (openIdx != -1) {
                                depth--
                                if (depth == 0) {
                                    closeIdx = i
                                    i = s.length // break
                                    continue
                                }
                            }
                        }
                    }
                }
            }
            i++
        }

        if (openIdx == -1 || closeIdx == -1 || closeIdx <= openIdx) return null

        val header = s.take(openIdx).trim()
        val argsRegion = s.substring(openIdx + 1, closeIdx)

        val dot = header.lastIndexOf('.')
        if (dot <= 0 || dot >= header.length - 1) return null
        val objectName = header.take(dot).trim()
        val functionName = header.substring(dot + 1).trim()
        if (objectName.isEmpty() || functionName.isEmpty()) return null

        val argsList = splitTopLevelArgs(argsRegion)
        val normalized = argsList.mapNotNull { tok ->
            val t = tok.trim()
            if (t.isEmpty()) null else unquoteAndUnescape(t)
        }
        val args: MutableMap<String, Any?> = normalized.withIndex()
            .associateTo(mutableMapOf()) { it.index.toString() to it.value }

        return ToolCall(objectName, functionName, args)
    }

    // Split arguments by commas at top level, honoring quotes, escapes, and nested parentheses.
    private fun splitTopLevelArgs(s: String): List<String> {
        val out = mutableListOf<String>()
        if (s.isBlank()) return out
        var inSingle = false
        var inDouble = false
        var escape = false
        var depth = 0
        val buf = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (escape) {
                buf.append(c)
                escape = false
                i++
                continue
            }
            when {
                inSingle -> {
                    when (c) {
                        '\\' -> {
                            escape = true
                            buf.append(c)
                        }
                        '\'' -> {
                            inSingle = false
                            buf.append(c)
                        }
                        else -> buf.append(c)
                    }
                }
                inDouble -> {
                    when (c) {
                        '\\' -> {
                            escape = true
                            buf.append(c)
                        }
                        '"' -> {
                            inDouble = false
                            buf.append(c)
                        }
                        else -> buf.append(c)
                    }
                }
                else -> {
                    when (c) {
                        '\'' -> {
                            inSingle = true
                            buf.append(c)
                        }
                        '"' -> {
                            inDouble = true
                            buf.append(c)
                        }
                        '(' -> {
                            depth++
                            buf.append(c)
                        }
                        ')' -> {
                            if (depth > 0) depth--
                            buf.append(c)
                        }
                        ',' -> {
                            if (depth == 0) {
                                out.add(buf.toString())
                                buf.setLength(0)
                            } else {
                                buf.append(c)
                            }
                        }
                        else -> buf.append(c)
                    }
                }
            }
            i++
        }
        // Last token (may be empty on trailing comma)
        if (buf.isNotEmpty()) {
            out.add(buf.toString())
        }
        return out
    }

    // Remove one level of matching quotes and unescape backslash sequences inside.
    private fun unquoteAndUnescape(token: String): String {
        if (token.length >= 2) {
            val first = token.first()
            val last = token.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return unescape(token.substring(1, token.length - 1))
            }
        }
        return token.trim()
    }

    // Unescape known sequences; preserve unknown ones (e.g. \p -> \p)
    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    '\'' -> { sb.append('\''); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    else -> {
                        // Unknown escape: keep the backslash and the char
                        sb.append('\\').append(n)
                        i += 2
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
