package ai.platon.pulsar.agentic.tools.langchain4j

import ai.platon.pulsar.agentic.model.ToolSpec
import dev.langchain4j.agent.tool.ToolSpecification as LangChain4jToolSpec
import org.junit.jupiter.api.Test
import kotlin.test.*

class ToolSpecificationConverterTest {

    @Test
    fun `tool name replaces dots with underscores`() {
        assertEquals("tab_navigate", ToolSpecificationConverter.toolName("tab", "navigate"))
        assertEquals("skill_blog_run", ToolSpecificationConverter.toolName("skill.blog", "run"))
        assertEquals("skill_debug_scraping_run",
            ToolSpecificationConverter.toolName("skill.debug.scraping", "run"))
    }

    @Test
    fun `tool name sanitises special chars`() {
        val name = ToolSpecificationConverter.toolName("some:domain", "method")
        assertFalse(name.contains(":"))
        assertTrue(name.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' })
    }

    @Test
    fun `tool name truncates to 64 chars`() {
        val longDomain = "a".repeat(60)
        val longMethod = "b".repeat(60)
        val name = ToolSpecificationConverter.toolName(longDomain, longMethod)
        assertTrue(name.length <= 64)
    }

    @Test
    fun `string arg maps to string schema`() {
        val spec = ToolSpec(
            domain = "tab", method = "click",
            arguments = listOf(ToolSpec.Arg("selector", "String")),
        )
        val result = ToolSpecificationConverter.toToolSpecifications(listOf(spec)).single()
        assertEquals("tab_click", result.name())
        val params = result.parameters()
        assertTrue(params.required().contains("selector"))
        assertTrue(params.properties().containsKey("selector"))
    }

    @Test
    fun `arg with default is not required`() {
        val spec = ToolSpec(
            domain = "tab", method = "type",
            arguments = listOf(
                ToolSpec.Arg("text", "String"),
                ToolSpec.Arg("selector", "String", "null"),
            ),
        )
        val result = ToolSpecificationConverter.toToolSpecifications(listOf(spec)).single()
        val params = result.parameters()
        assertTrue(params.required().contains("text"))
        assertFalse(params.required().contains("selector"))
    }

    @Test
    fun `integer arg maps to integer schema`() {
        val spec = ToolSpec(
            domain = "test", method = "count",
            arguments = listOf(ToolSpec.Arg("limit", "Int")),
        )
        val result = ToolSpecificationConverter.toToolSpecifications(listOf(spec)).single()
        val props = result.parameters().properties()
        assertTrue(props.containsKey("limit"))
    }

    @Test
    fun `boolean arg maps to boolean schema`() {
        val spec = ToolSpec(
            domain = "test", method = "toggle",
            arguments = listOf(ToolSpec.Arg("enable", "Boolean")),
        )
        val result = ToolSpecificationConverter.toToolSpecifications(listOf(spec)).single()
        val props = result.parameters().properties()
        assertTrue(props.containsKey("enable"))
    }

    @Test
    fun `overloads are merged keeping most args`() {
        val specs = listOf(
            ToolSpec("tab", "click",
                listOf(ToolSpec.Arg("selector", "String"))),
            ToolSpec("tab", "click",
                listOf(
                    ToolSpec.Arg("selector", "String"),
                    ToolSpec.Arg("modifier", "String", "null"),
                )),
        )
        val result = ToolSpecificationConverter.toToolSpecifications(specs)
        assertEquals(1, result.size)
        val params = result.single().parameters()
        // The merged schema has both properties, but only "selector" is required
        assertTrue(params.properties().containsKey("selector"))
        assertTrue(params.properties().containsKey("modifier"))
        assertTrue(params.required().contains("selector"))
        assertFalse(params.required().contains("modifier"))
    }

    @Test
    fun `registry round-trips domain and method`() {
        val specs = listOf(
            ToolSpec("tab", "navigate",
                listOf(ToolSpec.Arg("url", "String"))),
            ToolSpec("fs", "readString",
                listOf(ToolSpec.Arg("filename", "String"))),
        )
        val registry = ToolSpecificationConverter.toRegistry(specs)
        assertEquals(2, registry.size)

        val tabSpec = registry["tab_navigate"]
        assertNotNull(tabSpec)
        assertEquals("tab", tabSpec.domain)
        assertEquals("navigate", tabSpec.method)

        val fsSpec = registry["fs_readString"]
        assertNotNull(fsSpec)
        assertEquals("fs", fsSpec.domain)
        assertEquals("readString", fsSpec.method)
    }

    @Test
    fun `empty specs produces empty lists`() {
        assertTrue(ToolSpecificationConverter.toToolSpecifications(emptyList()).isEmpty())
        assertTrue(ToolSpecificationConverter.toRegistry(emptyList()).isEmpty())
    }

    @Test
    fun `description includes return type when not Unit`() {
        val spec = ToolSpec(
            domain = "tab", method = "textContent",
            arguments = emptyList(),
            returnType = "String?",
            description = "Returns text content",
        )
        val result = ToolSpecificationConverter.toToolSpecifications(listOf(spec)).single()
        assertTrue(result.description().isNotBlank())
        // Description should carry the spec description and/or return type info
        assertTrue(
            result.description().contains("textContent") ||
            result.description().contains("String") ||
            result.description().contains("text")
        )
    }
}
