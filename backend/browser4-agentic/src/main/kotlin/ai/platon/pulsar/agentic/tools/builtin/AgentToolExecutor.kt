package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.PerceptiveAgent
import ai.platon.pulsar.agentic.model.ExtractionSchema
import ai.platon.pulsar.agentic.tools.specs.ToolSpecGenerator
import ai.platon.pulsar.common.getLogger
import kotlin.reflect.KClass

class AgentToolExecutor : AbstractToolExecutor() {
    private val logger = getLogger(this)

    override val domain = "agent"

    override val receiverClass: KClass<*> = PerceptiveAgent::class

    init {
        ToolSpecGenerator.agentToolSpecs.associateByTo(toolSpec) { it.method }
    }

    override fun help(method: String): String {
        return when (method) {
            "extract" -> extractHelp()
            else -> toolSpec[method]?.description ?: ""
        }
    }

    /**
     * Execute agent.* expressions against a PerceptiveAgent target using named args.
     */
    @Suppress("UNUSED_PARAMETER")
    @Throws(IllegalArgumentException::class)
    override suspend fun callFunctionOn(
        domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }
        require(functionName.isNotBlank()) { "Function name must not be blank" }

        val agent = requireNotNull(receiver as? PerceptiveAgent) { "Target must be a PerceptiveAgent" }

        return when (functionName) {
            // agent.act(action: String)
            "act" -> {
                validateArgs(args, allowed = setOf("action"), required = setOf("action"), functionName)
                agent.act(paramString(args, "action", functionName)!!)
            }
            // agent.observe(instruction: String)
            "observe" -> {
                validateArgs(args, allowed = setOf("instruction"), required = setOf("instruction"), functionName)
                agent.observe(paramString(args, "instruction", functionName)!!)
            }
            // agent.extract(instruction: String) OR agent.extract(instruction: String, schema: Map<String,String>)
            "extract" -> {
                return if ("schema" in args) {
                    validateArgs(
                        args,
                        allowed = setOf("instruction", "schema"),
                        required = setOf("instruction", "schema"),
                        functionName
                    )
                    val instruction = paramString(args, "instruction", functionName)!!
                    val schema = coerceSchema(args["schema"], functionName)
                    agent.extract(instruction, schema)
                } else {
                    validateArgs(args, allowed = setOf("instruction"), required = setOf("instruction"), functionName)
                    agent.extract(paramString(args, "instruction", functionName)!!)
                }
            }
            "run" -> {
                validateArgs(args, allowed = setOf("task"), required = setOf("task"), functionName)
                agent.run(paramString(args, "task", functionName)!!)
            }
            "summarize" -> {
                validateArgs(args, allowed = setOf("instruction", "selector"), required = setOf(), functionName)
                val instruction = paramString(args, "instruction", functionName, required = false)
                val selector = paramString(args, "selector", functionName, required = false)
                agent.summarize(instruction, selector)
            }
            // Signal completion; just return true
            "done" -> {
                validateArgs(args, allowed = emptySet(), required = emptySet(), functionName)
                true
            }

            else -> {
                throw IllegalArgumentException("Unsupported agent method: $functionName(${args.keys})")
            }
        }
    }


    fun coerceSchema(raw: Any?, functionName: String): ExtractionSchema {
        return try {
            coerceSchema0(raw, functionName)
        } catch (e: IllegalArgumentException) {
            val message = e.message + "\n\n" + help(functionName)
            val revised = IllegalArgumentException(message, e)
            throw revised
        }
    }

    // Helper: coerce schema parameter to Map<String,String>, only accept Map or JSON object string
    private fun coerceSchema0(raw: Any?, functionName: String): ExtractionSchema {
        if (raw == null) throw IllegalArgumentException("Missing parameter 'schema' for $functionName")
        return when (raw) {
            is ExtractionSchema -> raw
            is Map<*, *> -> {
                ExtractionSchema.fromMap(raw)
            }

            is String -> {
                ExtractionSchema.parse(raw)
            }

            else -> throw IllegalArgumentException("Parameter 'schema' must be ExtractionSchema or JSON object string; actual='${raw::class.simpleName}'")
        }
    }

    companion object {

        fun extractHelp(): String {
            val help = $$"""
使用 `agent.extract` 满足高级数据提取要求：

- 对提取结果格式有严格要求
- 提取结果存在内嵌对象
- 其他数据提取工具无法满足要求

参数说明：

1. `instruction`: 准确描述 1. 数据提取目标 2. 数据提取要求
2. `schema`: JSON 格式描述的数据提取结果 schema 要求，遵循如下模式：

```kotlin
class ExtractionField(
    name: String,
    type: String = "string",                 // JSON schema primitive or 'object' / 'array'
    description: String,
    required: Boolean = true,
    objectMemberProperties: List<ExtractionField> = emptyList(), // children if object
    arrayElements: ExtractionField? = null                    // item schema if array
)
class ExtractionSchema(fields: List<ExtractionField>)
```

对应的 JSON 描述：

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "ExtractionSchema",
  "type": "object",
  "required": ["fields"],
  "properties": {
    "fields": {
      "type": "array",
      "items": {
        "$ref": "#/definitions/ExtractionField"
      }
    }
  },
  "definitions": {
    "ExtractionField": {
      "type": "object",
      "required": ["name", "description"],
      "properties": {
        "name": { "type": "string" },
        "type": {
          "type": "string",
          "default": "string",
          "enum": ["string", "number", "boolean", "object", "array"]
        },
        "description": { "type": "string" },
        "required": {
          "type": "boolean",
          "default": true
        },
        "properties": {
          "type": "array",
          "items": { "$ref": "#/definitions/ExtractionField" },
          "default": []
        },
        "items": {
          "$ref": "#/definitions/ExtractionField",
          "default": null
        }
      }
    }
  }
}
```

示例：

```json
{
  "fields": [
    {
      "name": "product",
      "type": "object",
      "description": "Product info",
      "properties": [
        {
          "name": "name",
          "type": "string",
          "description": "Product name",
          "required": true
        },
        {
          "name": "variants",
          "type": "array",
          "required": false,
          "items": {
            "name": "variant",
            "type": "object",
            "required": false,
            "properties": [
              { "name": "sku", "type": "string", "required": false },
              { "name": "price", "type": "number", "required": false }
            ]
          }
        }
      ]
    }
  ]
}
```

        """.trimIndent()

            return help
        }
    }
}
