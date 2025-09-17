package ai.platon.pulsar.autoCoder

import ai.platon.pulsar.common.code.ProjectUtils
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.external.ChatModel
import ai.platon.pulsar.external.ChatModelFactory
import java.io.BufferedReader
import java.io.InputStreamReader
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
 * - The project is a Maven multi-module project with standardized directory structure.
 * - Each module follows Maven conventions with `src/main/kotlin` and `src/test/kotlin` directories.
 * - Source code is located in `src/main/kotlin` and test code in `src/test/kotlin`.
 * - Package structure mirrors fully qualified class names in both source and test directories.
 * - **Test class naming convention**: Test classes use the original class name with "AITest" suffix.
 *   - Class name: `DataCollectors` → Test class name: `DataCollectorsAITest`
 *   - File name matches class name (e.g., `DataCollectorsAITest.kt`)
 * - Examples of file locations:
 *   - Source class: `ai.platon.pulsar.common.collect.DataCollectors` → `src/main/kotlin/ai/platon/pulsar/common/collect/DataCollectors.kt`
 *   - Test class: `ai.platon.pulsar.common.collect.DataCollectorsAITest` → `src/test/kotlin/ai/platon/pulsar/common/collect/DataCollectorsAITest.kt`
 * - Build system: Use `mvnw` (Maven wrapper) instead of `mvn` for consistent builds.
 *   - Maven wrapper location: Project root directory
 *   - Example command: `cd "D:\\workspace\\Browser4-4.0.x" && .\\mvnw.cmd compile -pl pulsar-tools/pulsar-auto-coder`
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

    // Test execution and optimization tracking
    private val testResults = mutableMapOf<String, TestResult>()
    private val maxOptimizationRounds = 3

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
        // 5. Run tests automatically and optimize based on results
        classes.forEach { classFile ->
            try {
                val className = extractClassName(classFile)
                logger.info("Processing class: $className from file: ${classFile.fileName}")

                // Generate initial tests
                val testFile = generateUnitTestsFromFile(classFile, className)

                // Run tests automatically and optimize
                if (testFile != null) {
                    runAndOptimizeTests(className, testFile, classFile)
                }
            } catch (e: Exception) {
                logger.error("Failed to generate tests for class in file: ${classFile.fileName}", e)
            }
        }

        logger.info("Unit test generation completed for package: $packageName")
    }

    /**
     * Run tests automatically and optimize based on results
     */
    private fun runAndOptimizeTests(className: String, testFile: Path, sourceFile: Path) {
        logger.info("Starting automatic test execution and optimization for: $className")

        var currentTestFile = testFile
        var optimizationRound = 0

        while (optimizationRound < maxOptimizationRounds) {
            logger.info("Optimization round ${optimizationRound + 1} for $className")

            // Run the tests
            val testResult = runTests(currentTestFile, className)
            testResults[className] = testResult

            // If tests pass and coverage is good, we're done
            if (testResult.isSuccessful && testResult.coverage >= 80) {
                logger.info("Tests successful with ${testResult.coverage}% coverage for $className")
                break
            }

            // If tests fail or coverage is low, optimize
            if (!testResult.isSuccessful || testResult.coverage < 80) {
                logger.info("Optimizing tests for $className (coverage: ${testResult.coverage}%, failures: ${testResult.failureCount})")

                val optimizedTestFile = optimizeTestCode(
                    currentTestFile,
                    sourceFile,
                    testResult,
                    className,
                    optimizationRound
                )

                if (optimizedTestFile != null) {
                    currentTestFile = optimizedTestFile
                } else {
                    logger.warn("Failed to optimize tests for $className")
                    break
                }
            }

            optimizationRound++
        }

        logger.info("Completed test optimization for $className after ${optimizationRound + 1} rounds")
    }

    /**
     * Generate unit tests for a class using the actual file path
     * @return Path to the generated test file, or null if generation failed
     */
    private fun generateUnitTestsFromFile(classFile: Path, className: String): Path? {
        logger.info("Generating unit tests for class: $className")

        // 2. Analyze the class structure and methods
        val classInfo = analyzeClassStructure(classFile)

        // 3. Generate unit test code for each method
        val testCode = generateTestCode(classInfo)
        if (testCode.isBlank()) {
            logger.warn("No test code generated for class: $className")
            return null
        }

        // 4. Save the generated unit test code to a file
        val testFile = createTestFilePath(className)
        saveTestCode(testFile, testCode)

        logger.info("Unit tests generated successfully for: $className")
        logger.info("Test file created at: $testFile")
        return testFile
    }

    /**
     * Create test file path for a class.
     */
    fun createTestFilePath(className: String): Path {
        val lastDot = className.lastIndexOf('.')
        val pkg = if (lastDot > 0) className.substring(0, lastDot) else ""
        val simpleName = if (lastDot > 0) className.substring(lastDot + 1) else className

        // Find the correct module directory based on the package
        val moduleDir = findModuleDirectoryForPackage(pkg)
        val testDir = moduleDir.resolve(testOutputDir)
        val pkgDir = if (pkg.isNotBlank()) testDir.resolve(pkg.replace('.', '/')) else testDir
        val fileName = "${simpleName}AITest.kt"
        return pkgDir.resolve(fileName)
    }

    /**
     * Find the module directory that contains the given package.
     * This method searches for the module that contains the source class.
     */
    private fun findModuleDirectoryForPackage(packageName: String): Path {
        val packagePath = packageName.replace('.', '/')

        // Search for the module that contains this package in src/main/kotlin
        Files.walk(projectRootDir).use { stream ->
            val matchingPaths = stream.filter { path ->
                val pathStr = path.toString().replace('\\', '/')
                path.isDirectory() &&
                pathStr.contains("src/main/kotlin/$packagePath") &&
                pathStr.indexOf("src/main/kotlin/$packagePath") > 0
            }.toList()

            for (path in matchingPaths) {
                val pathStr = path.toString().replace('\\', '/')
                val srcMainKotlinIndex = pathStr.indexOf("src/main/kotlin")
                if (srcMainKotlinIndex > 0) {
                    val moduleDir = Paths.get(pathStr.take(srcMainKotlinIndex))
                    logger.debug("Found module directory for package {}: {}", packageName, moduleDir)
                    return moduleDir
                }
            }
        }

        // Fallback: if not found, return the project root directory
        logger.warn("Could not find specific module for package: $packageName, using project root")
        return projectRootDir
    }

    /** Save test code to disk, creating directories as needed. */
    fun saveTestCode(testFile: Path, testCode: String) {
        Files.createDirectories(testFile.parent)
        Files.writeString(testFile, testCode)
        logger.info("Wrote test file: $testFile")
    }

    /**
     * Find all Kotlin class files in the specified package
     */
    fun findClassesInPackage(packageName: String): List<Path> {
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
    fun analyzeClassStructure(classFile: Path): ClassInfo {
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
            val response = chatModel.call(prompt)
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
1. Create a test class named "${classInfo.name}AITest"
2. Include proper package declaration: "package ${classInfo.packageName}"
3. Add necessary imports including JUnit5 and kotlin.test
4. Use @BeforeEach and @AfterEach for setup and teardown if needed
5. Each test method should be annotated with @Test
6. Use meaningful variable names and clear assertions
7. Test both positive and negative scenarios
8. Include boundary value testing where applicable
9. Add comments to explain complex test logic

## IMPORTANT: 
Please provide ONLY the Kotlin test code wrapped in a code block like this:

```kotlin
// Your complete test class code here
```

Do NOT include any explanations, summaries, or additional text outside the code block. Only return the pure Kotlin test code within the code block.
""".trimIndent()
    }

    /** Clean up and validate the generated test code */
    private fun cleanupGeneratedTestCode(generatedCode: String, classInfo: ClassInfo): String {
        logger.debug("Raw generated code length: ${generatedCode.length}")
        
        var cleanCode = extractKotlinCodeFromResponse(generatedCode)
        
        // If no valid Kotlin code found, try to extract from code blocks
        if (cleanCode.isBlank()) {
            cleanCode = extractFromCodeBlocks(generatedCode)
        }
        
        // If still no code, return the original but cleaned
        if (cleanCode.isBlank()) {
            cleanCode = generatedCode.trim()
        }
        
        // Remove markdown code block markers
        cleanCode = cleanCode.replace("```kotlin", "").replace("```", "").trim()
        
        // Remove common explanation patterns that might leak into code
        cleanCode = removeExplanationText(cleanCode)
        
        // Ensure package declaration exists
        if (!cleanCode.contains("package ${classInfo.packageName}")) {
            cleanCode = "package ${classInfo.packageName}\n\n$cleanCode"
        }
        
        // Add required imports if missing
        cleanCode = ensureRequiredImports(cleanCode)
        
        // Validate that we have a proper class declaration
        val expectedClassName = "${classInfo.name}AITest"
        if (!cleanCode.contains("class $expectedClassName")) {
            logger.warn("Generated code does not contain expected class: $expectedClassName")
        }
        
        logger.debug("Cleaned code length: ${cleanCode.length}")
        return cleanCode
    }
    
    /** Extract Kotlin code from LLM response, filtering out explanatory text */
    private fun extractKotlinCodeFromResponse(response: String): String {
        // Look for package declaration as start marker
        val packageRegex = Regex("""package\s+[\w.]+""")
        val packageMatch = packageRegex.find(response)
        
        if (packageMatch != null) {
            val startIndex = packageMatch.range.first
            
            // Find the end of the Kotlin code by looking for explanation sections
            val explanationMarkers = listOf(
                "### Explanation:",
                "## Explanation:",
                "Explanation:",
                "### Summary:",
                "## Summary:",
                "Summary:",
                "Note:",
                "### Note:",
                "## Note:"
            )
            
            var endIndex = response.length
            for (marker in explanationMarkers) {
                val markerIndex = response.indexOf(marker, startIndex)
                if (markerIndex > startIndex && markerIndex < endIndex) {
                    endIndex = markerIndex
                }
            }
            
            return response.substring(startIndex, endIndex).trim()
        }
        
        return ""
    }
    
    /** Extract code from markdown code blocks */
    private fun extractFromCodeBlocks(content: String): String {
        val codeBlockRegex = Regex("""```(?:kotlin)?\s*\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
        val matches = codeBlockRegex.findAll(content)
        
        for (match in matches) {
            val code = match.groups[1]?.value?.trim() ?: ""
            // Check if this looks like Kotlin test code
            if (code.contains("package") && (code.contains("class") || code.contains("fun"))) {
                return code
            }
        }
        
        return ""
    }
    
    /** Remove common explanation text patterns that might leak into code */
    private fun removeExplanationText(code: String): String {
        val lines = code.split('\n').toMutableList()
        val cleanLines = mutableListOf<String>()
        
        var foundPackage = false
        
        for (line in lines) {
            val trimmedLine = line.trim()
            
            // Skip empty lines at the beginning
            if (!foundPackage && trimmedLine.isEmpty()) {
                continue
            }
            
            // Check for package declaration
            if (trimmedLine.startsWith("package ")) {
                foundPackage = true
                cleanLines.add(line)
                continue
            }
            
            // Skip explanation headers and non-code content before package
            if (!foundPackage && (
                trimmedLine.startsWith("###") ||
                trimmedLine.startsWith("##") ||
                trimmedLine.startsWith("**") ||
                trimmedLine.startsWith("Explanation:") ||
                trimmedLine.startsWith("Summary:") ||
                trimmedLine.startsWith("Note:") ||
                trimmedLine.contains("test class") && !trimmedLine.startsWith("class") ||
                trimmedLine.contains("following test") ||
                trimmedLine.contains("explanation") && !line.contains("//")
            )) {
                continue
            }
            
            // If we found package, include all code-like content
            if (foundPackage) {
                // Skip obvious explanation sections
                if (trimmedLine.startsWith("### Explanation:") ||
                    trimmedLine.startsWith("## Explanation:") ||
                    trimmedLine.startsWith("Explanation:") ||
                    trimmedLine.startsWith("### Summary:") ||
                    trimmedLine.startsWith("## Summary:") ||
                    trimmedLine == "Explanation:" ||
                    (trimmedLine.startsWith("1.") && trimmedLine.contains("test")) ||
                    (trimmedLine.startsWith("2.") && trimmedLine.contains("test")) ||
                    (trimmedLine.startsWith("3.") && trimmedLine.contains("test")) ||
                    (trimmedLine.startsWith("4.") && trimmedLine.contains("test"))
                ) {
                    break // Stop processing when we hit explanation section
                }
                cleanLines.add(line)
            }
        }
        
        return cleanLines.joinToString("\n").trim()
    }
    
    /** Ensure required imports are present */
    private fun ensureRequiredImports(code: String): String {
        var cleanCode = code
        val requiredImports = listOf(
            "import org.junit.jupiter.api.Test",
            "import kotlin.test.*"
        )

        requiredImports.forEach { import ->
            if (!cleanCode.contains(import)) {
                val packageIndex = cleanCode.indexOf("package")
                val firstImportIndex = cleanCode.indexOf("import")
                val insertIndex = if (firstImportIndex > packageIndex && firstImportIndex != -1) {
                    firstImportIndex
                } else {
                    val packageLineEnd = cleanCode.indexOf('\n', packageIndex)
                    if (packageLineEnd != -1) packageLineEnd + 1 else 0
                }
                cleanCode = cleanCode.substring(0, insertIndex) + "$import\n" + cleanCode.substring(insertIndex)
            }
        }

        return cleanCode
    }

    /**
     * Run tests automatically and return results
     */
    fun runTests(testFile: Path, className: String): TestResult {
        logger.info("Running tests for: $className")

        try {
            // Find the module directory containing the test file
            val moduleDir = findModuleDirectoryForTestFile(testFile)

            // Build the Maven command to run the specific test
            val testClassName = extractTestClassName(testFile)
            val command = buildMavenTestCommand(moduleDir, testClassName)

            logger.info("Executing command: ${command.joinToString(" ")}")

            // Execute the test command
            val process = ProcessBuilder(command)
                .directory(projectRootDir.toFile())
                .redirectErrorStream(true)
                .start()

            val output = mutableListOf<String>()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.lines().forEach { line ->
                    output.add(line)
                    logger.debug(line)
                }
            }

            val exitCode = process.waitFor()
            val outputText = output.joinToString("\n")

            // Parse test results
            val testResult = parseTestResults(outputText, exitCode, className)

            logger.info("Test execution completed for $className with exit code: $exitCode")
            logger.info("Tests passed: ${testResult.successCount}, Failed: ${testResult.failureCount}, Coverage: ${testResult.coverage}%")

            return testResult

        } catch (e: Exception) {
            logger.error("Failed to run tests for $className", e)
            return TestResult(
                className = className,
                isSuccessful = false,
                successCount = 0,
                failureCount = 0,
                coverage = 0,
                errorMessage = e.message
            )
        }
    }

    /**
     * Build Maven command to run specific test
     */
    private fun buildMavenTestCommand(moduleDir: Path, testClassName: String): List<String> {
        val relativeModulePath = projectRootDir.relativize(moduleDir).toString()
        return listOf(
            if (System.getProperty("os.name").lowercase().contains("win")) ".\\mvnw.cmd" else "./mvnw",
            "test",
            "-pl", relativeModulePath,
            "-Dtest=$testClassName"
        )
    }

    /**
     * Find module directory for a test file
     */
    private fun findModuleDirectoryForTestFile(testFile: Path): Path {
        var current = testFile.parent
        while (current != null && current != projectRootDir) {
            if (Files.exists(current.resolve("pom.xml"))) {
                return current
            }
            current = current.parent
        }
        return projectRootDir
    }

    /**
     * Extract test class name from test file path
     */
    private fun extractTestClassName(testFile: Path): String {
        val fileName = testFile.fileName.toString()
        val className = fileName.removeSuffix(".kt")
        val packagePath = extractPackagePathFromTestFile(testFile)
        return if (packagePath.isNotEmpty()) "$packagePath.$className" else className
    }

    /**
     * Extract package path from test file
     */
    private fun extractPackagePathFromTestFile(testFile: Path): String {
        val pathStr = testFile.toString().replace('\\', '/')
        val idx = pathStr.indexOf("src/test/kotlin/")
        if (idx != -1) {
            val packagePath = pathStr.substring(idx + "src/test/kotlin/".length, pathStr.lastIndexOf('/'))
            return packagePath.replace('/', '.')
        }
        return ""
    }

    /**
     * Parse test results from Maven output
     */
    private fun parseTestResults(output: String, exitCode: Int, className: String): TestResult {
        var successCount = 0
        var failureCount = 0
        var coverage = 0

        // Parse test counts
        val testsRunRegex = Regex("""Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)""")
        val testsMatch = testsRunRegex.find(output)
        if (testsMatch != null) {
            val total = testsMatch.groupValues[1].toInt()
            val failures = testsMatch.groupValues[2].toInt()
            val errors = testsMatch.groupValues[3].toInt()

            successCount = total - failures - errors
            failureCount = failures + errors
        }

        // Parse coverage (if available)
        val coverageRegex = Regex("""Total.*?([0-9.]+)%""")
        val coverageMatch = coverageRegex.find(output)
        if (coverageMatch != null) {
            coverage = coverageMatch.groupValues[1].toInt()
        } else {
            // Estimate coverage based on test results
            coverage = if (failureCount == 0) 70 else 40
        }

        return TestResult(
            className = className,
            isSuccessful = exitCode == 0 && failureCount == 0,
            successCount = successCount,
            failureCount = failureCount,
            coverage = coverage,
            errorMessage = if (exitCode != 0) "Test execution failed with exit code: $exitCode" else null
        )
    }

    /**
     * Optimize test code based on test results
     */
    private fun optimizeTestCode(
        currentTestFile: Path,
        sourceFile: Path,
        testResult: TestResult,
        className: String,
        optimizationRound: Int
    ): Path? {
        logger.info("Optimizing test code for $className (round ${optimizationRound + 1})")

        try {
            val currentTestCode = Files.readString(currentTestFile)
            val sourceCode = Files.readString(sourceFile)
            val classInfo = analyzeClassStructure(sourceFile)

            val optimizationPrompt = buildOptimizationPrompt(
                classInfo,
                currentTestCode,
                testResult,
                optimizationRound
            )

            val response = chatModel.call(optimizationPrompt)
            val optimizedCode = cleanupGeneratedTestCode(response.content, classInfo)

            if (optimizedCode.isNotBlank() && optimizedCode != currentTestCode) {
                // Save optimized code to a new file
                val optimizedFile = createOptimizedTestFilePath(currentTestFile, optimizationRound + 1)
                saveTestCode(optimizedFile, optimizedCode)
                logger.info("Optimized test code saved to: $optimizedFile")
                return optimizedFile
            } else {
                logger.warn("No meaningful optimization generated for $className")
                return null
            }

        } catch (e: Exception) {
            logger.error("Failed to optimize test code for $className", e)
            return null
        }
    }

    /**
     * Build optimization prompt for LLM
     */
    private fun buildOptimizationPrompt(
        classInfo: ClassInfo,
        currentTestCode: String,
        testResult: TestResult,
        optimizationRound: Int
    ): String {
        val issues = mutableListOf<String>()

        if (!testResult.isSuccessful) {
            issues.add("Tests are failing (${testResult.failureCount} failures)")
        }

        if (testResult.coverage < 80) {
            issues.add("Code coverage is low (${testResult.coverage}%, target: 80%+)")
        }

        return """
You are an expert Kotlin developer tasked with optimizing unit tests based on test execution results.

## Original Class Information:
Package: ${classInfo.packageName}
Class Name: ${classInfo.name}

## Current Test Issues to Fix:
${issues.joinToString("\n")}

## Current Test Code:
```kotlin
$currentTestCode
```

## Methods to Cover:
${classInfo.methods.filter { it.isPublic && !it.isConstructor }
    .joinToString("\n") { "- ${it.name}(${it.parameters.joinToString { p -> "${p.name}: ${p.type}" }}): ${it.returnType}" }}

## Optimization Requirements:
1. Fix any failing tests by improving test logic or fixing assertions
2. Increase code coverage to at least 80% by adding more test cases
3. Add edge cases and boundary value testing
4. Improve test data and assertions
5. Ensure all public methods are thoroughly tested
6. Follow the Arrange-Act-Assert pattern
7. Use descriptive test method names

## Important Notes:
- This is optimization round ${optimizationRound + 1}
- Focus on fixing the identified issues
- Maintain existing working tests while improving coverage
- Add parameterized tests where applicable
- Ensure tests run quickly and are independent

Please provide ONLY the optimized Kotlin test code wrapped in a code block:

```kotlin
// Your optimized test class code here
```

Do NOT include any explanations or additional text outside the code block.
""".trimIndent()
    }

    /**
     * Create optimized test file path
     */
    private fun createOptimizedTestFilePath(originalFile: Path, round: Int): Path {
        val fileName = originalFile.fileName.toString()
        val baseName = fileName.removeSuffix(".kt")
        val optimizedFileName = "${baseName}_optimized_$round.kt"
        return originalFile.parent.resolve(optimizedFileName)
    }

    // ------------------------------
    // Parsing helpers (simplified)
    // ------------------------------

    private fun extractMethods(content: String): List<MethodInfo> {
        val methods = mutableListOf<MethodInfo>()

        // Simple but effective approach - find all fun declarations
        // First, find all fun keyword occurrences
        val funRegex = Regex("""(?m)^\s*(?:override\s+)?fun\s+(\w+)""")
        val funMatches = funRegex.findAll(content).toList()

        logger.debug("Found ${funMatches.size} fun keyword matches")

        // For each fun match, extract the full method signature
        funMatches.forEach { funMatch ->
            val methodName = funMatch.groups[1]?.value ?: return@forEach

            // Find the start of this method (from the fun keyword)
            val methodStart = funMatch.range.first

            // Find the opening parenthesis after the method name
            val afterNameStart = funMatch.groups[1]?.range?.last?.plus(1) ?: return@forEach
            val remainingContent = content.substring(afterNameStart)

            // Find parameter list and return type
            val paramListRegex = Regex("""^\s*\(([^)]*)\)""")
            val paramMatch = paramListRegex.find(remainingContent)

            if (paramMatch != null) {
                val paramStr = paramMatch.groups[1]?.value ?: ""
                val parameters = parseParameters(paramStr)

                // Find return type if present (after closing parenthesis)
                val afterParamsStart = paramMatch.range.last + 1
                val afterParamsContent = remainingContent.substring(afterParamsStart)

                val returnTypeRegex = Regex("""^\s*:\s*([^\s={]+)""")
                val returnTypeMatch = returnTypeRegex.find(afterParamsContent)
                val returnType = returnTypeMatch?.groups?.get(1)?.value?.trim() ?: "Unit"

                logger.debug("Found method: $methodName with params: $paramStr, returnType: $returnType")

                methods.add(
                    MethodInfo(
                        name = methodName,
                        returnType = returnType,
                        isPublic = true, // Assume public for now
                        isConstructor = false,
                        parameters = parameters
                    )
                )
            }
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

    data class TestResult(
        val className: String,
        val isSuccessful: Boolean,
        val successCount: Int,
        val failureCount: Int,
        val coverage: Int,
        val errorMessage: String? = null
    )

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

    /**
     * Generate and optimize tests for a specific class
     */
    fun generateTestsForClass(className: String) {
        logger.info("Generating tests for specific class: $className")

        // Find the class file
        val classFile = findClassByName(className)
        if (classFile == null) {
            logger.error("Class not found: $className")
            return
        }

        try {
            // Generate initial tests
            val testFile = generateUnitTestsFromFile(classFile, className)

            // Run tests automatically and optimize
            if (testFile != null) {
                runAndOptimizeTests(className, testFile, classFile)
            }

            logger.info("Test generation and optimization completed for class: $className")
        } catch (e: Exception) {
            logger.error("Failed to process class: $className", e)
        }
    }

    /**
     * Find a class by its fully qualified name
     */
    fun findClassByName(className: String): Path? {
        val packagePath = className.substringBeforeLast('.', "")
        val simpleName = className.substringAfterLast('.')

        return findClassesInPackage(packagePath).find { file ->
            file.fileName.toString() == "$simpleName.kt"
        }
    }

    /**
     * Get test results summary
     */
    fun getTestResultsSummary(): Map<String, TestResult> {
        return testResults.toMap()
    }
}

fun main() {
    val autoCoder = AutoCoder()

    // Debug: Print the project root directory being used
    println("Project root directory: ${autoCoder.javaClass.getDeclaredField("projectRootDir").let { it.isAccessible = true; it.get(autoCoder) }}")

    // Example usage: Generate tests for a specific class or package
    println("AutoCoder - AI-Powered Test Generation and Optimization Tool")
    println("Usage options:")
    println("1. Generate tests for a package: autoCoder.generateUnitTestsForPackage(\"ai.platon.pulsar.common.collect\")")
    println("2. Generate tests for a specific class: autoCoder.generateTestsForClass(\"ai.platon.pulsar.common.collect.DataCollectors\")")
    println()

    try {
        // Example: Generate tests for a specific class
        val className = "ai.platon.pulsar.common.collect.DataCollectors"
        println("Generating tests for class: $className")
        autoCoder.generateTestsForClass(className)

        // Print summary
        val results = autoCoder.getTestResultsSummary()
        println("\n=== Test Results Summary ===")
        results.forEach { (className, result) ->
            println("Class: $className")
            println("  Success: ${result.isSuccessful}")
            println("  Tests Passed: ${result.successCount}")
            println("  Tests Failed: ${result.failureCount}")
            println("  Coverage: ${result.coverage}%")
            if (result.errorMessage != null) {
                println("  Error: ${result.errorMessage}")
            }
            println()
        }

        println("AutoCoder execution completed!")
    } catch (e: Exception) {
        println("Error during test generation: ${e.message}")
        e.printStackTrace()
    }
}
