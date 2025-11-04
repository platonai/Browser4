package ai.platon.pulsar.agentic.support

import ai.platon.pulsar.agentic.ai.support.ActionExecutionService
import ai.platon.pulsar.skeleton.ai.ToolCall
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for ActionExecutionService to verify unified action execution.
 */
class ActionExecutionServiceTest {
    
    private val service = ActionExecutionService()
    
    @Test
    fun `parseExpression should parse simple driver calls`() {
        val result = service.parseExpression("driver.goBack()")
        
        assertNotNull(result)
        assertEquals("driver", result!!.domain)
        assertEquals("goBack", result.method)
        assertTrue(result.arguments.isEmpty())
    }
    
    @Test
    fun `parseExpression should parse calls with arguments`() {
        val result = service.parseExpression("driver.click(\"#button\")")
        
        assertNotNull(result)
        assertEquals("driver", result!!.domain)
        assertEquals("click", result.method)
        assertEquals("#button", result.arguments["0"])
    }
    
    @Test
    fun `parseExpression should parse browser calls`() {
        val result = service.parseExpression("browser.switchTab(\"tab1\")")
        
        assertNotNull(result)
        assertEquals("browser", result!!.domain)
        assertEquals("switchTab", result.method)
        assertEquals("tab1", result.arguments["0"])
    }
    
    @Test
    fun `parseExpression should handle empty input`() {
        val result = service.parseExpression("")
        assertNull(result)
    }
    
    @Test
    fun `parseExpression should handle invalid expressions`() {
        val result = service.parseExpression("invalid expression")
        assertNull(result)
    }
    
    @Test
    fun `convertToExpression should convert simple tool calls`() {
        val toolCall = ToolCall("driver", "goBack", mutableMapOf())
        val expression = service.convertToExpression(toolCall)
        
        assertNotNull(expression)
        assertEquals("driver.goBack()", expression)
    }
    
    @Test
    fun `convertToExpression should convert tool calls with selector`() {
        val toolCall = ToolCall("driver", "click", mutableMapOf("selector" to "#button"))
        val expression = service.convertToExpression(toolCall)
        
        assertNotNull(expression)
        assertTrue(expression!!.contains("#button"))
    }
    
    @Test
    fun `convertToExpression should convert navigateTo with URL`() {
        val toolCall = ToolCall("driver", "navigateTo", mutableMapOf("url" to "https://example.com"))
        val expression = service.convertToExpression(toolCall)
        
        assertNotNull(expression)
        assertTrue(expression!!.contains("https://example.com"))
    }
    
    @Test
    fun `convertToExpression should handle browser switchTab`() {
        val toolCall = ToolCall("browser", "switchTab", mutableMapOf("tabId" to "tab123"))
        val expression = service.convertToExpression(toolCall)
        
        assertNotNull(expression)
        assertEquals("browser.switchTab(\"tab123\")", expression)
    }
    
    @Test
    fun `validate should accept valid tool calls`() {
        val toolCall = ToolCall("driver", "click", mutableMapOf("selector" to "#button"))
        val isValid = service.validate(toolCall)
        
        assertTrue(isValid)
    }
    
    @Test
    fun `roundtrip conversion should preserve tool call information`() {
        val originalToolCall = ToolCall("driver", "scrollToBottom", mutableMapOf())
        
        // Convert to expression
        val expression = service.convertToExpression(originalToolCall)
        assertNotNull(expression)
        
        // Parse back to ToolCall
        val parsedToolCall = service.parseExpression(expression!!)
        assertNotNull(parsedToolCall)
        
        // Verify roundtrip
        assertEquals(originalToolCall.domain, parsedToolCall!!.domain)
        assertEquals(originalToolCall.method, parsedToolCall.method)
    }
    
    @Test
    fun `convertToExpression should handle special characters in selectors`() {
        val toolCall = ToolCall("driver", "click", mutableMapOf(
            "selector" to "#my-button[data-test=\"value\"]"
        ))
        val expression = service.convertToExpression(toolCall)
        
        assertNotNull(expression)
        // Should properly escape quotes
        assertTrue(expression!!.contains("\\\""))
    }
    
    @Test
    fun `convertToExpression should handle scroll methods with parameters`() {
        val toolCall = ToolCall("driver", "scrollToMiddle", mutableMapOf("ratio" to "0.7"))
        val expression = service.convertToExpression(toolCall)
        
        assertNotNull(expression)
        assertTrue(expression!!.contains("0.7"))
    }
    
    @Test
    fun `convertToExpression should handle delay with millis`() {
        val toolCall = ToolCall("driver", "delay", mutableMapOf("millis" to "2000"))
        val expression = service.convertToExpression(toolCall)
        
        assertNotNull(expression)
        assertEquals("driver.delay(2000)", expression)
    }
}
