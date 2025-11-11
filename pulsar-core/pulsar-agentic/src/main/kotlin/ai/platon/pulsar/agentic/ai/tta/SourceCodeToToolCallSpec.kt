package ai.platon.pulsar.agentic.ai.tta

import ai.platon.pulsar.common.ResourceLoader
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.code.ProjectUtils
import ai.platon.pulsar.skeleton.ai.ToolCallSpec
import ai.platon.pulsar.skeleton.common.llm.LLMUtils
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.javaMethod

object SourceCodeToToolCallSpec {

    val webDriverToolCallFullList = mutableListOf<ToolCallSpec>()

    init {
        LLMUtils.copyWebDriverAsResource()
        val resource = "code-mirror/WebDriver.kt"
        val sourceCode = ResourceLoader.readString(resource)
        extract("driver", sourceCode).toCollection(webDriverToolCallFullList)
    }

    val toolCallExpressions get() = webDriverToolCallFullList.joinToString("\n") { it.expression }

    fun extract(domain: String, sourceCode: String): List<ToolCallSpec> {
        // Parse WebDriver interface methods and build ToolCall specs
        val ifaceBody = extractInterfaceBody(sourceCode, "WebDriver") ?: sourceCode
        val methods = parseFunctionsWithKDoc(ifaceBody)
        val toolCallSpecs = mutableListOf<ToolCallSpec>()
        for (m in methods) {
            val arguments = mutableListOf<ToolCallSpec.Arg>()
            for (p in m.params) {
                val v = when {
                    p.defaultValue != null && p.type.equals("String", ignoreCase = true) -> unquote(p.defaultValue)
                    p.defaultValue != null -> p.defaultValue
                    else -> ""
                }
                val arg = ToolCallSpec.Arg(p.name, p.type, p.defaultValue)
                arguments.add(arg)
            }

            val method = m.name
            // Use parsed return type; default to Unit when absent
            val returnType = m.returnType.ifBlank { "Unit" }
            val desc = m.kdoc?.let { compactDoc(it) }
            toolCallSpecs += ToolCallSpec(domain, method, arguments, returnType, desc)
        }

        return toolCallSpecs
    }

    /**
     * Generate ToolCallSpec list from a Kotlin interface using reflection.
     *
     * This method uses Kotlin reflection to inspect the methods of the given interface class,
     * extracting method signatures, parameters, return types, and documentation.
     * The generated list is then saved as a JSON file in the PROJECT_UTILS.CODE_RESOURCE_DIR directory.
     *
     * @param domain The domain name for the tool call specs (e.g., "driver")
     * @param interfaceClass The Kotlin class representing the interface to inspect
     * @param outputFileName The name of the output JSON file (without path)
     * @return List of ToolCallSpec objects generated from the interface
     */
    fun generateFromReflection(
        domain: String,
        interfaceClass: KClass<*>,
        outputFileName: String = "webdriver-toolcall-specs.json"
    ): List<ToolCallSpec> {
        val toolCallSpecs = mutableListOf<ToolCallSpec>()

        // Get all declared functions from the interface
        val functions = interfaceClass.declaredFunctions

        for (function in functions) {
            // Skip private, internal, or deprecated functions if needed
            // For now, we'll include all declared functions

            val methodName = function.name
            val arguments = mutableListOf<ToolCallSpec.Arg>()

            // Extract parameters
            for (param in function.parameters) {
                // Skip the instance parameter (this)
                if (param.kind == kotlin.reflect.KParameter.Kind.INSTANCE) {
                    continue
                }

                val paramName = param.name ?: "arg${param.index}"
                val paramType = extractTypeName(param.type.toString())
                
                // Check if parameter has a default value
                val defaultValue = if (param.isOptional) {
                    // Try to extract default value from parameter
                    // Note: Reflection doesn't give us the actual default value,
                    // so we'll use null as a marker for optional parameters
                    extractDefaultValue(param)
                } else {
                    null
                }

                arguments.add(ToolCallSpec.Arg(paramName, paramType, defaultValue))
            }

            // Extract return type
            val returnType = extractTypeName(function.returnType.toString())

            // Extract KDoc if available
            val description = extractKDoc(function)

            toolCallSpecs.add(ToolCallSpec(domain, methodName, arguments, returnType, description))
        }

        // Save to JSON file in CODE_RESOURCE_DIR
        saveToJsonFile(toolCallSpecs, outputFileName)

        return toolCallSpecs
    }

