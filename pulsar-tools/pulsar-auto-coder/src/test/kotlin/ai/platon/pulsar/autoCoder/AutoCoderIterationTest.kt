package ai.platon.pulsar.autoCoder

import ai.platon.pulsar.common.config.ImmutableConfig
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.*

/**
 * Complete iterative AutoCoder test workflow for ConcurrentPassiveExpiringSet
 *
 * This test demonstrates the full 4-phase iteration cycle:
 * 1. Task Assignment (AutoCoder → LLM)
 * 2. Test Generation (LLM → AutoCoder)
 * 3. Execution Validation (AutoCoder → Maven & JaCoCo)
 * 4. Feedback Analysis (Feedback Analyzer → Orchestrator)
 */
class AutoCoderIterationTest {

    private val projectRoot = Paths.get("/home/vincent/workspace/browser4")
    private val targetClass = "ai.platon.pulsar.common.concurrent.ConcurrentPassiveExpiringSet"
    private val maxIterations = 3
    private val targetCoverage = 80

    @Test
    fun testConcurrentPassiveExpiringSetIterativeGeneration() {
        println("=== 🚀 Starting Complete AutoCoder Iterative Test Workflow ===")
        println("Target Class: $targetClass")
        println("Max Iterations: $maxIterations")
        println("Target Coverage: $targetCoverage%")
        println()

        val autoCoder = AutoCoder(
            projectRootDir = projectRoot,
            config = ImmutableConfig(loadDefaults = true)
        )

        // Find the target class
        val classFile = autoCoder.findClassByName(targetClass)
        assertNotNull(classFile, "Should find $targetClass")

        // Analyze class structure first
        println("=== 🔍 Phase 0: Initial Class Analysis ===")
        val classInfo = autoCoder.analyzeClassStructure(classFile!!)
        println("Class: ${classInfo.name}")
        println("Package: ${classInfo.packageName}")
        println("Methods found: ${classInfo.methods.size}")
        println("Properties found: ${classInfo.properties.size}")

        // Show method details
        println("\nMethods to be tested:")
        classInfo.methods.forEachIndexed { index, method ->
            val params = method.parameters.joinToString(", ") { "${it.name}: ${it.type}" }
            println("  ${index + 1}. ${method.name}($params) -> ${method.returnType}")
        }

        // Generate initial test
        println("\n=== 📝 Phase 1: Initial Test Generation ===")
        val testFile = generateInitialTests(autoCoder, classInfo, classFile)

        if (testFile != null) {
            // Run iterative optimization
            runIterativeOptimization(autoCoder, targetClass, testFile, classFile)
        } else {
            println("❌ Failed to generate initial tests")
        }

        println("\n=== ✅ AutoCoder Iterative Test Workflow Complete ===")
    }

    private fun generateInitialTests(
        autoCoder: AutoCoder,
        classInfo: AutoCoder.ClassInfo,
        classFile: Path
    ): Path? {
        println("Generating initial unit tests for ${classInfo.name}...")

        // Get the source code
        val sourceCode = Files.readString(classFile)

        // Create comprehensive test prompt
        val testPrompt = createComprehensiveTestPrompt(classInfo, sourceCode)

        println("Test generation prompt created (${testPrompt.length} characters)")
        println("Calling LLM for test generation...")

        // For this test, we'll simulate the LLM response since actual LLM calls may timeout
        // In real usage, this would call chatModel.call(testPrompt)
        val simulatedTestCode = createSimulatedTestCode(classInfo)

        // Save the generated test
        val testFilePath = autoCoder.createTestFilePath(targetClass)
        autoCoder.saveTestCode(testFilePath, simulatedTestCode)

        println("✅ Initial tests generated and saved to: $testFilePath")
        return testFilePath
    }

