package ai.platon.pulsar.autoCoder

import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.*

/**
 * Test class to demonstrate AutoCoder functionality
 */
class AutoCoderDemoTest {

    @Test
    fun testAutoCoderCompilation() {
        // Test that AutoCoder can be instantiated without errors
        val autoCoder = AutoCoder()
        assertNotNull(autoCoder)
    }

    @Test
    fun testAutoCoderWithSimpleClass() {
        // Create a simple test project path
        val testProjectPath = Paths.get("src/test/resources/test-project")

        // Create AutoCoder instance with the test project
        val autoCoder = AutoCoder(
            projectRootDir = testProjectPath,
            config = ai.platon.pulsar.common.config.ImmutableConfig(loadDefaults = true)
        )

        // Test finding classes in the test project
        val classes = autoCoder.findClassesInPackage("com.example")

        // Verify that we found the SimpleService class
        assertTrue(classes.isNotEmpty(), "Should find classes in com.example package")

        val simpleServiceFile = classes.find { it.fileName.toString() == "SimpleService.kt" }
        assertNotNull(simpleServiceFile, "Should find SimpleService.kt file")
    }

    @Test
    fun testClassStructureAnalysis() {
        val testProjectPath = Paths.get("src/test/resources/test-project")
        val autoCoder = AutoCoder(
            projectRootDir = testProjectPath,
            config = ai.platon.pulsar.common.config.ImmutableConfig(loadDefaults = true)
        )

        // Find the SimpleService class
        val classes = autoCoder.findClassesInPackage("com.example")
        val simpleServiceFile = classes.find { it.fileName.toString() == "SimpleService.kt" } ?: return

        // Debug: Print file content
        val content = java.nio.file.Files.readString(simpleServiceFile)
        println("File content:\n$content")

        // Analyze the class structure
        val classInfo = autoCoder.analyzeClassStructure(simpleServiceFile)

        // Debug: Print class info details
        println("Class name: ${classInfo.name}")
        println("Package name: ${classInfo.packageName}")
        println("Methods found: ${classInfo.methods.size}")
        classInfo.methods.forEach { method ->
            println("  Method: ${method.name}(${method.parameters.joinToString { "${it.name}: ${it.type}" }}) -> ${method.returnType}")
        }

        // Verify class info
        assertEquals("SimpleService", classInfo.name)
        assertEquals("com.example", classInfo.packageName)
        assertTrue(classInfo.methods.isNotEmpty(), "Should find methods in SimpleService")

        // Check for specific methods
        val methodNames = classInfo.methods.map { it.name }
        assertTrue(methodNames.contains("add"), "Should find add method")
        assertTrue(methodNames.contains("subtract"), "Should find subtract method")
        assertTrue(methodNames.contains("isPositive"), "Should find isPositive method")
    }

    @Test
    fun testTestFilePathCreation() {
        val autoCoder = AutoCoder()

        // Test creating test file path for a class
        val testFilePath = autoCoder.createTestFilePath("com.example.SimpleService")

        // The path should end with the correct test file name
        assertTrue(testFilePath.toString().endsWith("SimpleServiceAITest.kt"))
        assertTrue(testFilePath.toString().contains("src/test/kotlin"))
        assertTrue(testFilePath.toString().contains("com/example"))
    }

    @Test
    fun testTestResultDataClass() {
        val testResult = AutoCoder.TestResult(
            className = "TestClass",
            isSuccessful = true,
            successCount = 5,
            failureCount = 0,
            coverage = 85,
            errorMessage = null
        )

        assertEquals("TestClass", testResult.className)
        assertTrue(testResult.isSuccessful)
        assertEquals(5, testResult.successCount)
        assertEquals(0, testResult.failureCount)
        assertEquals(85, testResult.coverage)
        assertNull(testResult.errorMessage)
    }
}