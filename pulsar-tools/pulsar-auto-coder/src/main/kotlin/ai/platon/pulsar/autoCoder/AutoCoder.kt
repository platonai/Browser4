package ai.platon.pulsar.autoCoder

import ai.platon.pulsar.common.code.ProjectUtils
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.nameWithoutExtension

/**
 * A tool to generate code with AI assistance.
 *
 * AI module to help you to generate code:
 * - `ai.platon.pulsar.external`, example: `ai.platon.pulsar.external.ChatModelTests`.
 *
 * Tools to recommend:
 * - `ai.platon.pulsar.common.code.ProjectUtils`, example: `ai.platon.pulsar.common.code.ProjectUtilsTest`.
 *
 * Project structure assumptions:
 * - The project is a Maven project with standard directory layout.
 * - Source code is located in `src/main/kotlin` and test code in `src/test/kotlin`.
 * - The package structure in the source and test directories mirrors the fully qualified class names.
 * - Use mvnw instead of mvn to run maven commands, it's located in the root directory of the project.
 *   - example: cd "D:\workspace\Browser4-4.0.x" && .\mvnw.cmd compile -pl pulsar-tools/pulsar-auto-coder
 *
 * Unit test generation guidelines:
 * - Use testing framework JUnit5.
 * - Use assertion library kotlin.test and JUnit5, e.g. `kotlin.test.assertTrue`, `org.junit.jupiter.api.Assertions.assertEquals`.
 * - Each test method should be annotated with `@Test`.
 * - Each test method should have a descriptive name indicating what it tests.
 * - Each test method should be independent and not rely on the execution order of other tests.
 * - Use setup and teardown methods annotated with `@BeforeEach` and `@AfterEach` if needed.
 * - Unless asked explicitly, *DO NOT* use mock objects.
 * - Ensure high code coverage, aiming for at least 80% coverage of the target class
 * - Each test should include assertions to verify the expected outcomes.
 * - Use meaningful variable names to enhance code readability.
 * - Follow the project's coding style and conventions.
 * - Include comments to explain complex logic or decisions in the test code.
 * - Ensure that tests run quickly to facilitate frequent execution.
 * - Handle exceptions and edge cases to ensure robustness.
 * - Use parameterized tests where applicable to test multiple scenarios with different inputs.
 * - Regularly review and refactor test code to maintain quality and relevance.
 */
