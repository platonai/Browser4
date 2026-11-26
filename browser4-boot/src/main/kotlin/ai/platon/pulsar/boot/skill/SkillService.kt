package ai.platon.pulsar.boot.skill

import ai.platon.pulsar.agentic.common.AgentPaths
import ai.platon.pulsar.agentic.skills.*
import ai.platon.pulsar.common.getLogger
import org.springframework.context.ApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

/**
 * Service for managing skills at runtime.
 *
 * Provides operations to list, inspect, install, uninstall, and reload skills.
 * Wraps [SkillInstaller], [SkillRegistry], [SkillLoader], and [SkillDefinitionLoader].
 *
 * @param applicationContext  the Spring application context (reserved for future use)
 * @param skillsDir           the directory where managed skills are stored
 *                            (default: [AgentPaths.SKILLS_DIR])
 */
class SkillService(
    private val applicationContext: ApplicationContext,
    private val skillsDir: Path = AgentPaths.SKILLS_DIR,
    private val registry: SkillRegistry = SkillRegistry.instance,
    private val definitionLoader: SkillDefinitionLoader = SkillDefinitionLoader(),
    private val draftsDir: Path = AgentPaths.AGENT_BASE_DIR.resolve("skills-drafts"),
) {
    private val logger = getLogger(SkillService::class)
    private val installer: SkillInstaller = SkillInstaller(registry, definitionLoader)
    private val loader: SkillLoader = SkillLoader(registry)

    /**
     * Detailed information about a skill, including its full SKILL.md content.
     */
    data class SkillDetail(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val author: String,
        val tags: List<String>,
        val dependencies: List<String>,
        val skillMd: String,
        val scriptsPath: String?,
        val referencesPath: String?,
        val assetsPath: String?,
        val origin: String?,
    )

    /**
     * Lightweight summary of an auto-generated skill draft sitting in the drafts directory.
     */
    data class DraftSummary(
        /** Draft directory name, e.g. `refactor-auth-1732456789012` — the stable handle for promote. */
        val id: String,
        /** Skill id parsed from the directory name (the part before the timestamp suffix). */
        val skillId: String,
        /** Generation time (epoch millis), parsed from the directory name suffix or file mtime. */
        val generatedAt: Long?,
        /** Source task id, read from the draft's `context.json`. */
        val sourceTaskId: String?,
        /** Version declared in the SKILL.md frontmatter. */
        val version: String?,
        /** Description declared in the SKILL.md frontmatter. */
        val description: String?,
        /** Whether a skill with the same id is currently registered in the runtime registry. */
        val registered: Boolean,
    )

    // ---- Query ----

    /**
     * List all registered skills as lightweight summaries.
     */
    fun listSkills(): List<SkillRegistry.SkillSummary> {
        return registry.listSkillSummaries()
    }

    /**
     * Get detailed information about a skill by ID.
     *
     * @param skillId  the skill identifier
     * @return [SkillDetail] if found, null otherwise
     */
    fun getSkill(skillId: String): SkillDetail? {
        val skill = registry.get(skillId) ?: return null

        val activation = try {
            registry.activateSkill(skillId)
        } catch (e: Exception) {
            logger.warn("Failed to activate skill '{}': {}", skillId, e.message)
            null
        }

        val origin = when (skill) {
            is DefinitionBackedSkill -> skill.definition.let {
                // Best effort: check if any path is under skillsDir
                it.scriptsPath?.toString()
                    ?: it.referencesPath?.toString()
                    ?: it.assetsPath?.toString()
            }
            else -> null
        }

        return SkillDetail(
            id = skill.metadata.id,
            name = skill.metadata.name,
            version = skill.metadata.version,
            description = skill.metadata.description,
            author = skill.metadata.author,
            tags = skill.metadata.tags.toList(),
            dependencies = skill.metadata.dependencies,
            skillMd = activation?.skillMd ?: "",
            scriptsPath = activation?.scriptsPath,
            referencesPath = activation?.referencesPath,
            assetsPath = activation?.assetsPath,
            origin = origin,
        )
    }

    // ---- Drafts (auto-generated) ----

    private val frontmatterBlock = Regex("""^---\s*\n(.*?)\n---\s*\n""", RegexOption.DOT_MATCHES_ALL)
    private val frontmatterEntry = Regex("""^([a-zA-Z0-9_-]+):\s*(.*)$""", RegexOption.MULTILINE)
    private val jsonMapper = com.fasterxml.jackson.databind.ObjectMapper()

    /**
     * List all auto-generated skill drafts in the drafts directory.
     *
     * A draft is a sub-directory of [draftsDir] containing a `SKILL.md` (and optionally
     * a `context.json`). Never throws: an inaccessible or malformed drafts dir yields an empty list.
     */
    fun listDrafts(): List<DraftSummary> {
        if (!Files.isDirectory(draftsDir)) return emptyList()
        return runCatching {
            Files.list(draftsDir).use { stream ->
                stream
                    .filter { Files.isDirectory(it) && Files.exists(it.resolve("SKILL.md")) }
                    .map { parseDraft(it) }
                    .toList()
                    .sortedByDescending { it.generatedAt ?: 0L }
            }
        }.getOrElse {
            logger.warn("Failed to list skill drafts in {}: {}", draftsDir, it.message)
            emptyList()
        }
    }

    private fun parseDraft(dir: Path): DraftSummary {
        val id = dir.fileName.toString()
        val skillId = parseSkillIdFromDirName(id) ?: id
        val generatedAt = parseTimestampFromDirName(id)
            ?: runCatching { Files.getLastModifiedTime(dir).toMillis() }.getOrNull()
        val ctx = runCatching { readContextJson(dir.resolve("context.json")) }.getOrNull()
        val md = runCatching { dir.resolve("SKILL.md").readText() }.getOrDefault("")
        val fm = parseFrontmatter(md)
        val registered = runCatching { registry.contains(skillId) }.getOrDefault(false)
        return DraftSummary(
            id = id,
            skillId = skillId,
            generatedAt = generatedAt,
            sourceTaskId = ctx?.get("taskId") as? String,
            version = fm["version"] ?: "1.0.0",
            description = fm["description"],
            registered = registered,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun readContextJson(file: Path): Map<String, Any?>? {
        if (!Files.isRegularFile(file)) return null
        return runCatching {
            jsonMapper.readValue(file.toFile(), Map::class.java) as Map<String, Any?>
        }.getOrNull()
    }

    private fun parseFrontmatter(md: String): Map<String, String> {
        val block = frontmatterBlock.find(md)?.groupValues?.get(1) ?: return emptyMap()
        return frontmatterEntry.findAll(block)
            .associate { it.groupValues[1] to it.groupValues[2].trim().trim('"', '\'') }
    }

    private fun parseSkillIdFromDirName(dirName: String): String? {
        val idx = dirName.lastIndexOf('-')
        if (idx <= 0) return null
        return if (dirName.substring(idx + 1).toLongOrNull() != null) dirName.substring(0, idx) else null
    }

    private fun parseTimestampFromDirName(dirName: String): Long? {
        val idx = dirName.lastIndexOf('-')
        if (idx < 0) return null
        return dirName.substring(idx + 1).toLongOrNull()
    }

    /**
     * Promote an auto-generated draft into the runtime registry by installing it.
     *
     * @param draftId    the draft directory name ([DraftSummary.id])
     * @param overwrite  if true, overwrite an existing registered skill with the same id
     * @return [SkillInstaller.InstallResult] describing the outcome
     * @throws IllegalArgumentException if the draft does not exist or lacks SKILL.md
     * @throws IllegalStateException if the draft id escapes the drafts directory
     */
    suspend fun promoteDraft(
        draftId: String,
        overwrite: Boolean = false,
    ): SkillInstaller.InstallResult {
        val dir = draftsDir.resolve(draftId).normalize()
        check(dir.startsWith(draftsDir)) { "Invalid draft id: $draftId" }
        require(Files.isDirectory(dir)) { "Draft not found: $draftId" }
        require(Files.exists(dir.resolve("SKILL.md"))) { "SKILL.md not found in draft: $draftId" }
        return installSkill(dir, overwrite)
    }

    // ---- Install ----

    /**
     * Install a skill from a source directory.
     *
     * The source directory must contain a valid `SKILL.md` file.
     *
     * @param sourceDir  path to the skill source directory
     * @param overwrite  if true, overwrite an existing skill with the same ID
     * @return [SkillInstaller.InstallResult] describing the outcome
     */
    suspend fun installSkill(
        sourceDir: Path,
        overwrite: Boolean = false,
    ): SkillInstaller.InstallResult {
        require(Files.isDirectory(sourceDir)) {
            "Skill source is not a directory: ${sourceDir.toAbsolutePath()}"
        }
        require(Files.exists(sourceDir.resolve("SKILL.md"))) {
            "SKILL.md not found in source directory: ${sourceDir.toAbsolutePath()}"
        }

        val context = SkillContext(sessionId = "skill-service-install")
        return installer.install(sourceDir, context, overwrite)
    }

    // ---- Uninstall ----

    /**
     * Uninstall a skill by ID.
     *
     * @param skillId  the skill identifier
     * @return [SkillInstaller.InstallResult] describing the outcome
     * @throws IllegalArgumentException if the skill is not registered
     */
    suspend fun uninstallSkill(skillId: String): SkillInstaller.InstallResult {
        if (!registry.contains(skillId)) {
            throw IllegalArgumentException("Skill '$skillId' is not registered")
        }
        val context = SkillContext(sessionId = "skill-service-uninstall")
        return installer.uninstall(skillId, context)
    }

    // ---- Reload ----

    /**
     * Reload a skill from its source directory.
     *
     * For [DefinitionBackedSkill] instances with a filesystem origin,
     * the definition is re-parsed from disk and the skill is re-registered.
     *
     * @param skillId  the skill identifier
     * @return true if reloaded successfully
     * @throws IllegalArgumentException if the skill is not found
     * @throws IllegalStateException    if the skill cannot be reloaded (e.g. classpath origin)
     */
    suspend fun reloadSkill(skillId: String): Boolean {
        val skill = registry.get(skillId)
            ?: throw IllegalArgumentException("Skill '$skillId' is not registered")

        val context = SkillContext(sessionId = "skill-service-reload")

        when (skill) {
            is DefinitionBackedSkill -> {
                val origin = skill.definition
                // Determine the source directory from the definition's paths
                val sourceDir = origin.scriptsPath?.parent
                    ?: origin.referencesPath?.parent
                    ?: origin.assetsPath?.parent
                    ?: throw IllegalStateException(
                        "Cannot determine source directory for skill '$skillId'"
                    )

                if (!Files.exists(sourceDir.resolve("SKILL.md"))) {
                    throw IllegalStateException(
                        "SKILL.md not found in source directory: $sourceDir"
                    )
                }

                // Re-parse definition from disk
                val definitions = definitionLoader.loadFromDirectory(sourceDir.parent)
                val freshDefinition = definitions.find { it.skillId == skillId }
                    ?: throw IllegalStateException(
                        "Failed to re-parse skill definition from: $sourceDir"
                    )

                val freshSkill = DefinitionBackedSkill(
                    freshDefinition,
                    DefinitionBackedSkill.Origin.FileSystem(sourceDir)
                )

                return loader.reload(freshSkill, context)
            }
            else -> {
                // For programmatic skills, try unload + load (best effort)
                logger.info(
                    "Reloading non-definition-backed skill '{}': will unload and re-load",
                    skillId
                )
                loader.unload(skillId, context)
                return loader.load(skill, context)
            }
        }
    }
}
