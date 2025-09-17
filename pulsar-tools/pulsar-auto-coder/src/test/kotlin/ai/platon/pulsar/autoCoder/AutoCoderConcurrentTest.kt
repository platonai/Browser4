package ai.platon.pulsar.autoCoder

import ai.platon.pulsar.common.config.ImmutableConfig
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.*

/**
 * Test AutoCoder with ConcurrentPassiveExpiringSet container class
 */
class AutoCoderConcurrentTest {

    @Test
    fun testAutoCoderWithConcurrentPassiveExpiringSet() {
        println("=== Testing AutoCoder with ConcurrentPassiveExpiringSet ===")

        // Create AutoCoder instance targeting pulsar-common
        val projectRoot = Paths.get("/home/vincent/workspace/browser4")
        val autoCoder = AutoCoder(
            projectRootDir = projectRoot,
            config = ImmutableConfig(loadDefaults = true)
        )

        // Test class name
        val className = "ai.platon.pulsar.common.concurrent.ConcurrentPassiveExpiringSet"

        println("Generating tests for class: $className")

        try {
            // Generate tests for the specific class
            autoCoder.generateTestsForClass(className)

            // Get results summary
            val results = autoCoder.getTestResultsSummary()
            val result = results[className]

            println("\n=== Test Generation Results ===")
            if (result != null) {
                println("Class: ${result.className}")
                println("Success: ${result.isSuccessful}")
                println("Tests Passed: ${result.successCount}")
                println("Tests Failed: ${result.failureCount}")
                println("Coverage: ${result.coverage}%")
                if (result.errorMessage != null) {
                    println("Error: ${result.errorMessage}")
                }
            } else {
                println("No test results available for $className")
            }

            // Verify that the class was found and processed
            val classFile = autoCoder.findClassByName(className)
            assertNotNull(classFile, "Should find ConcurrentPassiveExpiringSet class")

            // Analyze the class structure
            val classInfo = autoCoder.analyzeClassStructure(classFile!!)

            println("\n=== Class Structure Analysis ===")
            println("Class name: ${classInfo.name}")
            println("Package name: ${classInfo.packageName}")
            println("Methods found: ${classInfo.methods.size}")

            // Verify key methods are detected
            val methodNames = classInfo.methods.map { it.name }
            assertTrue(methodNames.contains("add"), "Should find add method")
            assertTrue(methodNames.contains("remove"), "Should find remove method")
            assertTrue(methodNames.contains("contains"), "Should find contains method")
            assertTrue(methodNames.contains("iterator"), "Should find iterator method")
            assertTrue(methodNames.contains("clear"), "Should find clear method")

            println("Key methods detected: ${methodNames.filter { it in listOf("add", "remove", "contains", "iterator", "clear") }}")

            // Test that test file path is created correctly
            val testFilePath = autoCoder.createTestFilePath(className)
            println("\nTest file would be created at: $testFilePath")
            assertTrue(testFilePath.toString().endsWith("ConcurrentPassiveExpiringSetAITest.kt"))

            println("\n✅ AutoCoder test with ConcurrentPassiveExpiringSet completed successfully!")

        } catch (e: Exception) {
            println("❌ Error during AutoCoder test: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    @Test
    fun testConcurrentPassiveExpiringSetMethods() {
        println("=== Testing ConcurrentPassiveExpiringSet Method Detection ===")

        val projectRoot = Paths.get("/home/vincent/workspace/browser4")
        val autoCoder = AutoCoder(
            projectRootDir = projectRoot,
            config = ImmutableConfig(loadDefaults = true)
        )

        val className = "ai.platon.pulsar.common.concurrent.ConcurrentPassiveExpiringSet"
        val classFile = autoCoder.findClassByName(className)

        assertNotNull(classFile, "Should find ConcurrentPassiveExpiringSet class")

        val classInfo = autoCoder.analyzeClassStructure(classFile!!)

        println("Analyzing ${classInfo.name} methods:")
        classInfo.methods.forEach { method ->
            println("  - ${method.name}(${method.parameters.joinToString { "${it.name}: ${it.type}" }}) -> ${method.returnType}")
        }

        // Verify specific container methods
        val expectedMethods = setOf("add", "remove", "contains", "clear", "size", "isEmpty", "iterator", "addAll", "removeAll", "retainAll", "containsAll", "equals", "hashCode")
        val foundMethods = classInfo.methods.map { it.name }.toSet()

        val missingMethods = expectedMethods - foundMethods
        val foundExpectedMethods = expectedMethods.intersect(foundMethods)

        println("\nExpected methods found: ${foundExpectedMethods.size}/${expectedMethods.size}")
        println("Found: $foundExpectedMethods")
        if (missingMethods.isNotEmpty()) {
            println("Missing: $missingMethods")
        }

        // Key container methods should be present
        assertTrue(foundMethods.contains("add"), "Container should have add method")
        assertTrue(foundMethods.contains("remove"), "Container should have remove method")
        assertTrue(foundMethods.contains("contains"), "Container should have contains method")
        assertTrue(foundMethods.contains("iterator"), "Container should have iterator method")

        println("✅ ConcurrentPassiveExpiringSet method detection test completed!")
    }
}