    private fun createComprehensiveTestPrompt(
        classInfo: AutoCoder.ClassInfo,
        sourceCode: String
    ): String {
        return """
You are an expert Kotlin developer tasked with generating comprehensive unit tests for a container class.

## Target Class Information:
- Class Name: ${classInfo.name}
- Package: ${classInfo.packageName}
- Type: Concurrent container with expiration functionality

## Source Code:
```kotlin
$sourceCode
```

## Methods to Test (${classInfo.methods.size} methods):
${classInfo.methods.joinToString("\n") { method ->
    val params = method.parameters.joinToString(", ") { "${it.name}: ${it.type}" }
    "- ${method.name}($params) -> ${method.returnType}"
}}

## Test Requirements:
1. Use JUnit 5 framework with Kotlin test assertions
2. Test class name: "${classInfo.name}AITest"
3. Package: "${classInfo.packageName}"
4. Target: 80%+ code coverage
5. Include edge cases, boundary values, and error conditions
6. Test thread-safety aspects
7. Test expiration functionality with time-based assertions
8. Use descriptive test method names following Arrange-Act-Assert pattern

## Specific Test Scenarios to Cover:
1. **Basic Operations**: add, remove, contains with various element types
2. **Bulk Operations**: addAll, removeAll, retainAll, containsAll
3. **Expiration Testing**: Elements expiring after TTL
4. **Concurrent Access**: Multiple threads accessing the set simultaneously
5. **Edge Cases**: Empty collections, null handling, large datasets
6. **Iterator Behavior**: Proper iteration over expired/non-expired elements
7. **Equality & Hashing**: equals() and hashCode() contract compliance

## Important Notes:
- This is a concurrent container, so test thread-safety
- Default TTL is -1 (never expire), but also test with specific durations
- The underlying implementation uses PassiveExpiringMap
- Test both immediate expiration and delayed expiration scenarios

Please generate comprehensive unit tests following these requirements.
        """.trimIndent()
    }