open class AutoCoder(
    private val projectRootDir: Path = ProjectUtils.findProjectRootDir() ?: Paths.get("."),
    private val testOutputDir: String = "src/test/kotlin"
) {

    private val logger = org.slf4j.LoggerFactory.getLogger(AutoCoder::class.java)

    /**
     * Configuration for test generation
     */
    data class TestGenerationConfig(
        val includePrivateMethods: Boolean = false,
        val generateMockTests: Boolean = false,
        val targetCoveragePercent: Int = 80,
        val useParameterizedTests: Boolean = true
    )

    /**
     * Generate unit tests for the specified class.
     *
     * @param className Fully qualified class name (e.g., "ai.platon.pulsar.common.collect.DataCollectors")
     * @throws IllegalArgumentException if the class cannot be found
     */
    fun generateUnitTests(className: String) {
        logger.info("Generating unit tests for class: $className")

        // 1. Find the class in the project
        val classFile = findClassFile(className)
        if (classFile == null) {
            logger.error("Class file not found for: $className")
            throw IllegalArgumentException("Class file not found for: $className")
        }

        // 2. Analyze the class structure and methods
        val classInfo = analyzeClassStructure(classFile)

        // 3. Generate unit test code for each method
        val testCode = generateTestCode(classInfo)

        // 4. Save the generated unit test code to a file
        val testFile = createTestFilePath(className)
        saveTestCode(testFile, testCode)

        logger.info("Unit tests generated successfully for: $className")
        logger.info("Test file created at: $testFile")
    }

    /**
     * Generate unit tests for the specified function.
     *
     * @param functionName Fully qualified function name or function signature
     * @throws IllegalArgumentException if the function cannot be found
     */
    fun generateUnitTestsForFunction(functionName: String) {
        logger.info("Generating unit tests for function: $functionName")

        // 1. Find the function in the project
        val functionInfo = findFunction(functionName)
        if (functionInfo == null) {
            logger.error("Function not found: $functionName")
            throw IllegalArgumentException("Function not found: $functionName")
        }

        // 2. Analyze the function structure and parameters
        val analyzedFunction = analyzeFunctionStructure(functionInfo)

        // 3. Generate unit test code for the function
        val testCode = generateFunctionTestCode(analyzedFunction)

        // 4. Save the generated unit test code to a file
        val testFile = createFunctionTestFilePath(functionName)
        saveTestCode(testFile, testCode)

        logger.info("Unit tests generated successfully for function: $functionName")
    }

    /**
     * Generate unit tests for each class in the specified package.
     *
     * @param packageName Package name (e.g., "ai.platon.pulsar.common.collect")
     * @throws IllegalArgumentException if the package cannot be found
     */
    fun generateUnitTestsForPackage(packageName: String) {
        logger.info("Generating unit tests for package: $packageName")

        // 1. Find all classes in the package
        val classes = findClassesInPackage(packageName)
        if (classes.isEmpty()) {
            logger.warn("No classes found in package: $packageName")
            return
        }

        logger.info("Found ${classes.size} classes in package: $packageName")

        // 2. For each class, analyze the structure and methods
        // 3. Generate unit test code for each method in each class
        // 4. Save the generated unit test code to files
        classes.forEach { classFile ->
            try {
                val className = extractClassName(classFile)
                logger.info("Processing class: $className from file: ${classFile.fileName}")
                generateUnitTestsFromFile(classFile, className)
            } catch (e: Exception) {
                logger.error("Failed to generate tests for class in file: ${classFile.fileName}", e)
            }
        }

        logger.info("Unit test generation completed for package: $packageName")
    }

    /**
     * Generate unit tests for a class using the actual file path
     */
    private fun generateUnitTestsFromFile(classFile: Path, className: String) {
        logger.info("Generating unit tests for class: $className")

        // 2. Analyze the class structure and methods
        val classInfo = analyzeClassStructure(classFile)

        // 3. Generate unit test code for each method
        val testCode = generateTestCode(classInfo)

        // 4. Save the generated unit test code to a file
        val testFile = createTestFilePath(className)
        saveTestCode(testFile, testCode)

        logger.info("Unit tests generated successfully for: $className")
        logger.info("Test file created at: $testFile")
    }

    /**
     * Find a class file by fully qualified class name
     */
    private fun findClassFile(className: String): Path? {
        val classPath = className.replace('.', '/') + ".kt"

        return Files.walk(projectRootDir)
            .filter { it.toString().endsWith(classPath) && !it.toString().contains("/test/") }
            .findFirst()
            .orElse(null)
    }

    /**
     * Find all Kotlin class files in the specified package
     */
    private fun findClassesInPackage(packageName: String): List<Path> {
        val packagePath = packageName.replace('.', '/')

        logger.info("Searching for classes in package: $packageName")
        logger.info("Package path: $packagePath")
        logger.info("Project root: $projectRootDir")

        val foundFiles = mutableListOf<Path>()
        var totalFilesChecked = 0
        var kotlinFilesFound = 0
        var srcMainKotlinFound = 0
        var packagePathMatches = 0

        Files.walk(projectRootDir).use { stream ->
            stream.forEach { path ->
                totalFilesChecked++
                val pathStr = path.toString()

                // Use platform-independent path checks
                val normalizedPath = pathStr.replace('\\', '/')
                val normalizedPackagePath = packagePath

                // Debug specific conditions
                val isSrcMainKotlin = normalizedPath.contains("src/main/kotlin")
                val isKotlinFile = pathStr.endsWith(".kt")
                val hasPackagePath = normalizedPath.contains(normalizedPackagePath)
                val isNotDirectory = !path.isDirectory()

                if (isKotlinFile) kotlinFilesFound++
                if (isSrcMainKotlin) srcMainKotlinFound++
                if (hasPackagePath) packagePathMatches++

                // Log some sample matching paths for debugging
                if ((isKotlinFile && hasPackagePath) || (totalFilesChecked <= 10)) {
                    logger.info("Checking path: $pathStr")
                    logger.info("  - normalized: $normalizedPath")
                    logger.info("  - src/main/kotlin: $isSrcMainKotlin")
                    logger.info("  - ends with .kt: $isKotlinFile")
                    logger.info("  - contains package path: $hasPackagePath")
                    logger.info("  - not directory: $isNotDirectory")
                }

                if (isSrcMainKotlin && hasPackagePath && isKotlinFile && isNotDirectory) {
                    logger.info("Found matching file: $path")
                    foundFiles.add(path)
                }
            }
        }

        logger.info("Search summary:")
        logger.info("  - Total files checked: $totalFilesChecked")
        logger.info("  - Kotlin files found: $kotlinFilesFound")
        logger.info("  - src/main/kotlin paths: $srcMainKotlinFound")
        logger.info("  - Package path matches: $packagePathMatches")
        logger.info("  - Final matches: ${foundFiles.size}")

        return foundFiles
    }

    /**
     * Extract fully qualified class name from file path
     */
    private fun extractClassName(classFile: Path): String {
        val pathStr = classFile.toString().replace('\\', '/')

        // Find the src/main/kotlin part and extract the package path from there
        val srcMainKotlinIndex = pathStr.indexOf("src/main/kotlin/")
        if (srcMainKotlinIndex != -1) {
            val packagePath = pathStr.substring(srcMainKotlinIndex + "src/main/kotlin/".length)
            // Remove .kt extension and convert slashes to dots
            return packagePath.removeSuffix(".kt").replace('/', '.')
        }

        // Fallback to just the filename without extension
        return classFile.nameWithoutExtension
    }

    /**
     * Analyze class structure and extract methods, properties, etc.
     */
    private fun analyzeClassStructure(classFile: Path): ClassInfo {
        val content = Files.readString(classFile)
        val className = classFile.nameWithoutExtension

        // Basic parsing - in a real implementation, you'd use a proper Kotlin parser
        val methods = extractMethods(content)
        val properties = extractProperties(content)
        val imports = extractImports(content)
        val packageName = extractPackageName(content)

        return ClassInfo(
            name = className,
            packageName = packageName,
            filePath = classFile,
            methods = methods,
            properties = properties,
            imports = imports
        )
    }

    /**
     * Generate test code for a class
     */
    private fun generateTestCode(classInfo: ClassInfo): String {
        val testClassName = "${classInfo.name}Test"

        return buildString {
            appendLine("package ${classInfo.packageName}")
            appendLine()
            appendLine("import org.junit.jupiter.api.Test")
            appendLine("import org.junit.jupiter.api.BeforeEach")
            appendLine("import org.junit.jupiter.api.AfterEach")
            appendLine("import org.junit.jupiter.api.Assertions.*")
            appendLine("import kotlin.test.assertTrue")
            appendLine("import kotlin.test.assertFalse")
            appendLine("import kotlin.test.assertNotNull")
            appendLine("import kotlin.test.assertNull")
            appendLine()

            // Add additional imports based on the original class
            classInfo.imports.forEach { import ->
                if (shouldIncludeImport(import)) {
                    appendLine(import)
                }
            }
            appendLine()

            appendLine("/**")
            appendLine(" * Unit tests for ${classInfo.name}")
            appendLine(" * Generated by AutoCoder")
            appendLine(" */")
            appendLine("class $testClassName {")
            appendLine()
            appendLine("    private lateinit var testInstance: ${classInfo.name}")
            appendLine()
            appendLine("    @BeforeEach")
            appendLine("    fun setUp() {")
            appendLine("        testInstance = ${classInfo.name}()")
            appendLine("    }")
            appendLine()
            appendLine("    @AfterEach")
            appendLine("    fun tearDown() {")
            appendLine("        // Clean up resources if needed")
            appendLine("    }")
            appendLine()

            // Generate test methods for each public method
            classInfo.methods.forEach { method ->
                if (method.isPublic && !method.isConstructor) {
                    appendLine(generateTestMethod(method))
                    appendLine()
                }
            }

            appendLine("}")
        }
    }

    /**
     * Generate a test method for a specific method
     */
    private fun generateTestMethod(method: MethodInfo): String {
        val testMethodName = "test${method.name.replaceFirstChar { it.uppercase() }}"

        return buildString {
            appendLine("    @Test")
            appendLine("    fun $testMethodName() {")
            appendLine("        // Arrange")
            appendLine("        // TODO: Set up test data and expected results")
            appendLine()
            appendLine("        // Act")
            if (method.returnType != "Unit") {
                appendLine("        val result = testInstance.${method.name}(${generateMethodParameters(method)})")
            } else {
                appendLine("        testInstance.${method.name}(${generateMethodParameters(method)})")
            }
            appendLine()
            appendLine("        // Assert")
            if (method.returnType != "Unit") {
                appendLine("        assertNotNull(result)")
                appendLine("        // TODO: Add specific assertions based on expected behavior")
            } else {
                appendLine("        // TODO: Add assertions to verify the method's side effects")
            }
            appendLine("    }")
        }
    }

    /**
     * Generate method parameters for test calls
     */
    private fun generateMethodParameters(method: MethodInfo): String {
        return method.parameters.joinToString(", ") { param ->
            when (param.type.lowercase()) {
                "string" -> "\"test\""
                "int", "integer" -> "1"
                "long" -> "1L"
                "double" -> "1.0"
                "float" -> "1.0f"
                "boolean" -> "true"
                "list" -> "emptyList()"
                "map" -> "emptyMap()"
                "set" -> "emptySet()"
                else -> "null" // For custom types, use null and let the developer handle it
            }
        }
    }

    /**
     * Create test file path based on class name
     */
    private fun createTestFilePath(className: String): Path {
        val testPath = className.replace('.', '/') + "Test.kt"
        return projectRootDir.resolve(testOutputDir).resolve(testPath)
    }

    /**
     * Create test file path for function tests
     */
    private fun createFunctionTestFilePath(functionName: String): Path {
        val sanitizedName = functionName.replace(".", "_").replace("::", "_")
        val testPath = "${sanitizedName}Test.kt"
        return projectRootDir.resolve(testOutputDir).resolve("generated").resolve(testPath)
    }

    /**
     * Save generated test code to file
     */
    private fun saveTestCode(testFile: Path, testCode: String) {
        // Create directories if they don't exist
        Files.createDirectories(testFile.parent)

        // Write test code to file
        Files.writeString(testFile, testCode)

        logger.info("Test code saved to: $testFile")
    }

    // Helper methods for parsing (simplified implementations)

    private fun extractMethods(content: String): List<MethodInfo> {
        val methods = mutableListOf<MethodInfo>()
        val methodRegex = Regex("""(public|private|protected|internal)?\s*fun\s+(\w+)\s*\([^)]*\)(?:\s*:\s*(\w+))?""")

        methodRegex.findAll(content).forEach { match ->
            val visibility = match.groups[1]?.value ?: "public"
            val name = match.groups[2]?.value ?: ""
            val returnType = match.groups[3]?.value ?: "Unit"

            methods.add(MethodInfo(
                name = name,
                returnType = returnType,
                isPublic = visibility == "public" || visibility.isEmpty(),
                isConstructor = false,
                parameters = emptyList() // Simplified - would need proper parsing
            ))
        }

        return methods
    }

    private fun extractProperties(content: String): List<PropertyInfo> {
        val properties = mutableListOf<PropertyInfo>()
        val propertyRegex = Regex("""(val|var)\s+(\w+)\s*:\s*(\w+)""")

        propertyRegex.findAll(content).forEach { match ->
            val name = match.groups[2]?.value ?: ""
            val type = match.groups[3]?.value ?: ""
            val isReadOnly = match.groups[1]?.value == "val"

            properties.add(PropertyInfo(
                name = name,
                type = type,
                isReadOnly = isReadOnly
            ))
        }

        return properties
    }

    private fun extractImports(content: String): List<String> {
        val imports = mutableListOf<String>()
        val importRegex = Regex("""import\s+[^\n]+""")

        importRegex.findAll(content).forEach { match ->
            imports.add(match.value)
        }

        return imports
    }

    private fun extractPackageName(content: String): String {
        val packageRegex = Regex("""package\s+([^\n]+)""")
        return packageRegex.find(content)?.groups?.get(1)?.value?.trim() ?: ""
    }

    private fun shouldIncludeImport(import: String): Boolean {
        // Filter out imports that are not needed in tests
        val excludePatterns = listOf(
            "import java.util.*",
            "import kotlin.collections.*"
        )
        return !excludePatterns.any { import.contains(it) }
    }

    // Placeholder implementations for function-specific methods

    private fun findFunction(@Suppress("UNUSED_PARAMETER") functionName: String): FunctionInfo? {
        // TODO: Implement function finding logic
        return null
    }

    private fun analyzeFunctionStructure(functionInfo: FunctionInfo): FunctionInfo {
        // TODO: Implement function analysis
        return functionInfo
    }

    private fun generateFunctionTestCode(functionInfo: FunctionInfo): String {
        // TODO: Implement function test generation
        return "// Function test code for ${functionInfo.name}"
    }

    // Data classes for representing code structure

    data class ClassInfo(
        val name: String,
        val packageName: String,
        val filePath: Path,
        val methods: List<MethodInfo>,
        val properties: List<PropertyInfo>,
        val imports: List<String>
    )

    data class MethodInfo(
        val name: String,
        val returnType: String,
        val isPublic: Boolean,
        val isConstructor: Boolean,
        val parameters: List<ParameterInfo>
    )

    data class ParameterInfo(
        val name: String,
        val type: String,
        val hasDefault: Boolean = false
    )

    data class PropertyInfo(
        val name: String,
        val type: String,
        val isReadOnly: Boolean
    )

    data class FunctionInfo(
        val name: String,
        val returnType: String,
        val parameters: List<ParameterInfo>
    )
}

fun main() {
    val autoCoder = AutoCoder()
    val packageName = "ai.platon.pulsar.common.collect"

    // Debug: Print the project root directory being used
    println("Project root directory: ${autoCoder.javaClass.getDeclaredField("projectRootDir").let { 
        it.isAccessible = true
        it.get(autoCoder)
    }}")

    try {
        autoCoder.generateUnitTestsForPackage(packageName)
        println("Unit test generation completed successfully!")
    } catch (e: Exception) {
        println("Error during test generation: ${e.message}")
        e.printStackTrace()
    }
}
