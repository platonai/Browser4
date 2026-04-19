package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.common.getLogger
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.io.support.ResourcePatternResolver
import org.springframework.util.ResourceUtils
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Metadata parsed from a SKILL.md file.
 *
 * @property skillId Unique identifier for the skill
 * @property name Human-readable name
 * @property version Semantic version
 * @property author Skill author
 * @property tags Set of categorization tags
 * @property description Full description of the skill
 * @property dependencies List of skill IDs this skill depends on
 * @property parameters Map of parameter names to their descriptions
 * @property examples List of usage examples
 */
data class SkillDefinition(
    val skillId: String,
    val name: String,
    val version: String,
    val author: String,
    val tags: Set<String>,
    val description: String,
    val dependencies: List<String>,
    val parameters: Map<String, ParameterInfo>,
    val examples: List<String>,
    val scriptsPath: Path? = null,
    val referencesPath: Path? = null,
    val assetsPath: Path? = null,
    val allowedTools: Set<String> = emptySet(),
    /** Optional license identifier or bundled license reference (Agent Skills spec). */
    val license: String? = null,
    /** Optional environment requirements / compatibility notes (Agent Skills spec). */
    val compatibility: String? = null,
    /** Arbitrary string metadata mapping (Agent Skills spec). */
    val metadata: Map<String, String> = emptyMap(),
    /** Installation steps extracted from the SKILL.md Installation/Setup section. */
    val installSteps: List<String> = emptyList(),
) {
    /**
     * Information about a skill parameter.
     */
    data class ParameterInfo(
        val name: String,
        val type: String,
        val required: Boolean,
        val defaultValue: String?,
        val description: String
    )
}

/**
 * Loader for skill definitions from directory structure.
 *
 * The SkillDefinitionLoader reads skill metadata from SKILL.md files
 * and provides access to associated resources (scripts, references, assets).
 *
 * Expected directory structure:
 * ```
 * skill-name/
 * ├── SKILL.md          # Required: Skill metadata and documentation
 * ├── scripts/          # Optional: Executable scripts
 * ├── references/       # Optional: Developer documentation
 * └── assets/           # Optional: Configuration and templates
 * ```
 *
 * ## Usage Example:
 * ```kotlin
 * val loader = SkillDefinitionLoader()
 *
 * // Load from resources
 * val definitions = loader.loadFromResources("skills")
 *
 * // Load from filesystem
 * val definitions = loader.loadFromDirectory(Paths.get("/path/to/skills"))
 *
 * // Access skill metadata
 * definitions.forEach { definition ->
 *     println("Skill: ${definition.name} v${definition.version}")
 *     println("Description: ${definition.description}")
 * }
 * ```
 */
class SkillDefinitionLoader {
    private val logger = getLogger(this)

