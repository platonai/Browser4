package ai.platon.pulsar.agentic.tools.specs

import ai.platon.browser4.common.B4LLMUtils
import ai.platon.browser4.common.B4ProjectUtils
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.common.ExperimentalApi
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.prettyPulsarObjectMapper
import java.util.concurrent.atomic.AtomicBoolean

@ExperimentalApi
object ToolSpecGenerator {
    private val logger = getLogger(this)
    private val isGenerated: AtomicBoolean = AtomicBoolean()

    val webDriverToolSpecs = mutableListOf<ToolSpec>()
    val agentToolSpecs = mutableListOf<ToolSpec>()

    @Synchronized
    fun generateAllOnce() {
        if (isGenerated.compareAndSet(false, true)) {
            try {
                loadToolSpecs()
            } catch (e: Exception) {
                logger.warn("Failed to generate tool specs: {}", e.message)
                webDriverToolSpecs.clear()
                agentToolSpecs.clear()
                isGenerated.set(false)
            }
        }
    }

    private fun loadToolSpecs() {
        val webDriverSource = try {
            B4LLMUtils.readSourceFileFromResource("browser4-core", "WebDriver.kt")
        } catch (e: Exception) {
            logger.warn("Could not read WebDriver.kt source: {}", e.message)
            ""
        }
        if (webDriverSource.isNotBlank()) {
            extractInterface("tab", webDriverSource, "WebDriver").toCollection(webDriverToolSpecs)
        }
        if (webDriverToolSpecs.isEmpty()) {
            logger.warn("WebDriver tool call list is empty")
        }

        val agentSource = try {
            B4LLMUtils.readSourceFileFromResource("browser4-agentic", "PerceptiveAgent.kt")
        } catch (e: Exception) {
            logger.warn("Could not read PerceptiveAgent.kt source: {}", e.message)
            ""
        }
        if (agentSource.isNotBlank()) {
            extractInterface("agent", agentSource, "PerceptiveAgent").toCollection(agentToolSpecs)
        }
        if (agentToolSpecs.isEmpty()) {
            logger.warn("PerceptiveAgent tool call list is empty")
        }

        if (!B4ProjectUtils.isInJar()) {
            if (webDriverToolSpecs.isNotEmpty()) {
                val fileName = "driver-tool-call-specs.json"
                val content = normalizeToLinuxLineEndings(toSnapshotJson(webDriverToolSpecs))
                B4LLMUtils.writeAsResource(fileName, content)
            }

            if (agentToolSpecs.isNotEmpty()) {
                val fileName = "agent-tool-call-specs.json"
                val content = normalizeToLinuxLineEndings(toSnapshotJson(agentToolSpecs))
                B4LLMUtils.writeAsResource(fileName, content)
            }
        }

        // If both lists are still empty, allow future retries
        if (webDriverToolSpecs.isEmpty() && agentToolSpecs.isEmpty()) {
            isGenerated.set(false)
        }
    }

    internal fun toSnapshotJson(toolSpecs: List<ToolSpec>): String {
        return serializeToolSpecs(toolSpecs)
    }

