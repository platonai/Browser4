package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.agentic.common.AgentPaths
import ai.platon.pulsar.common.getLogger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Document-driven skill installer.
 *
 * The SkillInstaller reads a SKILL.md from a source directory, parses the documentation
 * to extract metadata and installation steps, copies the skill files into the managed
 * skills directory, and registers the skill in the [SkillRegistry].
 *
 * This enables the Agent to autonomously install skills by reading their documentation:
 * ```
 * agent.run("read SKILL documentation and install SKILL")
 * ```
 *
 * ## Installation Workflow:
 * 1. **Read** — Load SKILL.md from source directory and parse metadata
 * 2. **Validate** — Verify skill structure and dependencies
 * 3. **Deploy** — Copy skill files to the managed skills directory
 * 4. **Register** — Register the skill in the [SkillRegistry]
 *
 * ## Example Usage:
 * ```kotlin
 * val installer = SkillInstaller()
 * val result = installer.install(
 *     sourceDir = Paths.get("/path/to/my-skill"),
 *     context = SkillContext(sessionId = "session-123")
 * )
 * ```
 */
class SkillInstaller(
    private val registry: SkillRegistry = SkillRegistry.instance,
    private val definitionLoader: SkillDefinitionLoader = SkillDefinitionLoader(),
    private val loader: SkillLoader = SkillLoader(registry),
) {
    private val logger = getLogger(this)

    /**
     * Result of a skill installation attempt.
     *
     * @property success Whether the installation was successful
     * @property skillId The ID of the installed skill (or attempted skill ID)
     * @property message Human-readable message describing the result
     * @property installSteps List of installation steps extracted from SKILL.md
     * @property deployedPath Path where the skill was deployed (null on failure)
     */
    data class InstallResult(
        val success: Boolean,
        val skillId: String,
        val message: String,
        val installSteps: List<String> = emptyList(),
        val deployedPath: String? = null
    )

    /**
     * Install a skill from a source directory.
     *
     * Reads the SKILL.md, parses metadata, copies skill files to the managed skills
     * directory, and registers the skill.
     *
     * @param sourceDir Path to the source skill directory containing SKILL.md
     * @param context Execution context for skill initialization
     * @param overwrite Whether to overwrite an existing skill with the same ID
     * @return InstallResult describing the outcome
     */
    suspend fun install(
        sourceDir: Path,
        context: SkillContext,
        overwrite: Boolean = false
    ): InstallResult {
        logger.info("Installing skill from source directory: {}", sourceDir)

        // Step 1: Validate source directory
        if (!Files.exists(sourceDir) || !Files.isDirectory(sourceDir)) {
            return InstallResult(
                success = false,
                skillId = "",
                message = "Source directory does not exist or is not a directory: $sourceDir"
            )
        }

        val skillMdPath = sourceDir.resolve("SKILL.md")
        if (!Files.exists(skillMdPath)) {
            return InstallResult(
                success = false,
                skillId = "",
                message = "SKILL.md not found in source directory: $sourceDir"
            )
        }

        // Step 2: Parse SKILL.md
        val definition: SkillDefinition
        try {
            val definitions = definitionLoader.loadFromDirectory(sourceDir.parent)
            definition = definitions.find {
                it.skillId == sourceDir.fileName.toString()
            } ?: return InstallResult(
                success = false,
                skillId = sourceDir.fileName.toString(),
                message = "Failed to parse SKILL.md in: $sourceDir"
            )
        } catch (e: Exception) {
            return InstallResult(
                success = false,
                skillId = sourceDir.fileName.toString(),
                message = "Failed to parse SKILL.md: ${e.message}"
            )
        }

        val skillId = definition.skillId

        // Step 3: Parse installation steps from SKILL.md body
        val skillMdContent = Files.readString(skillMdPath)
        val installSteps = parseInstallSteps(skillMdContent)

        // Step 4: Check if skill already exists
        if (registry.contains(skillId) && !overwrite) {
            return InstallResult(
                success = false,
                skillId = skillId,
                message = "Skill '$skillId' is already installed. Use overwrite=true to replace it.",
                installSteps = installSteps
            )
        }

        // Step 5: Validate dependencies
        val missingDeps = definition.dependencies.filterNot { registry.contains(it) }
        if (missingDeps.isNotEmpty()) {
            return InstallResult(
                success = false,
                skillId = skillId,
                message = "Missing dependencies: ${missingDeps.joinToString()}",
                installSteps = installSteps
            )
        }

        // Step 6: Deploy skill files to managed skills directory
        val targetDir: Path
        try {
            targetDir = deploySkillFiles(sourceDir, skillId)
        } catch (e: Exception) {
            return InstallResult(
                success = false,
                skillId = skillId,
                message = "Failed to deploy skill files: ${e.message}",
                installSteps = installSteps
            )
        }

        // Step 7: Load and register the deployed skill
        try {
            // If overwriting, unregister first
            if (registry.contains(skillId)) {
                registry.unregister(skillId, context)
                logger.info("Unregistered existing skill '{}' for overwrite", skillId)
            }

            // Re-load definition from deployed location
            val deployedDefinitions = definitionLoader.loadFromDirectory(targetDir.parent)
            val deployedDefinition = deployedDefinitions.find { it.skillId == skillId }
                ?: return InstallResult(
                    success = false,
                    skillId = skillId,
                    message = "Failed to load deployed skill definition from: $targetDir",
                    installSteps = installSteps,
                    deployedPath = targetDir.toString()
                )

            val skill = DefinitionBackedSkill(
                deployedDefinition,
                DefinitionBackedSkill.Origin.FileSystem(targetDir)
            )

            loader.load(skill, context)

            logger.info("✓ Successfully installed skill '{}' to {}", skillId, targetDir)

            return InstallResult(
                success = true,
                skillId = skillId,
                message = "Skill '$skillId' installed successfully",
                installSteps = installSteps,
                deployedPath = targetDir.toString()
            )
        } catch (e: Exception) {
            logger.warn("Failed to register skill '{}': {}", skillId, e.message)
            return InstallResult(
                success = false,
                skillId = skillId,
                message = "Skill deployed but registration failed: ${e.message}",
                installSteps = installSteps,
                deployedPath = targetDir.toString()
            )
        }
    }

    /**
     * Uninstall a skill by removing its files and unregistering it.
     *
     * @param skillId The ID of the skill to uninstall
     * @param context Execution context for cleanup
     * @return InstallResult describing the outcome
     */
    suspend fun uninstall(skillId: String, context: SkillContext): InstallResult {
        logger.info("Uninstalling skill: {}", skillId)

        // Unregister from registry
        try {
            if (registry.contains(skillId)) {
                registry.unregister(skillId, context)
            }
        } catch (e: Exception) {
            return InstallResult(
                success = false,
                skillId = skillId,
                message = "Failed to unregister skill: ${e.message}"
            )
        }

        // Remove deployed files
        try {
            val targetDir = resolveSkillsDir().resolve(skillId)
            if (Files.exists(targetDir)) {
                deleteRecursively(targetDir)
                logger.info("✓ Removed skill directory: {}", targetDir)
            }
        } catch (e: Exception) {
            logger.warn("Skill unregistered but failed to remove files: {}", e.message)
            return InstallResult(
                success = true,
                skillId = skillId,
                message = "Skill '$skillId' unregistered, but file removal failed: ${e.message}"
            )
        }

        return InstallResult(
            success = true,
            skillId = skillId,
            message = "Skill '$skillId' uninstalled successfully"
        )
    }

    /**
     * List installed skills with their install status.
     *
     * @return List of installed skill IDs and their metadata
     */
    fun listInstalled(): List<Map<String, Any>> {
        return registry.getAll().map { skill ->
            mapOf(
                "id" to skill.metadata.id,
                "name" to skill.metadata.name,
                "version" to skill.metadata.version,
                "description" to skill.metadata.description,
                "tags" to skill.metadata.tags.toList()
            )
        }
    }

    /**
     * Read and return skill documentation (SKILL.md content) for a given skill ID.
     *
     * @param skillId The ID of the skill
     * @return The full SKILL.md content, or null if not found
     */
    fun readDocumentation(skillId: String): String? {
        val activation = try {
            registry.activateSkill(skillId)
        } catch (e: Exception) {
            logger.debug("Failed to read documentation for skill '{}': {}", skillId, e.message)
            return null
        }
        return activation.skillMd.takeIf { it.isNotBlank() }
    }

    /**
     * Parse installation steps from SKILL.md content.
     *
     * Looks for sections titled "## Installation", "## Install", "## Setup",
     * or "## Getting Started" and extracts numbered/bulleted steps.
     *
     * @param content The SKILL.md content
     * @return List of installation step descriptions
     */
    internal fun parseInstallSteps(content: String): List<String> {
        val steps = mutableListOf<String>()
        val lines = content.lines()

        var inInstallSection = false

        for (line in lines) {
            val trimmed = line.trim()

            // Detect installation section headers
            if (trimmed.matches(Regex("^##\\s+(Installation|Install|Setup|Getting Started).*", RegexOption.IGNORE_CASE))) {
                inInstallSection = true
                continue
            }

            // End of section when hitting another ## header
            if (inInstallSection && trimmed.matches(Regex("^##\\s+.*"))) {
                break
            }

            if (inInstallSection) {
                // Match numbered steps (1. Step description)
                val numberedMatch = Regex("^\\d+\\.\\s+(.+)").find(trimmed)
                if (numberedMatch != null) {
                    steps.add(numberedMatch.groupValues[1].trim())
                    continue
                }

                // Match bulleted steps (- Step description or * Step description)
                val bulletMatch = Regex("^[-*]\\s+(.+)").find(trimmed)
                if (bulletMatch != null) {
                    steps.add(bulletMatch.groupValues[1].trim())
                    continue
                }
            }
        }

        return steps
    }

    /**
     * Deploy skill files from source to managed skills directory.
     */
    private fun deploySkillFiles(sourceDir: Path, skillId: String): Path {
        val targetDir = resolveSkillsDir().resolve(skillId)

        if (Files.exists(targetDir)) {
            deleteRecursively(targetDir)
        }
        Files.createDirectories(targetDir)

        // Copy all files and directories from source
        copyRecursively(sourceDir, targetDir)

        logger.info("Deployed skill files from {} to {}", sourceDir, targetDir)
        return targetDir
    }

    /**
     * Resolve the managed skills directory, creating it if needed.
     */
    private fun resolveSkillsDir(): Path {
        return try {
            val dir = AgentPaths.SKILLS_DIR
            if (!Files.exists(dir)) {
                Files.createDirectories(dir)
            }
            dir
        } catch (e: Exception) {
            // Fallback: use a temp directory if AgentPaths is unavailable
            val fallback = Path.of(System.getProperty("java.io.tmpdir"), "browser4-skills")
            if (!Files.exists(fallback)) {
                Files.createDirectories(fallback)
            }
            logger.warn("AgentPaths.SKILLS_DIR unavailable, using fallback: {}", fallback)
            fallback
        }
    }

    private fun copyRecursively(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { sourcePath ->
                val relativePath = source.relativize(sourcePath)
                val targetPath = target.resolve(relativePath)
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath)
                } else {
                    Files.createDirectories(targetPath.parent)
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }
}
