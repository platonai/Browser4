package ai.platon.pulsar.agentic.model

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

data class ExtractionField(
    val name: String,
    val type: String = "string",                 // JSON schema primitive or 'object' / 'array'
    val description: String? = null,
    val required: Boolean = true,
    @param:JsonAlias("properties")
    val objectMemberProperties: List<ExtractionField> = emptyList(), // define the schema of member properties if type == object
    @param:JsonAlias("items")
    val arrayElements: ExtractionField? = null                   // define the schema of elements if type == array
) {
    fun toJsonSchemaNode(mapper: ObjectMapper): ObjectNode {
        val node = JsonNodeFactory.instance.objectNode()
        node.put("type", type)
        if (!description.isNullOrBlank()) node.put("description", description)
        when (type) {
            "object" -> {
                val propsNode = JsonNodeFactory.instance.objectNode()
                val requiredList = mutableListOf<String>()
                objectMemberProperties.forEach { child ->
                    propsNode.set<ObjectNode>(child.name, child.toJsonSchemaNode(mapper))
                    if (child.required) requiredList += child.name
                }
                node.set<ObjectNode>("properties", propsNode)
                if (requiredList.isNotEmpty()) {
                    node.set<ArrayNode>("required", mapper.valueToTree<ArrayNode>(requiredList))
                }
            }

            "array" -> {
                val itemNode = arrayElements?.toJsonSchemaNode(mapper) ?: JsonNodeFactory.instance.objectNode()
                    .apply { put("type", "string") }
                node.set<ObjectNode>("items", itemNode)
            }
        }
        return node
    }

    companion object {
        /** Convenience for a simple string field. */
        fun string(name: String, description: String? = null, required: Boolean = true): ExtractionField =
            ExtractionField(name = name, type = "string", description = description, required = required)

        /** Convenience for an object field with child properties. */
        fun obj(
            name: String,
            objectMemberProperties: List<ExtractionField>,
            description: String? = null,
            required: Boolean = true
        ): ExtractionField = ExtractionField(
            name = name,
            type = "object",
            description = description,
            required = required,
            objectMemberProperties = objectMemberProperties
        )

        /** Convenience for an array field with a given item schema. */
        fun arrayOf(
            name: String,
            item: ExtractionField,
            description: String? = null,
            required: Boolean = true
        ): ExtractionField = ExtractionField(
            name = name,
            type = "array",
            description = description,
            required = required,
            arrayElements = item
        )
    }
}

/**
 * ```json
 * {
 *   "$schema": "http://json-schema.org/draft-07/schema#",
 *   "title": "ExtractionSchema",
 *   "type": "object",
 *   "required": ["fields"],
 *   "properties": {
 *     "fields": {
 *       "type": "array",
 *       "items": {
 *         "$ref": "#/definitions/ExtractionField"
 *       }
 *     }
 *   },
 *   "definitions": {
 *     "ExtractionField": {
 *       "type": "object",
 *       "required": ["name", "description"],
 *       "properties": {
 *         "name": { "type": "string" },
 *         "type": {
 *           "type": "string",
 *           "default": "string",
 *           "enum": ["string", "number", "boolean", "object", "array"]
 *         },
 *         "description": { "type": "string" },
 *         "required": {
 *           "type": "boolean",
 *           "default": true
 *         },
 *         "properties": {
 *           "type": "array",
 *           "items": { "$ref": "#/definitions/ExtractionField" },
 *           "default": []
 *         },
 *         "items": {
 *           "$ref": "#/definitions/ExtractionField",
 *           "default": null
 *         }
 *       }
 *     }
 *   }
 * }
 * ```
 * */