    internal fun normalizeToLinuxLineEndings(content: String): String {
        return content.replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun serializeToolSpecs(toolSpecs: List<ToolSpec>): String {
        // Use explicit LinkedHashMap field insertion order to keep snapshot JSON deterministic.
        val payload = toolSpecs.map { spec ->
            linkedMapOf<String, Any?>(
                "domain" to spec.domain,
                "method" to spec.method,
                "arguments" to spec.arguments.map { arg ->
                    linkedMapOf<String, Any?>(
                        "name" to arg.name,
                        "type" to arg.type,
                        "defaultValue" to arg.defaultValue,
                        "expression" to arg.expression,
                        "cliOptions" to arg.cliOptions,
                    )
                },
                "returnType" to spec.returnType,
                "description" to spec.description,
                "help" to spec.help,
                "expression" to spec.expression,
                "cli" to spec.cli,
            )
        }
        return prettyPulsarObjectMapper().writeValueAsString(payload)
    }

    fun extractInterface(domain: String, sourceCode: String, interfaceName: String): List<ToolSpec> {
        // Parse WebDriver interface methods and build ToolCall specs
        val interfaceBody = extractInterfaceBody(sourceCode, interfaceName) ?: sourceCode
        val methods = parseFunctionsWithKDoc(interfaceBody)
        val toolSpec = mutableListOf<ToolSpec>()
        for (m in methods) {
            val arguments = mutableListOf<ToolSpec.Arg>()
            for (p in m.params) {
                val defaultValue = when {
                    p.defaultValue != null && p.type.equals("String", ignoreCase = true) -> unquote(p.defaultValue)
                    p.defaultValue != null -> p.defaultValue
                    else -> ""
                }
                val arg = ToolSpec.Arg(p.name, p.type, defaultValue)
                arguments.add(arg)
            }

            val method = m.name
            // Use parsed return type; default to Unit when absent
            val returnType = m.returnType.ifBlank { "Unit" }
            val description = m.kdoc ?: methodNameToDescription(method)
            val help = m.fullKDoc
            // Keep argument order exactly as declared in source signatures.
            toolSpec += ToolSpec(domain, method, arguments, returnType, description, help)
        }

        // Keep method order exactly as declared in source interfaces.
        return toolSpec
    }

    // Helper types and parsers for SourceCodeToToolCall
    private data class ParamSig(val name: String, val type: String, val defaultValue: String?)
    private data class FuncSig(
        val name: String,
        val params: List<ParamSig>,
        val returnType: String,
        val kdoc: String?,
        val fullKDoc: String? = null
    )

    private fun extractInterfaceBody(src: String, interfaceName: String): String? {
        val regex = Regex("interface\\s+$interfaceName")
        val match = regex.find(src) ?: return null
        val idx = match.range.first
        val braceIdx = src.indexOf('{', idx)
        if (braceIdx < 0) return null
        var depth = 1
        var i = braceIdx + 1
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(braceIdx + 1, i)
                }
            }
            i++
        }
        return null
    }

    private fun parseFunctionsWithKDoc(body: String): List<FuncSig> {
        val out = mutableListOf<FuncSig>()
        val lines = body.lines()
        var inKDoc = false
        val kdocBuf = StringBuilder()
        var pendingKDoc: String? = null
        var pendingFullKDoc: String? = null
        var pendingMcpAnnotation = false

        var collectingSig = false
        val sigBuf = StringBuilder()
        var parenDepth = 0
        var inSingle = false
        var inDouble = false
        var escape = false

        fun resetSig() {
            collectingSig = false
            sigBuf.setLength(0)
            parenDepth = 0
            inSingle = false
            inDouble = false
            escape = false
        }

        fun commitSig() {
            val sig = sigBuf.toString()
            val parsed = parseSignature(sig)
            if (parsed != null && pendingMcpAnnotation) {
                val (name, params, returnType) = parsed
                out += FuncSig(name, params, returnType, pendingKDoc, pendingFullKDoc)
            }
            pendingKDoc = null
            pendingFullKDoc = null
            pendingMcpAnnotation = false
            resetSig()
        }

        for (raw in lines) {
            val line = raw.trimEnd()
            if (!inKDoc && line.trimStart().startsWith("/**")) {
                inKDoc = true
                kdocBuf.setLength(0)
                kdocBuf.appendLine(line)
                continue
            }
            if (inKDoc) {
                kdocBuf.appendLine(line)
                if (line.contains("*/")) {
                    inKDoc = false
                    // Clean up KDoc and extract relevant description
                    val (short, full) = processKDoc(kdocBuf.toString())
                    pendingKDoc = short
                    pendingFullKDoc = full
                }
                continue
            }

            // Skip annotation lines
            if (line.trimStart().startsWith("@")) {
                if (line.contains("@MCP")) {
                    pendingMcpAnnotation = true
                }
                continue
            }

            // If we are collecting a signature, keep appending until parens balanced
            if (collectingSig) {
                appendTracking(sigBuf, line) { ch ->
                    val state = QuoteParenState(inSingle, inDouble, escape, parenDepth)
                    val newState = updateQuoteParenState(state, ch)
                    inSingle = newState.inSingle
                    inDouble = newState.inDouble
                    escape = newState.escape
                    parenDepth = newState.parenDepth
                }
                // If we've seen the closing ')' for the parameter list, try to capture optional return type
                if (parenDepth == 0 && sigBuf.contains('(') && sigBuf.indexOf(')') > sigBuf.indexOf('(')) {
                    // If current buffer already contains a ':' after ')', we have the return type on this line.
                    // Otherwise, we may be on a multi-line declaration; keep collecting one more line.
                    val afterClose = sigBuf.substring(sigBuf.indexOf(')') + 1)
                    if (afterClose.contains(':')) {
                        commitSig()
                    } else {
                        // Defer to next line to see if return type appears; if not, we'll parse as Unit.
                        commitSig()
                    }
                }
                continue
            }

            // Detect start of a function declaration line
            val hasFun = line.contains("fun ") || line.contains("suspend fun ")
            if (hasFun) {
                collectingSig = true
                sigBuf.setLength(0)
                appendTracking(sigBuf, line) { ch ->
                    val state = QuoteParenState(inSingle, inDouble, escape, parenDepth)
                    val newState = updateQuoteParenState(state, ch)
                    inSingle = newState.inSingle
                    inDouble = newState.inDouble
                    escape = newState.escape
                    parenDepth = newState.parenDepth
                }
                // If already complete on the same line
                if (parenDepth == 0 && sigBuf.contains('(') && sigBuf.indexOf(')') > sigBuf.indexOf('(')) {
                    commitSig()
                }
            }
        }
        // In case last signature ended at EOF
        if (collectingSig && parenDepth == 0 && sigBuf.contains('(')) {
            val parsed = parseSignature(sigBuf.toString())
            if (parsed != null && pendingMcpAnnotation) {
                val (name, params, returnType) = parsed
                out += FuncSig(name, params, returnType, pendingKDoc, pendingFullKDoc)
            }
        }
        return out
    }

    private data class QuoteParenState(
        val inSingle: Boolean,
        val inDouble: Boolean,
        val escape: Boolean,
        val parenDepth: Int,
    )

    private fun updateQuoteParenState(state: QuoteParenState, ch: Char): QuoteParenState {
        var inSingle = state.inSingle
        var inDouble = state.inDouble
        var escape = state.escape
        var depth = state.parenDepth
        if (escape) {
            // consume escaped
            return QuoteParenState(inSingle, inDouble, false, depth)
        }
        when {
            inSingle -> when (ch) {
                '\\' -> escape = true
                '\'' -> inSingle = false
            }

            inDouble -> when (ch) {
                '\\' -> escape = true
                '"' -> inDouble = false
            }

            else -> when (ch) {
                '\'' -> inSingle = true
                '"' -> inDouble = true
                '(' -> depth++
                ')' -> if (depth > 0) depth--
            }
        }
        return QuoteParenState(inSingle, inDouble, escape, depth)
    }

    private inline fun appendTracking(
        buf: StringBuilder,
        text: String,
        setState: (Char) -> Unit
    ) {
        for (c in text) {
            buf.append(c)
            setState(c)
        }
    }

    private fun parseSignature(sig: String): Triple<String, List<ParamSig>, String>? {
        // Remove leading modifiers and annotations remnants, keep from the last 'fun'
        val idxFun = sig.lastIndexOf("fun ")
        if (idxFun < 0) return null
        var s = sig.substring(idxFun + 4).trim()
        // Remove generic <...> after fun if any
        if (s.startsWith('<')) {
            val gt = findMatchingAngle(s)
            if (gt > 0) s = s.substring(gt + 1).trim()
        }
        // name(...)
        val open = s.indexOf('(')
        val close = s.indexOf(')', startIndex = open + 1)
        if (open < 0 || close < 0) return null
        val name = s.substring(0, open).trim()
        val paramsRegion = s.substring(open + 1, close)
        val params = parseParams(paramsRegion)
        // parse return type if present: look for ':' after close paren
        var returnType = "Unit"
        if (close + 1 < s.length) {
            val after = s.substring(close + 1).trim()
            if (after.startsWith(':')) {
                val typeText = after.substring(1).trim()
                returnType = parseReturnType(typeText)
            }
        }
        return Triple(name, params, returnType)
    }

    private fun parseReturnType(typeText: String): String {
        // Capture until top-level '=' or '{' or end, respecting nested <>, (), [] and quotes
        var depthPar = 0
        var depthAngle = 0
        var depthBracket = 0
        var inSingle = false
        var inDouble = false
        var escape = false
        val buf = StringBuilder()
        for (c in typeText) {
            if (escape) {
                buf.append(c); escape = false; continue
            }
            when {
                inSingle -> when (c) {
                    '\\' -> {
                        escape = true; buf.append(c)
                    }

                    '\'' -> {
                        inSingle = false; buf.append(c)
                    }

                    else -> buf.append(c)
                }

                inDouble -> when (c) {
                    '\\' -> {
                        escape = true; buf.append(c)
                    }

                    '"' -> {
                        inDouble = false; buf.append(c)
                    }

                    else -> buf.append(c)
                }

                else -> when (c) {
                    '\'' -> {
                        inSingle = true; buf.append(c)
                    }

                    '"' -> {
                        inDouble = true; buf.append(c)
                    }

                    '(' -> {
                        depthPar++; buf.append(c)
                    }

                    ')' -> {
                        if (depthPar > 0) depthPar--; buf.append(c)
                    }

                    '<' -> {
                        depthAngle++; buf.append(c)
                    }

                    '>' -> {
                        if (depthAngle > 0) depthAngle--; buf.append(c)
                    }

                    '[' -> {
                        depthBracket++; buf.append(c)
                    }

                    ']' -> {
                        if (depthBracket > 0) depthBracket--; buf.append(c)
                    }

                    '=' -> if (depthPar == 0 && depthAngle == 0 && depthBracket == 0) {
                        break
                    } else buf.append(c)

                    '{' -> if (depthPar == 0 && depthAngle == 0 && depthBracket == 0) {
                        break
                    } else buf.append(c)

                    else -> buf.append(c)
                }
            }
        }
        return buf.toString().trim().trimEnd(';')
    }

    private fun findMatchingAngle(s: String): Int {
        var depth = 0
        var i = 0
        var inSingle = false
        var inDouble = false
        var escape = false
        while (i < s.length) {
            val c = s[i]
            if (escape) {
                escape = false; i++; continue
            }
            when {
                inSingle -> when (c) {
                    '\\' -> escape = true; '\'' -> inSingle = false
                }

                inDouble -> when (c) {
                    '\\' -> escape = true; '"' -> inDouble = false
                }

                else -> when (c) {
                    '\'' -> {
                        inSingle = true
                    }

                    '"' -> {
                        inDouble = true
                    }

                    '<' -> {
                        depth++
                    }

                    '>' -> {
                        depth--; if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun parseParams(region: String): List<ParamSig> {
        val tokens = splitTopLevel(region)
        val out = mutableListOf<ParamSig>()
        for (t in tokens) {
            val token = t.trim()
            if (token.isEmpty()) continue
            // Expect: name: Type = default
            val nameEnd = token.indexOf(':')
            if (nameEnd <= 0) continue
            val name = token.substring(0, nameEnd).trim().removePrefix("vararg ").removePrefix("noinline ")
                .removePrefix("crossinline ")
            val rest = token.substring(nameEnd + 1).trim()
            val eq = rest.indexOf('=')
            val type = (if (eq >= 0) rest.substring(0, eq) else rest).trim()
            val default = if (eq >= 0) rest.substring(eq + 1).trim() else null
            out += ParamSig(name, type, default)
        }
        return out
    }

    private fun splitTopLevel(s: String): List<String> {
        val out = mutableListOf<String>()
        if (s.isBlank()) return out
        var depthPar = 0
        var depthAngle = 0
        var inSingle = false
        var inDouble = false
        var escape = false
        val buf = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (escape) {
                buf.append(c); escape = false; i++; continue
            }
            when {
                inSingle -> when (c) {
                    '\\' -> {
                        escape = true; buf.append(c)
                    }; '\'' -> {
                        inSingle = false; buf.append(c)
                    }; else -> buf.append(c)
                }

                inDouble -> when (c) {
                    '\\' -> {
                        escape = true; buf.append(c)
                    }; '"' -> {
                        inDouble = false; buf.append(c)
                    }; else -> buf.append(c)
                }

                else -> when (c) {
                    '\'' -> {
                        inSingle = true; buf.append(c)
                    }

                    '"' -> {
                        inDouble = true; buf.append(c)
                    }

                    '(' -> {
                        depthPar++; buf.append(c)
                    }

                    ')' -> {
                        if (depthPar > 0) depthPar--; buf.append(c)
                    }

                    '<' -> {
                        depthAngle++; buf.append(c)
                    }

                    '>' -> {
                        if (depthAngle > 0) depthAngle--; buf.append(c)
                    }

                    ',' -> if (depthPar == 0 && depthAngle == 0) {
                        out += buf.toString(); buf.setLength(0)
                    } else buf.append(c)

                    else -> buf.append(c)
                }
            }
            i++
        }
        if (buf.isNotEmpty()) out += buf.toString()
        return out
    }

    private fun processKDoc(kdocRaw: String): Pair<String, String> {
        val inner = kdocRaw
            .replace("\r", "")
            .substringAfter("/**")
            .substringBeforeLast("*/")
            .trim()

        val lines = inner.lines()
            .map { it.trim().removePrefix("*").trim() }
            .filter {
                !it.startsWith("@") || it.startsWith("@mcp", ignoreCase = true)
            }

        // Group lines into paragraphs
        val paragraphs = mutableListOf<String>()
        val currentParagraph = StringBuilder()

        for (line in lines) {
            if (line.isBlank()) {
                if (currentParagraph.isNotEmpty()) {
                    paragraphs.add(currentParagraph.toString().trim())
                    currentParagraph.setLength(0)
                }
            } else {
                if (currentParagraph.isNotEmpty()) {
                    currentParagraph.append(" ")
                }
                currentParagraph.append(line)
            }
        }
        if (currentParagraph.isNotEmpty()) {
            paragraphs.add(currentParagraph.toString().trim())
        }

        val taggedParagraphs = paragraphs
            .filter { it.contains("@mcp", ignoreCase = true) || it.contains("#mcp", ignoreCase = true) }
            .map(::stripMcpTag)
            .filter { it.isNotBlank() }

        val shortDescription = if (taggedParagraphs.isNotEmpty()) {
            taggedParagraphs.joinToString("\n\n")
        } else {
            paragraphs.map(::stripMcpTag).filter { it.isNotBlank() }.joinToString("\n\n")
        }

        val fullDescription = paragraphs.map(::stripMcpTag).filter { it.isNotBlank() }.joinToString("\n\n")

        return shortDescription to fullDescription
    }

    private fun stripMcpTag(paragraph: String): String {
        return paragraph
            .replace("@mcp", "", ignoreCase = true)
            .replace("#mcp", "", ignoreCase = true)
            .trim()
    }

    private fun methodNameToDescription(methodName: String): String {
        return methodName
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun cleanupKDoc(kdocRaw: String): String {
        return processKDoc(kdocRaw).first
    }

    private fun compactDoc(doc: String): String {
        val s = Strings.compactWhitespaces(doc)
        return if (s.length <= 360) s else s.substring(0, 357) + "..."
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        return if ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith('\'') && t.endsWith('\''))) {
            t.substring(1, t.length - 1)
        } else t
    }
}
