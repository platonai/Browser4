package ai.platon.pulsar.agentic.inference.action

import ai.platon.pulsar.agentic.model.ExtractionField
import ai.platon.pulsar.agentic.model.ExtractionSchema
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ExtractionSchemaTest {

    private val mapper = ObjectMapper()

    @Test
        @DisplayName("simple string field produces required and description")
    fun simpleStringFieldProducesRequiredAndDescription() {
        val schema = ExtractionSchema(
            listOf(
                ExtractionField.string("title", description = "The title", required = true)
            )
        )
        val json = schema.toJsonSchema()
        val root = mapper.readTree(json)

        Assertions.assertEquals("object", root.get("type").asText())
        val props = root.get("properties")
        assertTrue(props.has("title"))
        Assertions.assertEquals("string", props.get("title").get("type").asText())
        Assertions.assertEquals("The title", props.get("title").get("description").asText())
        val required = root.get("required")
        Assertions.assertNotNull(required)
        assertTrue(required.any { it.asText() == "title" })
    }

    @Test
        @DisplayName("object with nested array renders children and item schema")
    fun objectWithNestedArrayRendersChildrenAndItemSchema() {
        val item = ExtractionField.obj(
            name = "item",
            objectMemberProperties = listOf(
                ExtractionField.string("name"),
                ExtractionField.string("sku", required = false)
            ),
            required = false
        )
        val field = ExtractionField.obj(
            name = "product",
            objectMemberProperties = listOf(
                ExtractionField.arrayOf("variants", item = item)
            )
        )
        val schema = ExtractionSchema(listOf(field))
        val jsonSchema = schema.toJsonSchema()
        val root = mapper.readTree(jsonSchema)

        val product = root.get("properties").get("product")
        Assertions.assertEquals("object", product.get("type").asText())
        val variants = product.get("properties").get("variants")
        Assertions.assertEquals("array", variants.get("type").asText())
        val items = variants.get("items")
        Assertions.assertEquals("object", items.get("type").asText())
        val itemProps = items.get("properties")
        assertTrue(itemProps.has("name"))
        assertTrue(itemProps.has("sku"))
        // child required list should include 'name' only
        val childReq = items.get("required")
        Assertions.assertNotNull(childReq)
        assertTrue(childReq.any { it.asText() == "name" })
        assertFalse(childReq.any { it.asText() == "sku" })
    }

    @Test
        @DisplayName("array without items falls back to string item type")
    fun arrayWithoutItemsFallsBackToStringItemType() {
        val field = ExtractionField(
            name = "tags",
            type = "array",
            arrayElements = null
        )
        val schema = ExtractionSchema(listOf(field))
        val root = mapper.readTree(schema.toJsonSchema())
        val tags = root.get("properties").get("tags")
        Assertions.assertEquals("array", tags.get("type").asText())
        Assertions.assertEquals("string", tags.get("items").get("type").asText())
    }

    @Test
        @DisplayName("When parse from JSON string Then success")
    fun whenParseFromJsonStringThenSuccess() {
        val json = """
{"fields":[{"name":"articles","type":"array","description":"文章列表","arrayElements":{"name":"article","type":"object","objectMemberProperties":[{"name":"title","type":"string","description":"文章标题","required":true},{"name":"comments","type":"number","description":"评论数量","required":true}]}}]}

        """.trimIndent()

        val schema = ExtractionSchema.parse(json)
        assertEquals(1, schema.fields.size)
        assertEquals("articles", schema.fields.first().name)
    }

    @Test
        @DisplayName("parse from JSON string")
    fun parseFromJsonString() {
        val json = """
            {
              "fields" : [ {
                "name" : "title",
                "type" : "string",
                "description" : "文章标题"
              }, {
                "name" : "comments",
                "type" : "integer",
                "description" : "评论数量，从评论链接中提取数字部分"
              } ]
            }
        """.trimIndent()

        val schema = ExtractionSchema.parse(json)
        assertEquals(2, schema.fields.size)
        assertEquals("title", schema.fields[0].name)
        assertEquals("integer", schema.fields[1].type)
    }

    // Kotlin
    @Test
        @DisplayName("parse from JSON string with nested fields")
    fun parseFromJsonStringWithNestedFields() {
        val json = """
        {
          "fields": [
            {
              "name": "product",
              "type": "object",
              "description": "Product info",
              "objectMemberProperties": [
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
                  "arrayElements": {
                    "name": "variant",
                    "type": "object",
                    "required": false,
                    "objectMemberProperties": [
                      { "name": "sku", "type": "string", "required": false },
                      { "name": "price", "type": "number", "required": false }
                    ]
                  }
                }
              ]
            }
          ]
        }
    """.trimIndent()

        val schema = ExtractionSchema.parse(json)
        assertEquals(1, schema.fields.size)

        val product = schema.fields[0]
        assertEquals("product", product.name)
        assertEquals("object", product.type)
        Assertions.assertEquals("Product info", product.description)

        // properties under object
        assertEquals(2, product.objectMemberProperties.size)
        val nameField = product.objectMemberProperties.first { it.name == "name" }
        assertEquals("string", nameField.type)
        assertTrue(nameField.required)

        val variantsField = product.objectMemberProperties.first { it.name == "variants" }
        assertEquals("array", variantsField.type)
        assertFalse(variantsField.required)
        Assertions.assertNotNull(variantsField.arrayElements)

        // items under array
        val item = variantsField.arrayElements!!
        assertEquals("variant", item.name)
        assertEquals("object", item.type)
        assertFalse(item.required)
        assertTrue(item.objectMemberProperties.any { it.name == "sku" && it.type == "string" && !it.required })
        assertTrue(item.objectMemberProperties.any { it.name == "price" && it.type == "number" && !it.required })
    }

    // Kotlin
    @Test
    fun `parse from JSON string `() {
        val json = """
{
  "fields": [
    {
      "name": "articles",
      "type": "array",
      "description": "文章列表",
      "items": {
        "type": "object",
        "fields": [
          {
            "name": "title",
            "type": "string",
            "description": "文章标题",
            "required": true
          },
          {
            "name": "comments",
            "type": "string",
            "description": "评论数",
            "required": true
          }
        ]
      }
    }
  ]
}
        """.trimIndent()

        val schema = ExtractionSchema.parse(json)
        assertEquals(1, schema.fields.size)
        val articles = schema.fields.first()
        assertEquals("articles", articles.name)
        assertEquals("array", articles.type)
        assertEquals("object", articles.arrayElements?.type)
        val articleItem = requireNotNull(articles.arrayElements)
        assertTrue(articleItem.name.isNotBlank())
        assertTrue(articleItem.objectMemberProperties.any { it.name == "title" && it.required })
        assertTrue(articleItem.objectMemberProperties.any { it.name == "comments" && it.required })
    }

    @Test
    @DisplayName("standard JSON Schema root object parses into extraction fields")
    fun standardJsonSchemaRootObjectParsesIntoExtractionFields() {
        val json = """
            {
              "type": "object",
              "properties": {
                "productName": {
                  "type": "string",
                  "description": "Product name"
                },
                "offers": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "price": {
                        "type": "number",
                        "description": "Offer price"
                      }
                    },
                    "required": ["price"]
                  }
                }
              },
              "required": ["productName"]
            }
        """.trimIndent()

        val schema = ExtractionSchema.parse(json)
        assertEquals(2, schema.fields.size)
        val productName = schema.fields.first { it.name == "productName" }
        assertEquals("string", productName.type)
        assertTrue(productName.required)

        val offers = schema.fields.first { it.name == "offers" }
        assertEquals("array", offers.type)
        assertEquals("offersItem", offers.arrayElements?.name)
        assertEquals("object", offers.arrayElements?.type)
        assertTrue(offers.arrayElements!!.objectMemberProperties.any { it.name == "price" && it.type == "number" && it.required })
    }

    @Test
    @DisplayName("minimal JSON Schema object without properties parses as empty schema")
    fun minimalJsonSchemaObjectWithoutPropertiesParsesAsEmptySchema() {
        val schema = ExtractionSchema.parse("""{"type":"object"}""")

        assertTrue(schema.fields.isEmpty())
        val root = mapper.readTree(schema.toJsonSchema())
        assertEquals("object", root.get("type").asText())
        assertEquals(0, root.get("properties").size())
    }

    @Test
        @DisplayName("legacy map adapter marks fields optional and sets descriptions")
    fun legacyMapAdapterMarksFieldsOptionalAndSetsDescriptions() {
        val map = mapOf(
            "title" to "Title text",
            "price" to "Price number"
        )
        val schema = ExtractionSchema.fromMap(map)
        val root = mapper.readTree(schema.toJsonSchema())

        val props = root.get("properties")
        Assertions.assertEquals("Title text", props.get("title").get("description").asText())
        Assertions.assertEquals("Price number", props.get("price").get("description").asText())
        // no required array expected since legacy fields are optional by default
        assertFalse(root.has("required"))
    }
}