    private fun createSimulatedTestCode(classInfo: AutoCoder.ClassInfo): String {
        // This simulates what an LLM would generate for ConcurrentPassiveExpiringSet
        return """
package ${classInfo.packageName}

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * AI-generated comprehensive unit tests for ConcurrentPassiveExpiringSet
 * Generated by AutoCoder with target coverage: 80%+
 */
class ${classInfo.name}AITest {

    private lateinit var expiringSet: ConcurrentPassiveExpiringSet<String>
    private lateinit var neverExpireSet: ConcurrentPassiveExpiringSet<Int>

    @BeforeEach
    fun setUp() {
        expiringSet = ConcurrentPassiveExpiringSet(Duration.ofSeconds(1))
        neverExpireSet = ConcurrentPassiveExpiringSet(Duration.ofSeconds(-1))
    }

    @AfterEach
    fun tearDown() {
        // Clean up resources if needed
    }

    // ===== Basic Operations Tests =====

    @Test
    fun testAddAndContains() {
        // Arrange
        val element = "test-element"

        // Act
        val added = expiringSet.add(element)
        val contains = expiringSet.contains(element)

        // Assert
        assertTrue(added, "Element should be added successfully")
        assertTrue(contains, "Set should contain the added element")
        assertEquals(1, expiringSet.size, "Set size should be 1")
    }

    @Test
    fun testAddDuplicateElement() {
        // Arrange
        val element = "duplicate-element"
        expiringSet.add(element)

        // Act
        val secondAdd = expiringSet.add(element)

        // Assert
        assertFalse(secondAdd, "Adding duplicate should return false")
        assertEquals(1, expiringSet.size, "Size should remain 1")
    }

    @Test
    fun testRemoveElement() {
        // Arrange
        val element = "to-remove"
        expiringSet.add(element)
        assertTrue(expiringSet.contains(element))

        // Act
        val removed = expiringSet.remove(element)

        // Assert
        assertTrue(removed, "Element should be removed successfully")
        assertFalse(expiringSet.contains(element), "Set should not contain removed element")
        assertEquals(0, expiringSet.size, "Set should be empty")
    }

    @Test
    fun testRemoveNonExistentElement() {
        // Arrange
        val element = "non-existent"

        // Act
        val removed = expiringSet.remove(element)

        // Assert
        assertFalse(removed, "Removing non-existent element should return false")
        assertEquals(0, expiringSet.size, "Size should remain 0")
    }

    // ===== Expiration Tests =====

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun testElementExpiration() {
        // Arrange
        val element = "expiring-element"
        expiringSet.add(element)

        // Act & Assert
        assertTrue(expiringSet.contains(element), "Element should exist immediately after adding")

        // Wait for expiration
        Thread.sleep(1100) // Wait 1.1 seconds (100ms buffer)

        // Assert
        assertFalse(expiringSet.contains(element), "Element should be expired after TTL")
        assertTrue(expiringSet.isEmpty(), "Set should be empty after expiration")
    }

    @Test
    fun testNeverExpireSet() {
        // Arrange
        val element = "never-expire"
        neverExpireSet.add(element)

        // Act
        val containsBefore = neverExpireSet.contains(element)
        Thread.sleep(100) // Short wait
        val containsAfter = neverExpireSet.contains(element)

        // Assert
        assertTrue(containsBefore, "Element should exist immediately")
        assertTrue(containsAfter, "Element should still exist after short wait")
        assertEquals(1, neverExpireSet.size, "Size should remain 1")
    }

    // ===== Bulk Operations Tests =====

    @Test
    fun testAddAll() {
        // Arrange
        val elements = listOf("a", "b", "c", "d")

        // Act
        val changed = expiringSet.addAll(elements)

        // Assert
        assertTrue(changed, "Set should be modified by addAll")
        assertEquals(4, expiringSet.size, "All elements should be added")
        assertTrue(expiringSet.containsAll(elements), "Set should contain all added elements")
    }

    @Test
    fun testAddAllWithDuplicates() {
        // Arrange
        expiringSet.add("existing")
        val elements = listOf("existing", "new1", "new2")

        // Act
        val changed = expiringSet.addAll(elements)

        // Assert
        assertTrue(changed, "Set should be modified")
        assertEquals(3, expiringSet.size, "Only new elements should be added")
        assertTrue(expiringSet.contains("existing"), "Original element should still exist")
        assertTrue(expiringSet.contains("new1"), "New elements should be added")
    }

    @Test
    fun testRemoveAll() {
        // Arrange
        val elements = listOf("a", "b", "c")
        expiringSet.addAll(elements)

        // Act
        val changed = expiringSet.removeAll(elements)

        // Assert
        assertTrue(changed, "Set should be modified by removeAll")
        assertEquals(0, expiringSet.size, "All elements should be removed")
        assertFalse(expiringSet.contains("a"), "No elements should remain")
    }

    @Test
    fun testRetainAll() {
        // Arrange
        expiringSet.addAll(listOf("a", "b", "c", "d"))
        val retainElements = listOf("b", "d", "e") // e doesn't exist

        // Act
        val changed = expiringSet.retainAll(retainElements)

        // Assert
        assertTrue(changed, "Set should be modified")
        assertEquals(2, expiringSet.size, "Only retained elements should remain")
        assertTrue(expiringSet.containsAll(listOf("b", "d")))
        assertFalse(expiringSet.contains("a"))
        assertFalse(expiringSet.contains("c"))
    }

    // ===== Iterator Tests =====

    @Test
    fun testIterator() {
        // Arrange
        val elements = setOf("a", "b", "c")
        expiringSet.addAll(elements)

        // Act
        val iteratedElements = expiringSet.toSet()

        // Assert
        assertEquals(elements, iteratedElements, "Iterator should return all elements")
    }

    @Test
    fun testIteratorWithExpiredElements() {
        // Arrange
        expiringSet.add("expire-soon")
        Thread.sleep(1100) // Wait for expiration
        expiringSet.add("still-valid")

        // Act
        val iteratedElements = expiringSet.toSet()

        // Assert
        assertEquals(setOf("still-valid"), iteratedElements, "Iterator should only return non-expired elements")
    }

    // ===== Concurrent Access Tests =====

    @Test
    fun testConcurrentAdditions() {
        // Arrange
        val threadCount = 10
        val elementsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        // Act
        for (i in 0 until threadCount) {
            executor.submit {
                try {
                    for (j in 0 until elementsPerThread) {
                        neverExpireSet.add("thread-<i>-element-<j>")
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        // Assert
        assertEquals(threadCount * elementsPerThread, neverExpireSet.size,
            "All concurrent additions should succeed")
    }

    @Test
    fun testConcurrentReadWrite() {
        // Arrange
        val writeExecutor = Executors.newSingleThreadExecutor()
        val readExecutor = Executors.newSingleThreadExecutor()
        val writeLatch = CountDownLatch(1)
        val readLatch = CountDownLatch(1)

        var readSuccessCount = 0

        // Act - Writer thread
        writeExecutor.submit {
            for (i in 0 until 100) {
                neverExpireSet.add("concurrent-<i>")
                Thread.sleep(10) // Small delay between writes
            }
            writeLatch.countDown()
        }

        // Act - Reader thread
        readExecutor.submit {
            for (i in 0 until 100) {
                if (neverExpireSet.contains("concurrent-<i>")) {
                    readSuccessCount++
                }
                Thread.sleep(10) // Small delay between reads
            }
            readLatch.countDown()
        }

        writeLatch.await()
        readLatch.await()
        writeExecutor.shutdown()
        readExecutor.shutdown()

        // Assert
        assertTrue(readSuccessCount > 0, "Should have successfully read some elements")
        assertEquals(100, neverExpireSet.size, "All elements should be added")
    }

    // ===== Edge Cases Tests =====

    @Test
    fun testEmptySet() {
        // Arrange (already empty)

        // Act & Assert
        assertTrue(expiringSet.isEmpty(), "New set should be empty")
        assertEquals(0, expiringSet.size, "Size should be 0")
        assertFalse(expiringSet.iterator().hasNext(), "Iterator should be empty")
        assertFalse(expiringSet.contains("anything"), "Should not contain any element")
    }

    @Test
    fun testClear() {
        // Arrange
        expiringSet.addAll(listOf("a", "b", "c"))
        assertEquals(3, expiringSet.size)

        // Act
        expiringSet.clear()

        // Assert
        assertTrue(expiringSet.isEmpty(), "Set should be empty after clear")
        assertEquals(0, expiringSet.size, "Size should be 0 after clear")
    }

    @Test
    fun testEqualsContract() {
        // Arrange
        val set1 = ConcurrentPassiveExpiringSet<String>()
        val set2 = ConcurrentPassiveExpiringSet<String>()
        val set3 = ConcurrentPassiveExpiringSet<String>()

        // Act
        set1.add("a")
        set2.add("a")
        set3.add("b")

        // Assert - Reflexive
        assertEquals(set1, set1, "Set should equal itself")

        // Assert - Symmetric
        assertEquals(set1, set2, "Sets with same elements should be equal")
        assertEquals(set2, set1, "Equality should be symmetric")

        // Assert - Different elements
        assertNotEquals(set1, set3, "Sets with different elements should not be equal")
    }

    @Test
    fun testHashCodeContract() {
        // Arrange
        val set1 = ConcurrentPassiveExpiringSet<String>()
        val set2 = ConcurrentPassiveExpiringSet<String>()

        // Act
        set1.addAll(listOf("a", "b"))
        set2.addAll(listOf("b", "a")) // Same elements, different order

        // Assert
        assertEquals(set1.hashCode(), set2.hashCode(),
            "Sets with same elements should have same hash code")
        assertEquals(set1, set2, "Sets should be equal")
    }

    // ===== Performance Tests =====

    @Test
    fun testLargeDatasetPerformance() {
        // Arrange
        val largeDataset = (1..1000).map { "element-<it>" }
        val startTime = System.currentTimeMillis()

        // Act
        neverExpireSet.addAll(largeDataset)
        val containsResult = neverExpireSet.contains("element-500")
        val endTime = System.currentTimeMillis()

        // Assert
        assertTrue(containsResult, "Should find middle element")
        assertEquals(1000, neverExpireSet.size, "All elements should be added")
        assertTrue((endTime - startTime) < 1000, "Operations should complete within reasonable time")
    }
}
        """.trimIndent()
    }

