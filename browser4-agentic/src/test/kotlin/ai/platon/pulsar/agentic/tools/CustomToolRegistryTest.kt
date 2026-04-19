package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.reflect.KClass
import org.junit.jupiter.api.DisplayName

/**
 * Tests for CustomToolRegistry functionality.
 */
class CustomToolRegistryTest {

    private lateinit var registry: CustomToolRegistry

    @BeforeEach
    fun setup() {
        registry = CustomToolRegistry.instance
        registry.clear()
    }

    @AfterEach
    fun cleanup() {
        registry.clear()
    }

    @Test
        @DisplayName("test register custom tool executor")
    fun testRegisterCustomToolExecutor() {
        val executor = TestToolExecutor()

        registry.register(executor)

        assertTrue(registry.contains("test"))
        assertEquals(1, registry.size())
        assertEquals(executor, registry.get("test"))
    }

    @Test
        @DisplayName("test register duplicate domain throws exception")
    fun testRegisterDuplicateDomainThrowsException() {
        val executor1 = TestToolExecutor()
        val executor2 = TestToolExecutor()

        registry.register(executor1)

        val exception = assertThrows<IllegalArgumentException> {
            registry.register(executor2)
        }
        assertTrue(exception.message!!.contains("already registered"))
    }

    @Test
        @DisplayName("test unregister custom tool executor")
    fun testUnregisterCustomToolExecutor() {
        val executor = TestToolExecutor()
        registry.register(executor)

        val removed = registry.unregister("test")

        assertTrue(removed)
        assertFalse(registry.contains("test"))
        assertEquals(0, registry.size())
    }

    @Test
        @DisplayName("test unregister non-existent domain returns false")
    fun testUnregisterNonExistentDomainReturnsFalse() {
        val removed = registry.unregister("nonexistent")

        assertFalse(removed)
    }

    @Test
        @DisplayName("test get all executors")
    fun testGetAllExecutors() {
        val executor1 = TestToolExecutor()
        val executor2 = AnotherTestToolExecutor()

        registry.register(executor1)
        registry.register(executor2)

        val executors = registry.getAllExecutors()
        assertEquals(2, executors.size)
        assertTrue(executors.contains(executor1))
        assertTrue(executors.contains(executor2))
    }

    @Test
        @DisplayName("test get all domains")
    fun testGetAllDomains() {
        registry.register(TestToolExecutor())
        registry.register(AnotherTestToolExecutor())

        val domains = registry.getAllDomains()
        assertEquals(2, domains.size)
        assertTrue(domains.contains("test"))
        assertTrue(domains.contains("another"))
    }

    @Test
        @DisplayName("test clear all executors")
    fun testClearAllExecutors() {
        registry.register(TestToolExecutor())
        registry.register(AnotherTestToolExecutor())

        registry.clear()

        assertEquals(0, registry.size())
        assertFalse(registry.contains("test"))
        assertFalse(registry.contains("another"))
    }

    @Test
        @DisplayName("test register executor with blank domain fails")
    fun testRegisterExecutorWithBlankDomainFails() = runBlocking {
        val executor = BlankDomainToolExecutor()

        val exception = assertThrows<IllegalArgumentException> {
            registry.register(executor)
        }
        assertTrue(exception.message!!.contains("must not be blank"))
    }

    @Test
        @DisplayName("test custom tool executor execution")
    fun testCustomToolExecutorExecution() = runBlocking {
        val executor = TestToolExecutor()
        val toolCall = ToolCall("test", "echo", mutableMapOf("message" to "Hello"))

        val result = executor.callFunctionOn(toolCall)

        assertEquals("Hello", result.value)
        assertTrue(result.expression?.startsWith("test.echo") ?: false)
    }

    @Test
        @DisplayName("test custom tool executor with invalid arguments")
    fun testCustomToolExecutorWithInvalidArguments() = runBlocking {
        val executor = TestToolExecutor()
        val target = TestTarget()
        val toolCall = ToolCall("test", "echo", mutableMapOf())

        val result = executor.callFunctionOn(toolCall, target)

        assertTrue(result.exception != null)
        assertTrue(result.exception?.cause is IllegalArgumentException)
    }

    // Test helper classes

    class TestToolExecutor : AbstractToolExecutor() {
        override val domain = "test"
        override val receiverClass: KClass<*> = TestTarget::class

        override suspend fun callFunctionOn(
            domain: String,
            functionName: String,
            args: Map<String, Any?>,
            receiver: Any
        ): Any? {
            // require(target is TestTarget) { "Target must be TestTarget" }
            return when (functionName) {
                "echo" -> {
                    validateArgs(args, setOf("message"), setOf("message"), functionName)
                    paramString(args, "message", functionName)
                }
                else -> throw IllegalArgumentException("Unknown method: $functionName")
            }
        }
    }

    class AnotherTestToolExecutor : AbstractToolExecutor() {
        override val domain = "another"
        override val receiverClass: KClass<*> = TestTarget::class

        override suspend fun callFunctionOn(
            domain: String,
            functionName: String,
            args: Map<String, Any?>,
            receiver: Any
        ): Any? {
            return "another"
        }
    }

    class BlankDomainToolExecutor : AbstractToolExecutor() {
        override val domain = ""
        override val receiverClass: KClass<*> = TestTarget::class

        override suspend fun callFunctionOn(
            domain: String,
            functionName: String,
            args: Map<String, Any?>,
            receiver: Any
        ): Any? {
            return null
        }
    }

    class TestTarget
}
