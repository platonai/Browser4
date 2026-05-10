package ai.platon.pulsar.rest.mcp.controller

interface ArgumentNormalizer {
    fun normalize(toolName: String, args: MutableMap<String, Any?>): MutableMap<String, Any?>
}

class DefaultArgumentNormalizer : ArgumentNormalizer {
    override fun normalize(toolName: String, args: MutableMap<String, Any?>): MutableMap<String, Any?> {
        val keys = args.keys.toList()
        keys.forEach { key ->
            val camelKey = snakeToCamel(key)
            if (camelKey != key) {
                val value = args.remove(key)
                if (value != null) {
                    args[camelKey] = value
                }
            }
        }
        args.remove(MCPConstants.KEY_SESSION_ID)
        
        val ref = args.remove(MCPConstants.KEY_REF)
        if (!args.containsKey("selector") && ref != null) {
            args["selector"] = ref
        }
        
        val startRef = args.remove("startRef")
        if (!args.containsKey("sourceSelector") && startRef != null) {
            args["sourceSelector"] = startRef
        }
        
        val endRef = args.remove("endRef")
        if (!args.containsKey("targetSelector") && endRef != null) {
            args["targetSelector"] = endRef
        }
        
        val modifiers = args.remove("modifiers")
        if (!args.containsKey("modifier") && modifiers is List<*> && modifiers.isNotEmpty()) {
            args["modifier"] = modifiers.first()?.toString()
        }
        
        return args
    }
    
    private fun snakeToCamel(key: String): String {
        if (!key.contains("_")) {
            return key
        }
        
        val parts = key.split("_").filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            return key
        }
        
        return buildString {
            append(parts.first())
            parts.drop(1).forEach { append(it.replaceFirstChar { c -> c.uppercase() }) }
        }
    }
}

class TabArgumentNormalizer : ArgumentNormalizer {
    override fun normalize(toolName: String, args: MutableMap<String, Any?>): MutableMap<String, Any?> {
        val legacyTabId = args.remove("id")
        if (!args.containsKey("tabId") && legacyTabId != null) {
            args["tabId"] = legacyTabId.toString()
        }
        return args
    }
}

class SelectOptionArgumentNormalizer : ArgumentNormalizer {
    override fun normalize(toolName: String, args: MutableMap<String, Any?>): MutableMap<String, Any?> {
        val legacyValue = args.remove("value")
        if (!args.containsKey("values") && legacyValue != null) {
            args["values"] = listOf(legacyValue.toString())
        }
        return args
    }
}

class EvaluateArgumentNormalizer : ArgumentNormalizer {
    override fun normalize(toolName: String, args: MutableMap<String, Any?>): MutableMap<String, Any?> {
        val selector = args["selector"]?.toString()?.takeIf { it.isNotBlank() }
        val expression = args["expression"]?.toString()?.takeIf { it.isNotBlank() }
        if (selector != null && expression != null && !args.containsKey("functionDeclaration")) {
            args.remove("expression")
            args["functionDeclaration"] = expression
        }
        return args
    }
}

object ArgumentNormalizerFactory {
    private val toolSpecificNormalizers = mapOf(
        "switch_tab" to TabArgumentNormalizer(),
        "tab_select" to TabArgumentNormalizer(),
        "close_tab" to TabArgumentNormalizer(),
        "tab_close" to TabArgumentNormalizer(),
        "select_option" to SelectOptionArgumentNormalizer(),
        "evaluate_value" to EvaluateArgumentNormalizer(),
        "evaluate_value_detail" to EvaluateArgumentNormalizer(),
    )
    
    private val defaultNormalizer = DefaultArgumentNormalizer()
    
    fun getNormalizer(toolName: String): ArgumentNormalizer {
        return toolSpecificNormalizers[toolName] ?: defaultNormalizer
    }
    
    fun normalize(toolName: String, args: Map<String, Any?>): Map<String, Any?> {
        val mutableArgs = args.toMutableMap()
        defaultNormalizer.normalize(toolName, mutableArgs)
        toolSpecificNormalizers[toolName]?.normalize(toolName, mutableArgs)
        return mutableArgs
    }
}