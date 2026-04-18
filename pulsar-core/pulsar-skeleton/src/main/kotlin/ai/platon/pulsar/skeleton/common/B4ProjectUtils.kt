package ai.platon.pulsar.skeleton.common

import ai.platon.pulsar.common.getLogger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.notExists

object B4ProjectUtils {
    private val logger = getLogger(this)

    const val CODE_MIRROR_DIR = "code-mirror"

    const val CODE_RESOURCE_DIR = "pulsar-core/pulsar-resources/src/main/resources/$CODE_MIRROR_DIR"

    fun isInJar(): Boolean {
        val location = this::class.java.protectionDomain.codeSource.location
        return location.protocol == "jar" || location.path.endsWith(".jar")
    }

    /**
     * Finds the project root directory by searching for a file named `VERSION` in the current directory
     * and its parent directories.
     *
     * @return The project root directory if found, otherwise null.
     */
    fun findProjectRootDir(): Path? = findProjectRootDir(Paths.get(".").toAbsolutePath().normalize())

    /**
     * Finds the project root directory by searching for a file named `VERSION` starting from the specified directory
     * and traversing up its parent directories.
     *
     * @param startDir The directory to start the search from.
     * @return The project root directory if found, otherwise null.
     */
    fun findProjectRootDir(startDir: Path, deepSearch: Boolean = true): Path? {
        if (isInJar()) {
            return null
        }

        var projectRootDir: Path? = startDir

        while (projectRootDir != null && projectRootDir.resolve("VERSION").notExists()) {
            projectRootDir = projectRootDir.parent
        }

        if (projectRootDir == null && deepSearch) {
            // The working directory may not be the project root, try to find the module directory first and then search for the project root.
            val moduleDir = Files.walk(startDir).filter { it.fileName.toString().endsWith("pulsar-common") }
                .findFirst().orElse(null)?.toAbsolutePath()

            if (moduleDir != null) {
                return findProjectRootDir(moduleDir, false)
            }
        }

        if (projectRootDir == null) {
            logger.warn("Project root directory not found. Please ensure you are running within a project structure containing a VERSION file.")
        }

        return projectRootDir
    }

    fun copySourceFileAsCodeResource(source: Path): Boolean {
        val rootDir = findProjectRootDir() ?: return false
        val destPath = rootDir.resolve(CODE_RESOURCE_DIR)

        val filename = source.fileName.toString() + ".txt"
        val target = destPath.resolve(filename)
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)

        return true
    }

    /**
     * Walks through the directory tree starting from the specified base directory to find a file with the given name.
     *
     * Excludes any files located in "target" and "build" directories to avoid unnecessary processing of build artifacts.
     *
     * This method works only when running in an environment where the project structure is accessible (i.e., not in a JAR environment). If the project root directory cannot be found, it returns an empty list.

     * @param fileName The name of the file to find.
     * @param baseDir The directory to start the search from.
     * @return The list of paths to the files that match the specified name.
     */
    fun walkToFindFiles(
        fileName: String, baseDir: Path,
        excludePaths: List<String> = listOf("/target/", "/build/", "/test/")
    ): List<Path> {
        return Files.walk(baseDir)
            .filter { it.fileName.toString() == fileName }
            .filter { path -> excludePaths.none { path.toString().contains(it) } }
            .toList()
    }

    /**
     * Finds the project root directory and then searches for a file with the specified name within the project structure.
     *
     * Excludes any files located in "target" and "build" directories to avoid unnecessary processing of build artifacts.
     *
     * This method works only when running in an environment where the project structure is accessible (i.e., not in a JAR environment). If the project root directory cannot be found, it returns an empty list.
     *
     * @param fileName The name of the file to find.
     * @return The list of paths to the files that match the specified name.
     */
    fun findFiles(fileName: String): List<Path> {
        val projectRootDir = findProjectRootDir()
        return if (projectRootDir != null) {
            walkToFindFiles(fileName, projectRootDir)
        } else emptyList()
    }

    /**
     * Finds the project root directory and then searches for a file with the specified name within the project structure.
     *
     * Excludes any files located in "target" and "build" directories to avoid unnecessary processing of build artifacts.
     *
     * This method works only when running in an environment where the project structure is accessible (i.e., not in a JAR environment). If the project root directory cannot be found, it returns an empty list.
     *
     * @param fileName The name of the file to find.
     * @return The list of paths to the files that match the specified name.
     */
    fun findFiles(moduleName: String, fileName: String): List<Path> {
        val projectRootDir = findProjectRootDir() ?: return emptyList()
        val moduleRootDir = Files.walk(projectRootDir).filter { it.fileName.toString() == moduleName }.toList()
        return if (moduleRootDir.isNotEmpty()) {
            walkToFindFiles(fileName, moduleRootDir.first())
        } else emptyList()
    }
}