class ExtractionSchema(val fields: List<ExtractionField>) {
    /**
     * Convert the internal representation to a standard JSON Schema (draft-agnostic) string.
     */
    fun toJsonSchema(): String {
        val mapper = ObjectMapper()
        val root = JsonNodeFactory.instance.objectNode()
        root.put("type", "object")
        val propsNode = JsonNodeFactory.instance.objectNode()
        val required = mutableListOf<String>()
        fields.forEach { f ->
            propsNode.set<ObjectNode>(f.name, f.toJsonSchemaNode(mapper))
            if (f.required) required += f.name
        }
        root.set<ObjectNode>("properties", propsNode)
        if (required.isNotEmpty()) root.set<ArrayNode>("required", mapper.valueToTree<ArrayNode>(required))
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root)
    }

    companion object {
        private val mapper = jacksonObjectMapper()

        /** Default rich extraction schema (JSON Schema string) */
        @JsonIgnore
        val DEFAULT: ExtractionSchema =
            ExtractionSchema(
                listOf(
                    ExtractionField("title", type = "string", description = "Page title"),
                    ExtractionField(
                        "content",
                        type = "string",
                        description = "Primary textual content of the page",
                        required = false
                    ), ExtractionField(
                        name = "links",
                        type = "array",
                        description = "Important hyperlinks on the page",
                        required = false,
                        arrayElements = ExtractionField(
                            name = "link", type = "object", objectMemberProperties = listOf(
                                ExtractionField("text", type = "string", description = "Anchor text", required = false),
                                ExtractionField("href", type = "string", description = "Href URL", required = false)
                            ), required = false
                        )
                    )
                )
            )

        fun parse(json: String): ExtractionSchema {
            val root = mapper.readTree(json)
            require(root.isObject) { "Extraction schema must be a JSON object" }

            return if (root.has("fields")) {
                fromFieldSchema(root)
            } else {
                fromJsonSchema(root)
            }
        }

        /** Utility adapter: build a schema from a legacy Map<String,String> where value = description */
        fun fromMap(map: Map<*, *>): ExtractionSchema {
            val fields = map.entries.map { (k, v) ->
                ExtractionField(
                    name = k.toString(),
                    description = v.toString(),
                    required = false
                )
            }
            return ExtractionSchema(fields)
        }

        private fun fromFieldSchema(root: JsonNode): ExtractionSchema {
            val fieldsNode = root.get("fields")
            require(fieldsNode != null && fieldsNode.isArray) { "Extraction schema 'fields' must be an array" }

            val fields = fieldsNode.mapIndexed { index, fieldNode ->
                parseFieldDefinition(fieldNode, fallbackName = "field${index + 1}")
            }
            return ExtractionSchema(fields)
        }

        private fun fromJsonSchema(root: JsonNode): ExtractionSchema {
            val rootType = inferType(root)
            if (rootType != "object") {
                return ExtractionSchema(listOf(jsonSchemaNodeToField("result", root, required = true)))
            }

            val properties = root.get("properties")
            if (properties == null || !properties.isObject) {
                return ExtractionSchema(emptyList())
            }

            val required = root.get("required")
                ?.takeIf { it.isArray }
                ?.mapTo(linkedSetOf()) { it.asText() }
                ?: emptySet()

            val fields = properties.properties().asSequence().map { (name, node) ->
                jsonSchemaNodeToField(name, node, name in required)
            }.toList()

            return ExtractionSchema(fields)
        }

        private fun parseFieldDefinition(node: JsonNode, fallbackName: String, requiredFallback: Boolean = true): ExtractionField {
            require(node.isObject) { "Extraction field definition must be an object" }

            val name = node.get("name")
                ?.takeIf { !it.isNull && it.asText().isNotBlank() }
                ?.asText()
                ?: fallbackName
            val type = node.get("type")
                ?.takeIf { !it.isNull && it.asText().isNotBlank() }
                ?.asText()
                ?: inferType(node)
            val description = node.get("description")?.takeIf { !it.isNull }?.asText()
            val required = node.get("required")?.takeIf { !it.isNull }?.asBoolean() ?: requiredFallback
            val objectMemberProperties = if (type == "object") parseFieldChildren(node, name) else emptyList()
            val arrayElements = if (type == "array") parseFieldArrayElements(name, node) else null

            return ExtractionField(
                name = name,
                type = type,
                description = description,
                required = required,
                objectMemberProperties = objectMemberProperties,
                arrayElements = arrayElements,
            )
        }

        private fun jsonSchemaNodeToField(name: String, node: JsonNode, required: Boolean): ExtractionField {
            val type = inferType(node)
            val description = node.get("description")?.takeIf { !it.isNull }?.asText()
            val objectMemberProperties = if (type == "object") parseObjectProperties(node) else emptyList()
            val arrayElements = if (type == "array") parseArrayElements(name, node.get("items")) else null

            return ExtractionField(
                name = name,
                type = type,
                description = description,
                required = required,
                objectMemberProperties = objectMemberProperties,
                arrayElements = arrayElements,
            )
        }

        private fun parseFieldChildren(node: JsonNode, ownerName: String): List<ExtractionField> {
            val childNode = node.get("objectMemberProperties")
                ?: node.get("properties")
                ?: node.get("fields")
                ?: return emptyList()

            return when {
                childNode.isArray -> childNode.mapIndexed { index, child ->
                    parseFieldDefinition(child, fallbackName = "${ownerName}Field${index + 1}")
                }

                childNode.isObject -> {
                    val required = node.get("required")
                        ?.takeIf { it.isArray }
                        ?.mapTo(linkedSetOf()) { it.asText() }
                        ?: emptySet()
                    childNode.properties().asSequence().map { (name, child) ->
                        jsonSchemaNodeToField(name, child, name in required)
                    }.toList()
                }

                else -> emptyList()
            }
        }

        private fun parseFieldArrayElements(parentName: String, node: JsonNode): ExtractionField? {
            val itemsNode = node.get("arrayElements") ?: node.get("items") ?: return null
            if (!itemsNode.isObject) {
                return null
            }

            return parseFieldDefinition(itemsNode, fallbackName = "${parentName}Item", requiredFallback = false)
        }

        private fun parseObjectProperties(node: JsonNode): List<ExtractionField> {
            val properties = node.get("properties")
            if (properties == null || !properties.isObject) {
                return emptyList()
            }

            val required = node.get("required")
                ?.takeIf { it.isArray }
                ?.mapTo(linkedSetOf()) { it.asText() }
                ?: emptySet()

            return properties.properties().asSequence().map { (name, child) ->
                jsonSchemaNodeToField(name, child, name in required)
            }.toList()
        }

        private fun parseArrayElements(parentName: String, itemsNode: JsonNode?): ExtractionField? {
            if (itemsNode == null || itemsNode.isNull) {
                return null
            }

            val itemName = itemsNode.get("name")
                ?.takeIf { !it.isNull && it.asText().isNotBlank() }
                ?.asText()
                ?: "${parentName}Item"

            return jsonSchemaNodeToField(itemName, itemsNode, required = false)
        }

        private fun inferType(node: JsonNode): String {
            val explicitType = node.get("type")
                ?.takeIf { !it.isNull && !it.asText().isNullOrBlank() }
                ?.asText()
            if (explicitType != null) {
                return explicitType
            }

            return when {
                node.has("objectMemberProperties") -> "object"
                node.has("properties") && node.get("properties")?.let { it.isObject || it.isArray } == true -> "object"
                node.has("fields") -> "object"
                node.has("arrayElements") -> "array"
                node.has("items") -> "array"
                else -> "string"
            }
        }
    }
}
