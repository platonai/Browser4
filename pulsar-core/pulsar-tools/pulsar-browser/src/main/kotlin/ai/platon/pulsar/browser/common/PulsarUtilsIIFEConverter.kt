package ai.platon.pulsar.browser.common

import ai.platon.pulsar.common.ResourceLoader
import ai.platon.pulsar.common.getLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Converts __pulsar_utils__ method calls to Immediately Invoked Function Expressions (IIFE).
 * This removes the need for global __pulsar_utils__ injection while maintaining functionality.
 *
 * Example transformation:
 * Before: __pulsar_utils__.check(selector)
 * After: ((selector) => { /* check method implementation */ })(selector)
 */
class PulsarUtilsIIFEConverter {
    companion object {
        private val logger = getLogger(this)
        private const val UTILS_JS_PATH = "js/__pulsar_utils__.js"
    }

    private val methodCache = ConcurrentHashMap<String, String>()
    private val utilsSource: String by lazy {
        ResourceLoader.readAllLines(UTILS_JS_PATH).joinToString("\n")
    }

    /**
     * Transform JavaScript code containing __pulsar_utils__ calls to use IIFEs instead.
     *
     * @param expression The JavaScript expression to transform
     * @return The transformed expression with IIFEs replacing __pulsar_utils__ calls
     */
    fun transform(expression: String): String {
        if (!expression.contains("__pulsar_utils__")) {
            return expression
        }

        var transformed = expression
        val pattern = Regex("""__pulsar_utils__\.(\w+)\s*\((.*?)\)""")
        val matches = pattern.findAll(expression).toList()

        // Process matches in reverse order to maintain correct positions
        for (match in matches.reversed()) {
            val methodName = match.groupValues[1]
            val args = match.groupValues[2]
            val iife = createIIFE(methodName, args)
            transformed = transformed.replaceRange(match.range, iife)
        }

        return transformed
    }

    /**
     * Create an IIFE for the given method call.
     *
     * @param methodName Name of the __pulsar_utils__ method
     * @param args Arguments passed to the method (as a string)
     * @return IIFE expression
     */
    private fun createIIFE(methodName: String, args: String): String {
        val methodDef = extractMethodDefinition(methodName)
            ?: return "__pulsar_utils__.$methodName($args)" // fallback to original

        // Build IIFE: (function(params) { body })(args)
        val iife = "(function${methodDef.paramsDeclaration} ${methodDef.body})($args)"
        
        logger.trace("Converted __pulsar_utils__.$methodName($args) to IIFE")
        return iife
    }

    /**
     * Extract the definition of a __pulsar_utils__ method from the source file.
     */
    private fun extractMethodDefinition(methodName: String): MethodDefinition? {
        return methodCache.getOrPut(methodName) {
            extractMethodDefinitionUncached(methodName) ?: ""
        }.let { if (it.isEmpty()) null else MethodDefinition(it) }
    }

    private fun extractMethodDefinitionUncached(methodName: String): String? {
        // Find the start of the method definition
        val startPattern = Regex("""__pulsar_utils__\.$methodName\s*=\s*function\s*(\([^)]*\))\s*\{""")
        val startMatch = startPattern.find(utilsSource) ?: run {
            logger.warn("Could not find definition for __pulsar_utils__.$methodName")
            return null
        }

        val params = startMatch.groupValues[1]
        val bodyStart = startMatch.range.last + 1

        // Find the matching closing brace using brace counting
        var braceCount = 1
        var bodyEnd = bodyStart
        while (braceCount > 0 && bodyEnd < utilsSource.length) {
            when (utilsSource[bodyEnd]) {
                '{' -> braceCount++
                '}' -> braceCount--
            }
            bodyEnd++
        }

        if (braceCount != 0) {
            logger.warn("Could not find matching closing brace for __pulsar_utils__.$methodName")
            return null
        }

        val body = utilsSource.substring(bodyStart - 1, bodyEnd)
        return "$params $body"
    }

    /**
     * Represents a parsed method definition.
     */
    private data class MethodDefinition(val fullDefinition: String) {
        val paramsDeclaration: String
        val body: String

        init {
            val parts = fullDefinition.split(Regex("""\s+"""), limit = 2)
            paramsDeclaration = parts.getOrNull(0) ?: "()"
            body = parts.getOrNull(1) ?: "{}"
        }
    }

    /**
     * Check if an expression contains __pulsar_utils__ calls.
     */
    fun needsTransformation(expression: String): Boolean {
        return expression.contains("__pulsar_utils__")
    }
}
