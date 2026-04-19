package ai.platon.pulsar.agentic.tools.examples

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.tools.CustomToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.DisplayName

/**
 * Tests for the example CalculatorToolExecutor.
 */
class CalculatorToolExecutorTest {

    private lateinit var executor: CalculatorToolExecutor
    private lateinit var calculator: Calculator
    private lateinit var registry: CustomToolRegistry

    @BeforeEach
    fun setup() {
        executor = CalculatorToolExecutor()
        calculator = Calculator()
        registry = CustomToolRegistry.instance
        registry.clear()
    }

    @AfterEach
    fun cleanup() {
        registry.clear()
    }

    @Test
        @DisplayName("test calculator add operation")
    fun testCalculatorAddOperation() = runBlocking {
        val toolCall = ToolCall("calc", "add", mutableMapOf("a" to "5.0", "b" to "3.0"))

        val result = executor.callFunctionOn(toolCall, calculator)

        assertEquals(8.0, result.value as Double, 0.001)
    }

    @Test
        @DisplayName("test calculator subtract operation")
    fun testCalculatorSubtractOperation() = runBlocking {
        val toolCall = ToolCall("calc", "subtract", mutableMapOf("a" to "10.0", "b" to "4.0"))

        val result = executor.callFunctionOn(toolCall, calculator)

        assertEquals(6.0, result.value as Double, 0.001)
    }

    @Test
        @DisplayName("test calculator multiply operation")
    fun testCalculatorMultiplyOperation() = runBlocking {
        val toolCall = ToolCall("calc", "multiply", mutableMapOf("a" to "7.0", "b" to "6.0"))

        val result = executor.callFunctionOn(toolCall, calculator)

        assertEquals(42.0, result.value as Double, 0.001)
    }

    @Test
        @DisplayName("test calculator divide operation")
    fun testCalculatorDivideOperation() = runBlocking {
        val toolCall = ToolCall("calc", "divide", mutableMapOf("a" to "20.0", "b" to "4.0"))

        val result = executor.callFunctionOn(toolCall, calculator)

        assertEquals(5.0, result.value as Double, 0.001)
    }

    @Test
        @DisplayName("test calculator divide by zero fails")
    fun testCalculatorDivideByZeroFails() = runBlocking {
        val toolCall = ToolCall("calc", "divide", mutableMapOf("a" to "10.0", "b" to "0.0"))

        val result = executor.callFunctionOn(toolCall, calculator)

        assertTrue(result.exception != null)
        assertTrue(result.exception?.cause is IllegalArgumentException)
        assertTrue(result.exception?.cause?.message?.contains("Division by zero") ?: false)
    }

    @Test
        @DisplayName("test calculator with missing arguments")
    fun testCalculatorWithMissingArguments() = runBlocking {
        val toolCall = ToolCall("calc", "add", mutableMapOf("a" to "5.0"))

        val result = executor.callFunctionOn(toolCall, calculator)

        assertTrue(result.exception != null)
        assertTrue(result.exception?.cause is IllegalArgumentException)
        assertTrue(result.exception?.cause?.message?.contains("Missing required parameter 'b'") ?: false)
    }

    @Test
        @DisplayName("test calculator with extra arguments")
    fun testCalculatorWithExtraArguments() = runBlocking {
        val toolCall = ToolCall("calc", "add", mutableMapOf("a" to "5.0", "b" to "3.0", "c" to "1.0"))

        val result = executor.callFunctionOn(toolCall, calculator)

        assertTrue(result.exception != null)
        assertTrue(result.exception?.cause is IllegalArgumentException)
        assertTrue(result.exception?.cause?.message?.contains("Extraneous parameter") ?: false)
    }

    @Test
        @DisplayName("test calculator with invalid method")
    fun testCalculatorWithInvalidMethod() = runBlocking {
        val toolCall = ToolCall("calc", "power", mutableMapOf("a" to "2.0", "b" to "3.0"))

        val result = executor.callFunctionOn(toolCall, calculator)

        assertTrue(result.exception != null)
        assertTrue(result.exception?.cause is IllegalArgumentException)
        assertTrue(result.exception?.cause?.message?.contains("Unsupported calc method") ?: false)
    }

    @Test
        @DisplayName("test calculator registration in registry")
    fun testCalculatorRegistrationInRegistry() {
        registry.register(executor)

        assertTrue(registry.contains("calc"))
        assertEquals(executor, registry.get("calc"))
    }

    @Test
        @DisplayName("test calculator with string arguments are converted to doubles")
    fun testCalculatorWithStringArgumentsAreConvertedToDoubles() = runBlocking {
        // The paramDouble function should handle string-to-double conversion
        val toolCall = ToolCall("calc", "add", mutableMapOf("a" to "5.5", "b" to "3.5"))

        val result = executor.callFunctionOn(toolCall, calculator)

        assertEquals(9.0, result.value as Double, 0.001)
    }

    @Test
        @DisplayName("test calculator domain property")
    fun testCalculatorDomainProperty() {
        assertEquals("calc", executor.domain)
    }

    @Test
        @DisplayName("test calculator target class property")
    fun testCalculatorTargetClassProperty() {
        assertEquals(Calculator::class, executor.receiverClass)
    }

    @Test
        @DisplayName("test calculator implementation directly")
    fun testCalculatorImplementationDirectly() {
        val calc = Calculator()

        assertEquals(8.0, calc.add(5.0, 3.0), 0.001)
        assertEquals(2.0, calc.subtract(5.0, 3.0), 0.001)
        assertEquals(15.0, calc.multiply(5.0, 3.0), 0.001)
        assertEquals(2.5, calc.divide(5.0, 2.0), 0.001)
    }

    @Test
        @DisplayName("test calculator divide by zero in implementation")
    fun testCalculatorDivideByZeroInImplementation() {
        val calc = Calculator()

        val exception = assertThrows<IllegalArgumentException> {
            calc.divide(10.0, 0.0)
        }
        assertTrue(exception.message!!.contains("Division by zero"))
    }

    @Test
        @DisplayName("help returns available calculator methods")
    fun helpReturnsAvailableCalculatorMethods() {
        val help = executor.help()

        assertTrue(help.isNotBlank())
        assertTrue(help.contains("Add two numbers") || help.contains("add"))
    }

    @Test
        @DisplayName("help for add returns detailed help")
    fun helpForAddReturnsDetailedHelp() {
        val help = executor.help("add")

        assertTrue(help.contains("Add two numbers"))
    }

    @Test
        @DisplayName("help for all calculator methods is available")
    fun helpForAllCalculatorMethodsIsAvailable() {
        val methods = listOf("add", "subtract", "multiply", "divide")

        methods.forEach { method ->
            val help = executor.help(method)
            org.junit.jupiter.api.Assertions.assertNotNull(help, "Help for $method should not be null")
            assertTrue(help.isNotBlank(), "Help for $method should not be blank")
        }
    }

    @Test
        @DisplayName("help for unknown method returns empty string")
    fun helpForUnknownMethodReturnsEmptyString() {
        val help = executor.help("unknownMethod")
        assertEquals("", help)
    }
}