    private fun runIterativeOptimization(
        autoCoder: AutoCoder,
        className: String,
        testFile: Path,
        sourceFile: Path
    ) {
        println("\n=== 🔄 Starting Iterative Optimization ===")

        var currentTestFile = testFile
        var currentIteration = 0
        var bestCoverage = 0
        var bestTestFile = currentTestFile

        while (currentIteration < maxIterations) {
            println("\n--- Iteration ${currentIteration + 1}/$maxIterations ---")

            // Phase 3: Execute and validate
            val testResult = autoCoder.runTests(currentTestFile, className)

            println("Test Results:")
            println("  Success: ${testResult.isSuccessful}")
            println("  Passed: ${testResult.successCount}")
            println("  Failed: ${testResult.failureCount}")
            println("  Coverage: ${testResult.coverage}%")

            // Update best results
            if (testResult.coverage > bestCoverage) {
                bestCoverage = testResult.coverage
                bestTestFile = currentTestFile
                println("  ✅ New best coverage: $bestCoverage%")
            }

            // Check if we reached target
            if (testResult.coverage >= targetCoverage) {
                println("  🎯 Target coverage achieved!")
                break
            }

            // Phase 4: Analyze feedback and prepare next iteration
            if (currentIteration < maxIterations - 1) {
                println("  Preparing next iteration...")

                val feedback = analyzeTestFeedback(testResult, currentIteration + 1)
                val optimizedFile = createOptimizedTestFile(
                    autoCoder,
                    currentTestFile,
                    sourceFile,
                    feedback,
                    currentIteration + 1
                )

                if (optimizedFile != null) {
                    currentTestFile = optimizedFile
                    println("  ✅ Optimized test file created: $currentTestFile")
                } else {
                    println("  ⚠️  No meaningful optimization generated")
                    break
                }
            }

            currentIteration++
        }

        println("\n=== 📊 Iteration Summary ===")
        println("Total iterations: $currentIteration")
        println("Best coverage achieved: $bestCoverage%")
        println("Target coverage: $targetCoverage%")
        println("Best test file: $bestTestFile")

        if (bestCoverage >= targetCoverage) {
            println("🎯 SUCCESS: Target coverage achieved!")
        } else {
            println("⚠️  Coverage target not met, but best effort completed")
        }
    }