    /**
     * Load skill definitions from a directory.
     *
     * @param skillsDirectory Path to directory containing skill subdirectories
     * @return List of skill definitions found
     */
    fun loadFromDirectory(skillsDirectory: Path): List<SkillDefinition> {
        if (!Files.exists(skillsDirectory) || !Files.isDirectory(skillsDirectory)) {
            logger.warn("Skills directory not found: $skillsDirectory")
            return emptyList()
        }

        val definitions = mutableListOf<SkillDefinition>()

        Files.list(skillsDirectory).use { paths ->
            paths.filter { Files.isDirectory(it) }.forEach { skillDir ->
                try {
                    val definition = loadSkillDefinition(skillDir)
                    if (definition != null) {
                        definitions.add(definition)
                        logger.info("✓ Loaded skill definition: ${definition.skillId}")
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to load skill from ${skillDir.fileName}: ${e.message}")
                }
            }
        }

        return definitions
    }

    /**
     * Load skill definitions from classpath resources.
     *
     * This method loads *all* matching resources from the classpath (across multiple jars/directories).
     *
     * Supports:
     * - exploded resources on filesystem (dev/test)
     * - resources packaged in a JAR (prod)
     * - Spring Boot fat-jar layout (best-effort via Spring Resource abstraction)
     *
     * @param resourcePath Resource path (e.g., "skills", "/skills", "classpath:skills")
     * @return List of skill definitions found
     */
    fun loadFromResources(resourcePath: String): List<SkillDefinition> {
        val normalized = normalizeClasspathResourcePath(resourcePath)
        if (normalized.isBlank()) {
            logger.warn("Resource path is blank")
            return emptyList()
        }

        // Scan for all SKILL.md files under the given resourcePath.
        val resolver: ResourcePatternResolver = PathMatchingResourcePatternResolver(javaClass.classLoader)
        val pattern = "classpath*:$normalized/**/SKILL.md"

        val resources = try {
            resolver.getResources(pattern)
        } catch (e: Exception) {
            logger.warn("Failed to scan resources '{}' with pattern '{}': {}", resourcePath, pattern, e.message)
            emptyArray()
        }

        if (resources.isEmpty()) {
            logger.warn("No skill resources found for '{}' (pattern='{}')", resourcePath, pattern)
            return emptyList()
        }

        val skillRootDirs = resources
            .mapNotNull { r -> resolveSkillRootDir(r) }
            .distinct()

        val loaded = mutableListOf<SkillDefinition>()
        for (skillRoot in skillRootDirs) {
            try {
                val definition = loadSkillDefinition(skillRoot)
                if (definition != null) {
                    loaded.add(definition)
                }
            } catch (e: Exception) {
                logger.warn("Failed to load skill from resource root '{}': {}", skillRoot, e.message)
            }
        }

        // Deduplicate by skillId. Keep deterministic ordering.
        // Current rule: first one wins (typically classpath order; in tests this stays stable).
        val byId = LinkedHashMap<String, SkillDefinition>()
        val conflicts = mutableListOf<String>()
        loaded
            .sortedBy { it.skillId }
            .forEach { def ->
                val prev = byId.putIfAbsent(def.skillId, def)
                if (prev != null) {
                    conflicts.add(def.skillId)
                }
            }

        if (conflicts.isNotEmpty()) {
            logger.warn("Duplicate skill definitions found on classpath for ids={}, first wins", conflicts.distinct().sorted())
        }

        return byId.values.toList().sortedBy { it.skillId }
    }

    private fun normalizeClasspathResourcePath(resourcePath: String): String {
        var p = resourcePath.trim()
        if (p.startsWith("classpath:", ignoreCase = true)) {
            p = p.substringAfter(':')
        }
        while (p.startsWith("/")) {
            p = p.removePrefix("/")
        }
        return p
    }

    private fun resolveSkillRootDir(skillMdResource: Resource): Path? {
        // If this resource is backed by a real file, we can get its parent directory directly.
        try {
            if (ResourceUtils.isFileURL(skillMdResource.url)) {
                return skillMdResource.file.toPath().parent
            }
        } catch (_: Exception) {
            // Fall through
        }

        // Otherwise, extract the whole skill directory to temp based on the SKILL.md stream.
        return try {
            extractSkillDirectoryFromResource(skillMdResource)
        } catch (e: Exception) {
            logger.warn("Failed to extract skill directory from resource '{}': {}", safeResourceId(skillMdResource), e.message)
            null
        }
    }

    private fun extractSkillDirectoryFromResource(skillMdResource: Resource): Path {
        // Derive skillId from URL path (best-effort): .../<skillId>/SKILL.md
        val urlPath = runCatching { skillMdResource.url.toExternalForm() }.getOrDefault("")
        val skillId = urlPath.substringBeforeLast("/SKILL.md").substringAfterLast('/').ifBlank { "skill" }

        val tmpRoot = Files.createTempDirectory("pulsar-skill-")
        tmpRoot.toFile().deleteOnExit()

        val skillOutDir = tmpRoot.resolve(skillId)
        Files.createDirectories(skillOutDir)

        // Always write SKILL.md
        skillMdResource.inputStream.use { input ->
            Files.copy(input, skillOutDir.resolve("SKILL.md"), StandardCopyOption.REPLACE_EXISTING)
        }

        // Best-effort: try to copy known optional directories by scanning for any resources under the same skill root.
        // This works well for filesystem resources and many jar layouts.
        val resolver: ResourcePatternResolver = PathMatchingResourcePatternResolver(javaClass.classLoader)
        val base = urlPath.substringBeforeLast("/SKILL.md")
        val classpathRelative = base.substringAfter(normalizeToClasspathMarker(base))
            .trimStart('/')

        if (classpathRelative.isNotBlank()) {
            copyResourceTree(resolver, "classpath*:$classpathRelative/scripts/**", skillOutDir.resolve("scripts"))
            copyResourceTree(resolver, "classpath*:$classpathRelative/references/**", skillOutDir.resolve("references"))
            copyResourceTree(resolver, "classpath*:$classpathRelative/assets/**", skillOutDir.resolve("assets"))
        }

        return skillOutDir
    }

    private fun copyResourceTree(resolver: ResourcePatternResolver, pattern: String, outDir: Path) {
        val resources = try {
            resolver.getResources(pattern)
        } catch (_: Exception) {
            emptyArray<Resource>()
        }

        if (resources.isEmpty()) {
            return
        }

        for (r in resources) {
            try {
                // Ignore directories / non-readable resources
                if (!r.isReadable) continue

                // Try resolve relative file name from URL; fallback to last segment.
                val url = runCatching { r.url.toExternalForm() }.getOrDefault("")
                val fileName = url.substringAfterLast('/').ifBlank { r.filename ?: continue }
                val dst = outDir.resolve(fileName)

                Files.createDirectories(dst.parent)
                r.inputStream.use { input ->
                    Files.copy(input, dst, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (_: Exception) {
                // Degrade silently; scripts/references/assets are optional.
            }
        }
    }

    private fun normalizeToClasspathMarker(url: String): String {
        // For Spring's common URL forms, try to find a stable marker.
        // - jar:file:...!/BOOT-INF/classes!/skills/... -> after '!/' or '!'
        // - jar:file:...!/skills/... -> after '!/'
        // - file:/.../classes/skills/... -> after last '/classes/'
        val idxBang = url.lastIndexOf("!/")
        if (idxBang >= 0) {
            return url.substring(0, idxBang + 2)
        }

        val idxClasses = url.lastIndexOf("/classes/")
        if (idxClasses >= 0) {
            return url.substring(0, idxClasses + "/classes/".length)
        }

        // Fallback: leave it as-is, caller will likely produce blank classpathRelative.
        return url
    }

    private fun safeResourceId(resource: Resource): String {
        return runCatching { resource.description }.getOrElse { resource.toString() }
    }

    /**
     * Load a single skill definition from a directory.
     *
     * @param skillDirectory Path to skill directory
     * @return Skill definition or null if SKILL.md not found
     */
    private fun loadSkillDefinition(skillDirectory: Path): SkillDefinition? {
        val skillMdPath = skillDirectory.resolve("SKILL.md")
        if (!Files.exists(skillMdPath)) {
            logger.warn("SKILL.md not found in ${skillDirectory.fileName}")
            return null
        }

        val content = Files.readString(skillMdPath)
        val metadata = parseSkillMetadata(content, directoryName = skillDirectory.fileName.toString())

        // Check for optional directories
        val scriptsPath = skillDirectory.resolve("scripts")
        val referencesPath = skillDirectory.resolve("references")
        val assetsPath = skillDirectory.resolve("assets")

        return metadata.copy(
            scriptsPath = if (Files.exists(scriptsPath)) scriptsPath else null,
            referencesPath = if (Files.exists(referencesPath)) referencesPath else null,
            assetsPath = if (Files.exists(assetsPath)) assetsPath else null
        )
    }

    /**
     * Parse skill metadata from SKILL.md content.
     * Supports both YAML frontmatter and traditional markdown format.
     *
     * @param content Content of SKILL.md file
     * @return Parsed skill definition
     */
    private fun parseSkillMetadata(content: String, directoryName: String? = null): SkillDefinition {
        // Check if content starts with YAML frontmatter (---\n...---\n)
        if (content.trim().startsWith("---")) {
            val yamlMetadata = parseYamlFrontmatter(content)
            if (yamlMetadata != null) {
                if (!directoryName.isNullOrBlank()) {
                    // Provide directory name for spec validation
                    (yamlMetadata as MutableMap<String, Any>)["__directory_name__"] = directoryName
                }
                return parseFromYamlAndMarkdown(content, yamlMetadata)
            }
        }

        // Fall back to traditional markdown parsing
        return parseFromMarkdown(content)
    }

    /**
     * Extract YAML frontmatter from SKILL.md content.
     *
     * @param content Content of SKILL.md file
     * @return Map of YAML metadata, or null if no frontmatter found
     */
    private fun parseYamlFrontmatter(content: String): Map<String, Any>? {
        val lines = content.lines()
        if (lines.isEmpty() || lines[0].trim() != "---") {
            return null
        }

        val yamlLines = mutableListOf<String>()
        var endIndex = -1

        // Find the closing ---
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") {
                endIndex = i
                break
            }
            yamlLines.add(lines[i])
        }

        if (endIndex == -1) {
            return null
        }

        val yamlText = yamlLines.joinToString("\n")
        val yaml = Yaml()
        val loaded = yaml.load<Any?>(yamlText) ?: return emptyMap()

        require(loaded is Map<*, *>) { "Invalid YAML frontmatter: expected mapping" }

        val result = mutableMapOf<String, Any>()
        loaded.entries.forEach { (k, v) ->
            if (k != null) {
                result[k.toString()] = v as Any
            }
        }

        return result
    }

    /**
     * Parse skill definition from YAML frontmatter and remaining markdown.
     *
     * @param content Full SKILL.md content
     * @param yamlMetadata Parsed YAML metadata
     * @return Parsed skill definition
     */
    private fun parseFromYamlAndMarkdown(
        content: String,
        yamlMetadata: Map<String, Any>
    ): SkillDefinition {
        // Extract metadata from YAML
        val rawName = (yamlMetadata["name"] as? String).orEmpty().trim()
        val rawDescription = (yamlMetadata["description"] as? String).orEmpty().trim()

        require(rawName.isNotBlank()) { "Skill name is required in SKILL.md" }
        require(rawDescription.isNotBlank()) { "Skill description is required in SKILL.md" }
        require(rawDescription.length in 1..1024) { "Skill description length must be 1..1024, actual=${rawDescription.length}" }

        validateSkillName(rawName)

        // Spec: 'name' is the canonical identifier and must match the directory name.
        val directoryName = (yamlMetadata["__directory_name__"] as? String)?.trim().orEmpty()
        if (directoryName.isNotBlank()) {
            require(rawName == directoryName) {
                "Skill name must match directory name. name='$rawName', dir='$directoryName'"
            }
        }

        val skillId = rawName
        val name = rawName

        // Optional fields in spec
        // (Currently parsed but not stored in SkillDefinition model)
        // val license = (yamlMetadata["license"] as? String)?.trim().orEmpty()
        // val compatibility = (yamlMetadata["compatibility"] as? String)?.trim().orEmpty()

        val allowedTools: Set<String> = (yamlMetadata["allowed-tools"] as? String)
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

        // Support tags/dependencies in either top-level keys (legacy) or metadata (spec extension).
        val tags: Set<String> = when (val t = yamlMetadata["tags"] ?: (yamlMetadata["metadata"] as? Map<*, *>)?.get("tags")) {
            is List<*> -> t.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }.toSet()
            is String -> t.split(Regex("[,\\s]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            else -> emptySet()
        }

        val dependencies: List<String> = when (val d = yamlMetadata["dependencies"] ?: (yamlMetadata["metadata"] as? Map<*, *>)?.get("dependencies")) {
            is List<*> -> d.mapNotNull { it?.toString()?.trim() }.filter { it.isNotEmpty() }
            is String -> d.split(Regex("[,\\s]+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            else -> emptyList()
        }

        // Parse remaining sections from markdown (parameters, examples)
        val markdownSections = parseMarkdownSections(content)
        val parameters = (markdownSections["parameters"] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (k, v) ->
                val key = k?.toString() ?: return@mapNotNull null
                val value = v as? SkillDefinition.ParameterInfo ?: return@mapNotNull null
                key to value
            }
            ?.toMap()
            ?: emptyMap()

        val examples = (markdownSections["examples"] as? List<*>)
            ?.mapNotNull { it?.toString() }
            ?: emptyList()

        // Optional fields in spec
        val license = (yamlMetadata["license"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val compatibility = (yamlMetadata["compatibility"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        if (compatibility != null) {
            require(compatibility.length <= 500) { "Skill compatibility length must be <= 500, actual=${compatibility.length}" }
        }

        // Spec metadata: arbitrary key-value mapping
        val metadata: Map<String, String> = (yamlMetadata["metadata"] as? Map<*, *>)
            ?.entries
            ?.filter { it.key != null && it.value != null }
            ?.associate { it.key.toString() to it.value.toString() }
            ?: emptyMap()

        // Parse installation steps from markdown
        val installSteps = (markdownSections["installSteps"] as? List<*>)
            ?.mapNotNull { it?.toString() }
            ?: emptyList()

        return SkillDefinition(
            skillId = skillId,
            name = name,
            version = (yamlMetadata["version"] as? String)?.trim()
                ?: (metadata["version"] ?: "1.0.0"),
            author = (yamlMetadata["author"] as? String)?.trim()
                ?: (metadata["author"] ?: ""),
            tags = tags,
            description = rawDescription,
            dependencies = dependencies,
            parameters = parameters,
            examples = examples,
            allowedTools = allowedTools,
            license = license,
            compatibility = compatibility,
            metadata = metadata,
            installSteps = installSteps,
        )
    }

    private fun validateSkillName(name: String) {
        require(name.length in 1..64) { "Skill name length must be 1..64, actual=${name.length}" }
        require(!name.contains("--")) { "Invalid skill name '$name'. Must not contain consecutive hyphens ('--')." }
        require(name.matches(Regex("[a-z0-9]+(?:-[a-z0-9]+)*"))) {
            "Invalid skill name '$name'. Only lowercase letters, digits and single hyphens are allowed."
        }
    }

    /**
     * Parse skill definition from traditional markdown format.
     * This is the legacy format parser.
     *
     * @param content Content of SKILL.md file
     * @return Parsed skill definition
     */
    private fun parseFromMarkdown(content: String): SkillDefinition {
        val lines = content.lines()

        // Extract metadata section
        var skillId = ""
        var name = ""
        var version = "1.0.0"
        var author = ""
        val tags = mutableSetOf<String>()
        var description = ""
        val dependencies = mutableListOf<String>()
        val parameters = mutableMapOf<String, SkillDefinition.ParameterInfo>()
        val examples = mutableListOf<String>()
        val installSteps = mutableListOf<String>()

        var inMetadataSection = false
        var inDescriptionSection = false
        var inDependenciesSection = false
        var inParametersSection = false
        var inExamplesSection = false
        var inInstallSection = false

        var currentExample = StringBuilder()

        for (line in lines) {
            when {
                line.trim().startsWith("## Metadata") -> {
                    inMetadataSection = true
                    inDescriptionSection = false
                    inDependenciesSection = false
                    inParametersSection = false
                    inExamplesSection = false
                    inInstallSection = false
                }
                line.trim().startsWith("## Description") -> {
                    inMetadataSection = false
                    inDescriptionSection = true
                    inDependenciesSection = false
                    inParametersSection = false
                    inExamplesSection = false
                    inInstallSection = false
                }
                line.trim().startsWith("## Dependencies") -> {
                    inMetadataSection = false
                    inDescriptionSection = false
                    inDependenciesSection = true
                    inParametersSection = false
                    inExamplesSection = false
                    inInstallSection = false
                }
                line.trim().startsWith("## Parameters") -> {
                    inMetadataSection = false
                    inDescriptionSection = false
                    inDependenciesSection = false
                    inParametersSection = true
                    inExamplesSection = false
                    inInstallSection = false
                }
                line.trim().startsWith("## Usage Examples") ||
                line.trim().startsWith("## Examples") -> {
                    inMetadataSection = false
                    inDescriptionSection = false
                    inDependenciesSection = false
                    inParametersSection = false
                    inExamplesSection = true
                    inInstallSection = false
                }
                line.trim().matches(Regex("^##\\s+(Installation|Install|Setup|Getting Started).*", RegexOption.IGNORE_CASE)) -> {
                    if (inExamplesSection && currentExample.isNotEmpty()) {
                        examples.add(currentExample.toString().trim())
                        currentExample = StringBuilder()
                    }
                    inMetadataSection = false
                    inDescriptionSection = false
                    inDependenciesSection = false
                    inParametersSection = false
                    inExamplesSection = false
                    inInstallSection = true
                }
                line.trim().startsWith("##") -> {
                    // End of current section
                    if (inExamplesSection && currentExample.isNotEmpty()) {
                        examples.add(currentExample.toString().trim())
                        currentExample = StringBuilder()
                    }
                    inMetadataSection = false
                    inDescriptionSection = false
                    inDependenciesSection = false
                    inParametersSection = false
                    inExamplesSection = false
                    inInstallSection = false
                }
                inMetadataSection -> {
                    when {
                        line.contains("**Skill ID**:") -> {
                            skillId = extractValue(line)
                        }
                        line.contains("**Name**:") -> {
                            name = extractValue(line)
                        }
                        line.contains("**Version**:") -> {
                            version = extractValue(line)
                        }
                        line.contains("**Author**:") -> {
                            author = extractValue(line)
                        }
                        line.contains("**Tags**:") -> {
                            val tagsStr = extractValue(line)
                            tags.addAll(tagsStr.split(",").map { it.trim().removePrefix("`").removeSuffix("`") })
                        }
                    }
                }
                inDescriptionSection -> {
                    if (line.isNotBlank() && !line.trim().startsWith("#")) {
                        description += line + "\n"
                    }
                }
                inDependenciesSection -> {
                    if (line.trim().startsWith("-") || line.trim().startsWith("*")) {
                        val depLine = line.trim().removePrefix("-").removePrefix("*").trim()
                        // Extract skill ID from backticks (e.g., "`web-scraping` - description" -> "web-scraping")
                        val dep = if (depLine.contains("`")) {
                            depLine.substringAfter("`").substringBefore("`")
                        } else {
                            depLine
                        }
                        if (dep.isNotBlank() && dep.lowercase() != "none") {
                            dependencies.add(dep)
                        }
                    }
                }
                inParametersSection -> {
                    // Parse parameter table rows
                    if (line.trim().startsWith("|") &&
                        !line.contains("Parameter") &&
                        !line.contains("---")) {
                        val parts = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                        if (parts.size >= 4) {
                            val paramName = parts[0]
                            val paramType = parts[1]
                            val required = parts[2].lowercase() == "yes"
                            val defaultValue = parts[3]
                            val paramDesc = if (parts.size > 4) parts[4] else ""

                            parameters[paramName] = SkillDefinition.ParameterInfo(
                                name = paramName,
                                type = paramType,
                                required = required,
                                defaultValue = defaultValue,
                                description = paramDesc
                            )
                        }
                    }
                }
                inInstallSection -> {
                    val trimmed = line.trim()
                    val numberedMatch = Regex("^\\d+\\.\\s+(.+)").find(trimmed)
                    if (numberedMatch != null) {
                        installSteps.add(numberedMatch.groupValues[1].trim())
                    } else {
                        val bulletMatch = Regex("^[-*]\\s+(.+)").find(trimmed)
                        if (bulletMatch != null) {
                            installSteps.add(bulletMatch.groupValues[1].trim())
                        }
                    }
                }
                inExamplesSection -> {
                    if (line.trim().startsWith("```")) {
                        if (currentExample.isNotEmpty()) {
                            examples.add(currentExample.toString().trim())
                            currentExample = StringBuilder()
                        }
                    } else if (line.isNotBlank()) {
                        currentExample.append(line).append("\n")
                    }
                }
            }
        }

        // Add last example if exists
        if (currentExample.isNotEmpty()) {
            examples.add(currentExample.toString().trim())
        }

        // Validate required fields
        require(skillId.isNotBlank()) { "Skill ID is required in SKILL.md" }
        require(name.isNotBlank()) { "Skill name is required in SKILL.md" }

        return SkillDefinition(
            skillId = skillId,
            name = name,
            version = version,
            author = author,
            tags = tags,
            description = description.trim(),
            dependencies = dependencies,
            parameters = parameters,
            examples = examples,
            installSteps = installSteps
        )
    }

    /**
     * Extract value from a metadata line.
     */
    private fun extractValue(line: String): String {
        return line.substringAfter(":")
            .trim()
            .removePrefix("`")
            .removeSuffix("`")
    }

    /**
     * Parse markdown sections (description, parameters, examples) from SKILL.md content.
     * Used when YAML frontmatter is present for metadata.
     *
     * @param content Full SKILL.md content
     * @return Map of section name to parsed content
     */
    private fun parseMarkdownSections(content: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val lines = content.lines()

        val description = StringBuilder()
        val parameters = mutableMapOf<String, SkillDefinition.ParameterInfo>()
        val examples = mutableListOf<String>()
        val installSteps = mutableListOf<String>()

        var inDescriptionSection = false
        var inParametersSection = false
        var inExamplesSection = false
        var inInstallSection = false
        var currentExample = StringBuilder()

        // Skip YAML frontmatter (both opening and closing ---). Start parsing after the second delimiter.
        var frontmatterDelimitersSeen = 0

        for (line in lines) {
            if (frontmatterDelimitersSeen < 2) {
                if (line.trim() == "---") {
                    frontmatterDelimitersSeen++
                }
                continue
            }

            when {
                line.trim().startsWith("## Description") -> {
                    if (inExamplesSection && currentExample.isNotEmpty()) {
                        examples.add(currentExample.toString().trim())
                        currentExample = StringBuilder()
                    }
                    inDescriptionSection = true
                    inParametersSection = false
                    inExamplesSection = false
                    inInstallSection = false
                }
                line.trim().startsWith("## Parameters") -> {
                    if (inExamplesSection && currentExample.isNotEmpty()) {
                        examples.add(currentExample.toString().trim())
                        currentExample = StringBuilder()
                    }
                    inDescriptionSection = false
                    inParametersSection = true
                    inExamplesSection = false
                    inInstallSection = false
                }
                line.trim().startsWith("## Usage Examples") ||
                line.trim().startsWith("## Examples") -> {
                    if (inExamplesSection && currentExample.isNotEmpty()) {
                        examples.add(currentExample.toString().trim())
                        currentExample = StringBuilder()
                    }
                    inDescriptionSection = false
                    inParametersSection = false
                    inExamplesSection = true
                    inInstallSection = false
                }
                line.trim().matches(Regex("^##\\s+(Installation|Install|Setup|Getting Started).*", RegexOption.IGNORE_CASE)) -> {
                    if (inExamplesSection && currentExample.isNotEmpty()) {
                        examples.add(currentExample.toString().trim())
                        currentExample = StringBuilder()
                    }
                    inDescriptionSection = false
                    inParametersSection = false
                    inExamplesSection = false
                    inInstallSection = true
                }
                line.trim().startsWith("##") -> {
                    // Another section, stop current parsing
                    if (inExamplesSection && currentExample.isNotEmpty()) {
                        examples.add(currentExample.toString().trim())
                        currentExample = StringBuilder()
                    }
                    inDescriptionSection = false
                    inParametersSection = false
                    inExamplesSection = false
                    inInstallSection = false
                }
                inDescriptionSection && line.trim().isNotEmpty() -> {
                    if (description.isNotEmpty()) {
                        description.append(" ")
                    }
                    description.append(line.trim())
                }
                inParametersSection && line.trim().startsWith("|") -> {
                    // Parse parameter table row
                    val param = parseParameterRow(line)
                    if (param != null) {
                        parameters[param.name] = param
                    }
                }
                inInstallSection -> {
                    val trimmed = line.trim()
                    // Match numbered steps (1. Step description)
                    val numberedMatch = Regex("^\\d+\\.\\s+(.+)").find(trimmed)
                    if (numberedMatch != null) {
                        installSteps.add(numberedMatch.groupValues[1].trim())
                    } else {
                        // Match bulleted steps (- Step description or * Step description)
                        val bulletMatch = Regex("^[-*]\\s+(.+)").find(trimmed)
                        if (bulletMatch != null) {
                            installSteps.add(bulletMatch.groupValues[1].trim())
                        }
                    }
                }
                inExamplesSection -> {
                    if (line.trim().startsWith("###")) {
                        // New example
                        if (currentExample.isNotEmpty()) {
                            examples.add(currentExample.toString().trim())
                            currentExample = StringBuilder()
                        }
                        currentExample.append(line).append("\n")
                    } else if (currentExample.isNotEmpty() || line.trim().isNotEmpty()) {
                        currentExample.append(line).append("\n")
                    }
                }
            }
        }

        // Add last example if any
        if (inExamplesSection && currentExample.isNotEmpty()) {
            examples.add(currentExample.toString().trim())
        }

        result["description"] = description.toString().trim()
        result["parameters"] = parameters
        result["examples"] = examples
        result["installSteps"] = installSteps

        return result
    }

    /**
     * Parse a markdown table row in the Parameters section.
     *
     * Expected formats (both supported):
     * - `| name | type | required | default | description |`
     * - `| name | type | required | description |` (default omitted)
     *
     * Header rows and separator rows (e.g. `| --- | --- |`) return null.
     */
    private fun parseParameterRow(line: String): SkillDefinition.ParameterInfo? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("|")) {
            return null
        }

        // Split by pipe and discard the leading/trailing empties caused by outer pipes.
        val cells = trimmed.split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (cells.isEmpty()) return null

        // Skip header rows
        val headerTokens = setOf("parameter", "name", "type", "required", "default", "description")
        if (cells.any { it.lowercase() in headerTokens } && cells.size <= 6) {
            return null
        }

        // Skip separator rows like | --- | --- |
        if (cells.all { it.isNotBlank() && it.all { ch -> ch == '-' || ch == ':' } }) {
            return null
        }

        // Need at least: name, type, required, (default?), description
        if (cells.size < 4) return null

        val name = cells[0].removeSurrounding("`")
        val type = cells[1].removeSurrounding("`")
        val requiredToken = cells[2].removeSurrounding("`").lowercase()

        val required = requiredToken in setOf("yes", "y", "true", "required")

        // Two common layouts:
        // 1) 5 columns: name, type, required, default, description
        // 2) 4 columns: name, type, required, description
        val (defaultValueRaw, descriptionRaw) = when {
            cells.size >= 5 -> cells[3] to cells.subList(4, cells.size).joinToString(" | ")
            else -> "" to cells.subList(3, cells.size).joinToString(" | ")
        }

        val defaultValue = defaultValueRaw
            .removeSurrounding("`")
            .trim()
            .takeIf { it.isNotBlank() && it.lowercase() != "none" }

        val description = descriptionRaw.removeSurrounding("`").trim()

        if (name.isBlank()) return null

        return SkillDefinition.ParameterInfo(
            name = name,
            type = type,
            required = required,
            defaultValue = defaultValue,
            description = description
        )
    }

    /**
     * Get list of scripts in a skill's scripts directory.
     *
     * @param definition Skill definition
     * @return List of script file paths
     */
    fun getSkillScripts(definition: SkillDefinition): List<Path> {
        val scriptsPath = definition.scriptsPath ?: return emptyList()
        if (!Files.exists(scriptsPath)) return emptyList()

        return Files.list(scriptsPath)
            .filter { Files.isRegularFile(it) }
            .toList()
    }

    /**
     * Get list of reference documents in a skill's references directory.
     *
     * @param definition Skill definition
     * @return List of reference file paths
     */
    fun getSkillReferences(definition: SkillDefinition): List<Path> {
        val referencesPath = definition.referencesPath ?: return emptyList()
        if (!Files.exists(referencesPath)) return emptyList()

        return Files.list(referencesPath)
            .filter { Files.isRegularFile(it) }
            .toList()
    }

    /**
     * Get list of assets in a skill's assets directory.
     *
     * @param definition Skill definition
     * @return List of asset file paths
     */
    fun getSkillAssets(definition: SkillDefinition): List<Path> {
        val assetsPath = definition.assetsPath ?: return emptyList()
        if (!Files.exists(assetsPath)) return emptyList()

        return Files.list(assetsPath)
            .filter { Files.isRegularFile(it) }
            .toList()
    }
}