    /**
     * Extract a simple type name from a full qualified type string.
     * For example: "kotlin.String?" -> "String"
     */
    private fun extractTypeName(typeString: String): String {
        // Remove package names and keep just the class name
        val cleaned = typeString
            .replace("kotlin.", "")
            .replace("java.lang.", "")
            .replace("ai.platon.pulsar.", "")
        
        // Handle nullable types
        return cleaned.trim()
    }

    /**
     * Extract default value for optional parameters.
     * Since Kotlin reflection doesn't provide actual default values,
     * we return a placeholder or null.
     */
    private fun extractDefaultValue(param: kotlin.reflect.KParameter): String? {
        // For optional parameters, we can't get the actual default value via reflection
        // We'll return null to indicate it has a default but we don't know what it is
        return if (param.isOptional) {
            // Return a type-appropriate default marker
            when {
                param.type.toString().contains("Int") -> "0"
                param.type.toString().contains("Boolean") -> "false"
                param.type.toString().contains("String") -> "\"\""
                else -> null
            }
        } else {
            null
        }
    }

    /**
     * Extract KDoc documentation from a function.
     * Note: KDoc is not available through Kotlin reflection at runtime,
     * so this will return null. For proper documentation, use source code parsing.
     */
    private fun extractKDoc(function: KFunction<*>): String? {
        // KDoc is not available via reflection at runtime
        // To get KDoc, we would need to parse the source file
        // For now, return null or try to get from annotations
        
        // Try to get from Deprecated annotation as an example
        val deprecated = function.findAnnotation<Deprecated>()
        return deprecated?.message
    }

    /**
     * Save ToolCallSpec list to a JSON file in the CODE_RESOURCE_DIR.
     */
    private fun saveToJsonFile(toolCallSpecs: List<ToolCallSpec>, fileName: String): Boolean {
        val rootDir = ProjectUtils.findProjectRootDir() ?: return false
        val destPath = rootDir.resolve(ProjectUtils.CODE_RESOURCE_DIR)

        // Ensure directory exists
        Files.createDirectories(destPath)

        val targetFile = destPath.resolve(fileName)

        // Configure ObjectMapper for pretty printing
        val objectMapper = ObjectMapper().apply {
            enable(SerializationFeature.INDENT_OUTPUT)
        }

        // Serialize to JSON
        val jsonContent = objectMapper.writeValueAsString(toolCallSpecs)

        // Write to file
        Files.writeString(
            targetFile,
            jsonContent,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        )

        return true
    }

    // Helper types and parsers for SourceCodeToToolCall
    private data class ParamSig(val name: String, val type: String, val defaultValue: String?)
    private data class FuncSig(val name: String, val params: List<ParamSig>, val returnType: String, val kdoc: String?)

    private fun extractInterfaceBody(src: String, ifaceName: String): String? {
        val regex = Regex("interface\\s+$ifaceName")
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
            if (parsed != null) {
                val (name, params, returnType) = parsed
                out += FuncSig(name, params, returnType, pendingKDoc)
            }
            pendingKDoc = null
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
                    pendingKDoc = cleanupKDoc(kdocBuf.toString())
                }
                continue
            }

            // Skip annotation lines
            if (line.trimStart().startsWith("@")) {
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
            if (parsed != null) {
                val (name, params, returnType) = parsed
                out += FuncSig(name, params, returnType, pendingKDoc)
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

    private fun cleanupKDoc(kdocRaw: String): String {
        val inner = kdocRaw
            .replace("\r", "")
            .substringAfter("/**", "")
            .substringBefore("*/", "")
        val lines = inner.lines()
        val cleaned = lines.map { it.trim().removePrefix("*").trim() }
            .filter { it.isNotBlank() && !it.trimStart().startsWith("@") }
        // keep until first empty line originally (paragraph)
        val sb = StringBuilder()
        var firstPara = true
        for (ln in cleaned) {
            if (ln.isBlank()) {
                if (firstPara) break else continue
            }
            if (ln.startsWith("```")) break
            sb.appendLine(ln)
            firstPara = false
        }
        return sb.toString().trim()
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