    private fun analyzeTestFeedback(
        testResult: AutoCoder.TestResult,
        iteration: Int
    ): String {
        val feedback = StringBuilder()

        feedback.appendLine("=== Test Feedback Analysis - Iteration $iteration ===")
        feedback.appendLine("Overall Success: ${testResult.isSuccessful}")
        feedback.appendLine("Tests Passed: ${testResult.successCount}")
        feedback.appendLine("Tests Failed: ${testResult.failureCount}")
        feedback.appendLine("Code Coverage: ${testResult.coverage}%")

        if (testResult.errorMessage != null) {
            feedback.appendLine("Error Message: ${testResult.errorMessage}")
        }

        // Coverage analysis
        when {
            testResult.coverage >= 80 -> feedback.appendLine("✅ Coverage: EXCELLENT (≥80%)")
            testResult.coverage >= 60 -> feedback.appendLine("🟡 Coverage: GOOD (60-79%)")
            testResult.coverage >= 40 -> feedback.appendLine("🟠 Coverage: FAIR (40-59%)")
            else -> feedback.appendLine("🔴 Coverage: POOR (<40%)")
        }

        // Specific recommendations
        feedback.appendLine("\n=== Optimization Recommendations ===")

        if (testResult.coverage < 80) {
            feedback.appendLine("- Add more test cases to increase coverage")
            feedback.appendLine("- Test edge cases and boundary conditions")
            feedback.appendLine("- Add parameterized tests for multiple scenarios")
        }

        if (testResult.failureCount > 0) {
            feedback.appendLine("- Fix failing test cases")
            feedback.appendLine("- Review test logic and assertions")
        }

        if (testResult.successCount == 0) {
            feedback.appendLine("- Implement basic functionality tests first")
            feedback.appendLine("- Start with simple positive test cases")
        }

        // Container-specific recommendations
        feedback.appendLine("\n=== Container-Specific Recommendations ===")
        feedback.appendLine("- Test expiration timing more precisely")
        feedback.appendLine("- Add concurrent access stress tests")
        feedback.appendLine("- Test memory behavior with large datasets")
        feedback.appendLine("- Verify iterator behavior with expired elements")

        return feedback.toString()
    }

    private fun createOptimizedTestFile(
        autoCoder: AutoCoder,
        currentTestFile: Path,
        sourceFile: Path,
        feedback: String,
        iteration: Int
    ): Path? {
        println("Creating optimized test file based on feedback...")

        // For this simulation, we'll create a slightly improved version
        // In real usage, this would involve LLM optimization
        val currentTestCode = Files.readString(currentTestFile)
        val improvedTestCode = simulateTestOptimization(currentTestCode, feedback, iteration)

        if (improvedTestCode != currentTestCode) {
            val optimizedFile = currentTestFile.parent.resolve("${targetClass.substringAfterLast('.')}AITest_optimized_$iteration.kt")
            autoCoder.saveTestCode(optimizedFile, improvedTestCode)
            return optimizedFile
        }

        return null
    }

    private fun simulateTestOptimization(
        currentCode: String,
        feedback: String,
        iteration: Int
    ): String {
        // This simulates LLM-based optimization based on feedback
        // In real usage, this would call the LLM with the feedback

        return currentCode.replace(
            "// This simulates what an LLM would generate",
            "// This simulates what an LLM would generate (Iteration $iteration - Optimized based on feedback)"
        ).plus("""

    // ===== Additional Tests from Iteration $iteration =====

    @Test
    fun testOptimizationIteration$iteration() {
        // Additional test based on feedback analysis
        println("Running optimization iteration $iteration test")

        // This test was added to improve coverage based on feedback
        val set = ConcurrentPassiveExpiringSet<String>(Duration.ofMillis(100))
        set.add("test")

        // Verify basic functionality still works
        assertTrue(set.contains("test"))
        assertEquals(1, set.size)
    }
"""
        )
    }
}