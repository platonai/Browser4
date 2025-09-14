package ai.platon.pulsar.autoCoder

import ai.platon.pulsar.common.code.ProjectUtils
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.external.ChatModel
import ai.platon.pulsar.external.ChatModelFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.isDirectory
import kotlin.io.path.nameWithoutExtension

/**
 * A tool to generate code with AI assistance.
 *
 * AI assistant guidelines:
 * - If you are an AI coding assistant, such as GitHub Copilot, Claude Code, you must follow the guidelines below strictly.
 * - When you are working on this file, your role is a meta programmer, your task is to generate code that generates code.
 * - Do not generate unit test code directly, instead, generate code that uses LLM to generate unit tests.
 * - If you understand the guidelines, please reply with "Acknowledged. I am ready to assist as a meta programmer."
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
 *   - example: cd "D:\\workspace\\Browser4-4.0.x" && .\\mvnw.cmd compile -pl pulsar-tools/pulsar-auto-coder
 *
 * Unit test generation guidelines:
 * - Use testing framework JUnit5.
 * - Use assertion library kotlin.test and JUnit5, e.g. `kotlin.test.assertTrue`, `org.junit.jupiter.api.Assertions.assertEquals`.
 * - Each test method should be annotated with `@Test`.
 * - Each test method should have a descriptive name indicating what it tests.
 * - Each test method should be independent and not rely on the execution order of other tests.
 * - Use setup and teardown methods annotated with `@BeforeEach` and `@AfterEach` if needed.
 * - Unless asked explicitly, DO NOT use mock objects.
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
    private val testOutputDir: String = "src/test/kotlin",
    private val config: ImmutableConfig = ImmutableConfig(loadDefaults = true)
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(AutoCoder::class.java)

    private val chatModel: ChatModel = ChatModelFactory.getOrCreate(config)

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
        if (testCode.isBlank()) {
            logger.warn("No test code generated for class: $className")
            return
        }

        // 4. Save the generated unit test code to a file
        val testFile = createTestFilePath(className)
        saveTestCode(testFile, testCode)

        logger.info("Unit tests generated successfully for: $className")
        logger.info("Test file created at: $testFile")
    }

    /**
     * Create test file path for a class.
     */
    private fun createTestFilePath(className: String): Path {
        val lastDot = className.lastIndexOf('.')
        val pkg = if (lastDot > 0) className.substring(0, lastDot) else ""
        val simpleName = if (lastDot > 0) className.substring(lastDot + 1) else className
        val testDir = projectRootDir.resolve(testOutputDir)
        val pkgDir = if (pkg.isNotBlank()) testDir.resolve(pkg.replace('.', '/')) else testDir
        val fileName = "${simpleName}Test.kt"
        return pkgDir.resolve(fileName)
    }

    /** Save test code to disk, creating directories as needed. */
    private fun saveTestCode(testFile: Path, testCode: String) {
        Files.createDirectories(testFile.parent)
        Files.writeString(testFile, testCode)
        logger.info("Wrote test file: $testFile")
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
                val normalizedPath = pathStr.replace('\\', '/')
                val isSrcMainKotlin = normalizedPath.contains("src/main/kotlin")
                val isKotlinFile = normalizedPath.endsWith(".kt")
                val hasPackagePath = normalizedPath.contains(packagePath)
                val isNotDirectory = !path.isDirectory()

                if (isKotlinFile) kotlinFilesFound++
                if (isSrcMainKotlin) srcMainKotlinFound++
                if (hasPackagePath) packagePathMatches++

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
        val idx = pathStr.indexOf("src/main/kotlin/")
        if (idx != -1) {
            val packagePath = pathStr.substring(idx + "src/main/kotlin/".length)
            return packagePath.removeSuffix(".kt").replace('/', '.')
        }
        return classFile.nameWithoutExtension
    }

    /**
     * Analyze class structure and extract methods, properties, etc.
     */
    private fun analyzeClassStructure(classFile: Path): ClassInfo {
        val content = Files.readString(classFile)
        val className = classFile.nameWithoutExtension
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

    /** Generate test code for a class */
    private fun generateTestCode(classInfo: ClassInfo): String {
        return generateTestCodeWithLLM(classInfo)
    }

    /** Generate test code using LLM */
    private fun generateTestCodeWithLLM(classInfo: ClassInfo): String {
        logger.info("Generating test code using LLM for class: ${classInfo.name}")
        val sourceCode = Files.readString(classInfo.filePath)
        val prompt = buildTestGenerationPrompt(classInfo, sourceCode)
        return try {
            val response = chatModel!!.call(prompt)
            val generatedCode = response.content
            logger.info("LLM generated test code successfully for class: ${classInfo.name}")
            logger.debug("Token usage: input=${response.tokenUsage.inputTokenCount}, output=${response.tokenUsage.outputTokenCount}")
            cleanupGeneratedTestCode(generatedCode, classInfo)
        } catch (e: Exception) {
            logger.error("Failed to generate test code using LLM for class: ${classInfo.name}", e)
            ""
        }
    }

    /** Build a comprehensive prompt for LLM test generation */
    private fun buildTestGenerationPrompt(classInfo: ClassInfo, sourceCode: String): String {
        return """
You are an expert Kotlin developer tasked with generating comprehensive unit tests for a Kotlin class.

## Requirements:
- Use JUnit5 testing framework
- Use Kotlin test assertions (kotlin.test) and JUnit5 assertions
- Generate tests for all public methods and important functionality
- Include edge cases and error handling tests
- Use descriptive test method names that clearly indicate what is being tested
- Follow the Arrange-Act-Assert pattern
- Target at least 80% code coverage
- Include parameterized tests where appropriate
- Do not use mock objects unless absolutely necessary
- Generate meaningful test data and assertions

## Class to test:
Package: ${classInfo.packageName}
Class Name: ${classInfo.name}

## Source Code:
```kotlin
$sourceCode
```

## Methods to test:
${
            classInfo.methods.filter { it.isPublic && !it.isConstructor }
                .joinToString("\n") { "- ${it.name}(${it.parameters.joinToString { p -> "${p.name}: ${p.type}" }}): ${it.returnType}" }
        }

## Guidelines:
1. Create a test class named "${classInfo.name}Test"
2. Include proper package declaration: "package ${classInfo.packageName}"
3. Add necessary imports including JUnit5 and kotlin.test
4. Use @BeforeEach and @AfterEach for setup and teardown if needed
5. Each test method should be annotated with @Test
6. Use meaningful variable names and clear assertions
7. Test both positive and negative scenarios
8. Include boundary value testing where applicable
9. Add comments to explain complex test logic

Generate a complete, compilable Kotlin test class that thoroughly tests the provided class.
""".trimIndent()
    }

    /** Clean up and validate the generated test code */
    private fun cleanupGeneratedTestCode(generatedCode: String, classInfo: ClassInfo): String {
        var cleanCode = generatedCode
        cleanCode = cleanCode.replace("```kotlin", "").replace("```", "")
        if (!cleanCode.contains("package ${classInfo.packageName}")) {
            cleanCode = "package ${classInfo.packageName}\n\n$cleanCode"
        }
        val requiredImports = listOf(
            "import org.junit.jupiter.api.Test",
            "import kotlin.test.*"
        )
        requiredImports.forEach { import ->
            if (!cleanCode.contains(import)) {
                val packageIndex = cleanCode.indexOf("package")
                val firstImportIndex = cleanCode.indexOf("import")
                val insertIndex =
                    if (firstImportIndex > packageIndex && firstImportIndex != -1) firstImportIndex else cleanCode.indexOf(
                        '\n',
                        packageIndex
                    ) + 1
                cleanCode = cleanCode.substring(0, insertIndex) + "$import\n" + cleanCode.substring(insertIndex)
            }
        }
        return cleanCode
    }

    // ------------------------------
    // Parsing helpers (simplified)
    // ------------------------------

    private fun extractMethods(content: String): List<MethodInfo> {
        val methods = mutableListOf<MethodInfo>()
        val methodRegex =
            Regex("""(?m)^\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:(public|private|protected|internal)\s+)?(?:suspend\s+|inline\s+|tailrec\s+|operator\s+|infix\s+|external\s+)?fun\s*(?:<[^>]+>\s*)?(?:[A-Za-z_][A-Za-z0-9_]*\.)?([A-Za-z_][A-ZaZ0-9_]*)\s*\(([^)]*)\)\s*(?::\s*([^=\n\r{]+))?""")
        methodRegex.findAll(content).forEach { match ->
            val visibility = match.groups[1]?.value ?: "public"
            val name = match.groups[2]?.value ?: ""
            val paramStr = match.groups[3]?.value ?: ""
            val returnType = match.groups[4]?.value?.trim() ?: "Unit"
            val parameters = parseParameters(paramStr)
            methods.add(
                MethodInfo(
                    name = name,
                    returnType = returnType,
                    isPublic = visibility == "public" || visibility.isEmpty(),
                    isConstructor = false,
                    parameters = parameters
                )
            )
        }
        return methods
    }

    private fun parseParameters(paramStr: String): List<ParameterInfo> {
        if (paramStr.trim().isEmpty()) return emptyList()

        return paramStr.split(',').mapNotNull { param ->
            val trimmed = param.trim()
            if (trimmed.isEmpty()) return@mapNotNull null

            // Match parameter pattern: name: Type = defaultValue or name: Type
            val paramRegex = Regex("""^\s*(\w+)\s*:\s*([^=]+?)(?:\s*=\s*.+)?\s*$""")
            val match = paramRegex.find(trimmed)
            if (match != null) {
                val name = match.groups[1]?.value ?: ""
                val type = match.groups[2]?.value?.trim() ?: ""
                val hasDefault = trimmed.contains('=')
                ParameterInfo(name = name, type = type, hasDefault = hasDefault)
            } else {
                null
            }
        }
    }

    private fun extractProperties(content: String): List<PropertyInfo> {
        val properties = mutableListOf<PropertyInfo>()
        val propertyRegex =
            Regex("""(?m)^\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:(public|private|protected|internal)\s+)?(val|var)\s+(\w+)\s*:\s*([^=\n\r{]+)""")
        propertyRegex.findAll(content).forEach { match ->
            val name = match.groups[3]?.value ?: ""
            val type = match.groups[4]?.value?.trim() ?: ""
            val isReadOnly = match.groups[2]?.value == "val"
            properties.add(PropertyInfo(name = name, type = type, isReadOnly = isReadOnly))
        }
        return properties
    }

    private fun extractImports(content: String): List<String> {
        val imports = mutableListOf<String>()
        val importRegex = Regex("""import\s+[^\n]+""")
        importRegex.findAll(content).forEach { match -> imports.add(match.value) }
        return imports
    }

    private fun extractPackageName(content: String): String {
        val packageRegex = Regex("""package\s+([^\n]+)""")
        return packageRegex.find(content)?.groups?.get(1)?.value?.trim() ?: ""
    }

    // ------------------------------
    // Data classes representing code structure
    // ------------------------------

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
    println(
        "Project root directory: ${
            autoCoder.javaClass.getDeclaredField("projectRootDir").let { it.isAccessible = true; it.get(autoCoder) }
        }"
    )

    try {
        autoCoder.generateUnitTestsForPackage(packageName)
        println("Unit test generation completed successfully!")
    } catch (e: Exception) {
        println("Error during test generation: ${e.message}")
        e.printStackTrace()
    }
}
