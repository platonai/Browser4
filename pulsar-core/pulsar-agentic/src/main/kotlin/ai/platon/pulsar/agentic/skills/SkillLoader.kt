package ai.platon.pulsar.agentic.skills

import ai.platon.pulsar.common.getLogger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

/**
 * Loads Skills from various sources (classpath, filesystem, etc.).
 *
 * The SkillLoader supports:
 * - Loading compiled Skill classes from the classpath
 * - Discovering skill definitions in directories
 * - Auto-registration with a SkillRegistry
 *
 * Skills can be:
 * 1. Compiled Kotlin/Java classes implementing the Skill interface
 * 2. Script-based skills (future enhancement)
 * 3. Declarative skill definitions (future enhancement)
 */
class SkillLoader(
    private val registry: SkillRegistry
) {
    private val logger = getLogger(SkillLoader::class)

    /**
     * Load a skill class by its fully qualified name.
     *
     * @param className Fully qualified class name (e.g., "com.example.MySkill").
     * @return true if the skill was loaded and registered successfully.
     */
    fun loadSkillClass(className: String): Boolean {
        return runCatching {
            val clazz = Class.forName(className)
            if (!Skill::class.java.isAssignableFrom(clazz)) {
                logger.warn("Class $className does not implement Skill interface")
                return false
            }

            @Suppress("UNCHECKED_CAST")
            val skillClass = clazz as Class<out Skill>
            val skill = skillClass.getDeclaredConstructor().newInstance()
            
            registry.register(skill)
        }.getOrElse { e ->
            logger.error("Failed to load skill class: $className", e)
            false
        }
    }

    /**
     * Load all skills from a directory.
     * This method looks for compiled .class files in the directory.
     *
     * @param directory Path to directory containing skill classes.
     * @param basePackage Base package name for the classes.
     * @return Number of skills successfully loaded.
     */
    fun loadFromDirectory(directory: Path, basePackage: String = ""): Int {
        if (!directory.exists() || !directory.isDirectory()) {
            logger.warn("Directory does not exist or is not a directory: $directory")
            return 0
        }

        var loadedCount = 0
        
        runCatching {
            Files.walk(directory)
                .asSequence()
                .filter { it.isRegularFile() && it.toString().endsWith(".class") }
                .forEach { classFile ->
                    val relativePath = directory.relativize(classFile).toString()
                    val className = relativePath
                        .replace("/", ".")
                        .replace("\\", ".")
                        .removeSuffix(".class")
                    
                    val fullClassName = if (basePackage.isNotEmpty()) {
                        "$basePackage.$className"
                    } else {
                        className
                    }

                    if (loadSkillClass(fullClassName)) {
                        loadedCount++
                    }
                }
        }.onFailure { e ->
            logger.error("Error loading skills from directory: $directory", e)
        }

        logger.info("Loaded $loadedCount skills from directory: $directory")
        return loadedCount
    }

    /**
     * Load multiple skill classes by their fully qualified names.
     *
     * @param classNames List of fully qualified class names.
     * @return Number of skills successfully loaded.
     */
    fun loadSkillClasses(classNames: List<String>): Int {
        var loadedCount = 0
        classNames.forEach { className ->
            if (loadSkillClass(className)) {
                loadedCount++
            }
        }
        return loadedCount
    }

    /**
     * Auto-discover and load skills from a standard location.
     * Looks for skills in the "skills" package of the application.
     *
     * @param packagePrefix Optional package prefix (e.g., "ai.platon.pulsar.agentic.skills").
     * @return Number of skills discovered and loaded.
     */
    fun autoDiscoverSkills(packagePrefix: String = "ai.platon.pulsar.agentic.skills"): Int {
        logger.info("Auto-discovering skills in package: $packagePrefix")
        
        // For now, this is a placeholder for auto-discovery
        // Real implementation would use classpath scanning or ServiceLoader
        // For this version, we'll rely on explicit registration
        
        return 0
    }

    /**
     * Load built-in skills that ship with the system.
     * These are the core skills provided by the framework.
     *
     * @return Number of built-in skills loaded.
     */
    fun loadBuiltInSkills(): Int {
        val builtInSkillClasses = listOf(
            "ai.platon.pulsar.agentic.skills.builtin.NavigationSkill",
            "ai.platon.pulsar.agentic.skills.builtin.DataExtractionSkill",
            "ai.platon.pulsar.agentic.skills.builtin.FormInteractionSkill",
        )

        return loadSkillClasses(builtInSkillClasses)
    }
}

/**
 * Builder for configuring and creating a SkillLoader.
 */
class SkillLoaderBuilder {
    private var registry: SkillRegistry? = null
    private val skillDirectories = mutableListOf<Pair<Path, String>>()
    private val skillClasses = mutableListOf<String>()
    private var loadBuiltIns = true
    private var autoDiscover = false

    /**
     * Set the skill registry to use.
     */
    fun registry(registry: SkillRegistry) = apply {
        this.registry = registry
    }

    /**
     * Add a directory to scan for skills.
     *
     * @param directory Path to the directory.
     * @param basePackage Base package for classes in this directory.
     */
    fun addDirectory(directory: Path, basePackage: String = "") = apply {
        skillDirectories.add(directory to basePackage)
    }

    /**
     * Add a skill class to load.
     *
     * @param className Fully qualified class name.
     */
    fun addClass(className: String) = apply {
        skillClasses.add(className)
    }

    /**
     * Enable or disable loading of built-in skills.
     */
    fun loadBuiltInSkills(load: Boolean) = apply {
        this.loadBuiltIns = load
    }

    /**
     * Enable or disable auto-discovery of skills.
     */
    fun autoDiscover(discover: Boolean) = apply {
        this.autoDiscover = discover
    }

    /**
     * Build the SkillLoader and load all configured skills.
     *
     * @return The configured SkillLoader with skills loaded.
     */
    fun build(): SkillLoader {
        val reg = registry ?: SkillRegistry()
        val loader = SkillLoader(reg)

        // Load built-in skills
        if (loadBuiltIns) {
            loader.loadBuiltInSkills()
        }

        // Auto-discover skills
        if (autoDiscover) {
            loader.autoDiscoverSkills()
        }

        // Load from directories
        skillDirectories.forEach { (dir, pkg) ->
            loader.loadFromDirectory(dir, pkg)
        }

        // Load individual classes
        loader.loadSkillClasses(skillClasses)

        return loader
    }
}
