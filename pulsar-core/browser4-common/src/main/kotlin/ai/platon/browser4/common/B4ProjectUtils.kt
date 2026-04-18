package ai.platon.browser4.common

import org.slf4j.LoggerFactory
import java.io.*
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.io.path.notExists
import kotlin.io.path.toPath

object B4ResourceLoader {

    private val logger = LoggerFactory.getLogger(B4ResourceLoader::class.java)
    private val lastModifiedTimes = ConcurrentHashMap<Path, Instant>()
    private val userClassFactories = ConcurrentLinkedDeque<ClassFactory>()
    private val classLoader = Thread.currentThread().contextClassLoader ?: B4ResourceLoader::class.java.classLoader

    private val DEFAULT_LINE_FILTER: (line: String) -> Boolean = { line ->
        !line.startsWith("# ") && !line.startsWith("-- ") && line.isNotBlank()
    }

    /**
     * Add a class factory in order to manage more than one class loader.
     *
     * @param classFactory An object that implements ClassFactory
     */
    fun addClassFactory(classFactory: ClassFactory) {
        userClassFactories.add(classFactory)
    }

    /**
     * Remove a class factory
     *
     * @param classFactory Already inserted class factory instance
     */
    fun removeClassFactory(classFactory: ClassFactory) {
        userClassFactories.remove(classFactory)
    }

    /**
     * Load a class, but check if it is allowed to load this class first. To
     * perform access rights checking, the system property h2.allowedClasses
     * needs to be set to a list of class file name prefixes.
     *
     * @param className the name of the class
     * @return the class object
     */
    @Throws(ClassNotFoundException::class)
    fun <Z> loadUserClass(className: String): Class<Z> {
        // Use provided class factory first.
        for (classFactory in userClassFactories) {
            if (classFactory.match(className)) {
                try {
                    val userClass = classFactory.loadClass(className)
                    if (userClass != null) {
                        @Suppress("UNCHECKED_CAST")
                        return userClass as Class<Z>
                    }
                } catch (ignored: ClassNotFoundException) {
                    // ignore, try other class loaders
                } catch (e: Exception) {
                    throw e
                }
            }
        }

        // Use local ClassLoader
        return try {
            @Suppress("UNCHECKED_CAST")
            Class.forName(className) as Class<Z>
        } catch (e: ClassNotFoundException) {
            try {
                @Suppress("UNCHECKED_CAST")
                Class.forName(className, true, Thread.currentThread().contextClassLoader) as Class<Z>
            } catch (e2: Exception) {
                throw e2
            }
        } catch (e: Error) {
            throw e
        }
    }

    /**
     * Read all lines from one of the following resource: string, file by file name and resource by resource name
     * The front resource have higher priority
     */
    @JvmOverloads
    fun readAllLines(
        stringResource: String?, resource: String, resourcePrefix: String = "", filter: Boolean = true
    ): List<String> {
        return getMultiSourceReader(stringResource, resource, resourcePrefix)?.useLines { seq ->
            if (filter) {
                seq.filter(DEFAULT_LINE_FILTER).toList()
            } else {
                seq.toList()
            }
        } ?: listOf()
    }

    fun readAllLines(resource: String) = readAllLines(resource, true)

    fun readAllLines(resource: String, filter: Boolean): List<String> {
        if (!filter) {
            return readAllLinesNoFilter(resource)
        }

        return getResourceAsReader(resource)?.useLines { it.filter(DEFAULT_LINE_FILTER).toList() } ?: listOf()
    }

    fun readAllLines(resource: String, filter: (String) -> Boolean = { true }): List<String> {
        return getResourceAsReader(resource)?.useLines { it.filter(filter).toList() } ?: listOf()
    }

    fun readAllLinesNoFilter(resource: String): List<String> {
        return getResourceAsReader(resource)?.useLines {
            it.toList()
        } ?: listOf()
    }

    fun readAllLinesIfModified(path: Path): List<String> {
        val lastModified = lastModifiedTimes.getOrDefault(path, Instant.EPOCH)
        val modified = Files.getLastModifiedTime(path).toInstant()

        return takeIf { modified > lastModified }
            ?.let { Files.readAllLines(path).also { lastModifiedTimes[path] = modified } }
            ?: listOf()
    }

    fun readString(resource: String): String {
        return readStringTo(resource, StringBuilder()).toString()
    }

    fun readStringTo(resource: String, sb: StringBuilder): StringBuilder {
        getResourceAsReader(resource)?.forEachLine {
            sb.appendLine(it)
        }
        return sb
    }

    /**
     * Get a [Reader] attached to the configuration resource with the
     * given `name`.
     *
     * @param name resource name.
     * @return a reader attached to the resource.
     */
    fun getResourceAsStream(name: String): InputStream? {
        return try {
            val url = getURLOrNull(name) ?: return null
            if (logger.isDebugEnabled) {
                logger.debug("Find resource $name | $url")
            }
            url.openStream()
        } catch (e: IOException) {
            logger.warn("Failed to read resource {} | {}", name, e.message)
            null
        }
    }

    /**
     * Find the first resource associated by prefix/name
     */
    fun getResourceAsStream(resource: String, vararg resourcePrefixes: String): InputStream? {
        var found = false
        return resourcePrefixes.asSequence().filter { it.isNotBlank() }
            .mapNotNull { if (!found) getResourceAsStream("$it/$resource") else null }
            .onEach { found = true }
            .firstOrNull() ?: getResourceAsStream(resource)
    }

    /**
     * Get a [Reader] attached to the configuration resource with the
     * given `name`.
     *
     * @param resource configuration resource name.
     * @return a reader attached to the resource.
     */
    fun getResourceAsReader(resource: String, vararg resourcePrefixes: String): Reader? {
        return getResourceAsStream(resource, *resourcePrefixes)?.let { InputStreamReader(it) }
    }

    /**
     * Check if a resource exists
     */
    fun exists(name: String) = getURLOrNull(name) != null

    /**
     * Finds a resource with a given name.
     *
     * Find resources first by each registered class loader and then by the default class loader.
     *
     * @param  name name of the desired resource
     * @return URL object for reading the resource; null if the resource could not be found
     * @see Class.getResource
     * @see ClassLoader.getResource
     */
    fun getURLOrNull(name: String): URL? {
        var url: URL? = null
        // User provided class loader first
        val it: Iterator<ClassFactory> = userClassFactories.iterator()
        while (url == null && it.hasNext()) {
            url = it.next().javaClass.getResource(name)
        }

        // classLoader.getResource: URL object for reading the resource; null if the resource could not be found
        return url ?: classLoader.getResource(name)
    }

    /**
     * Finds a resource with a given name.
     *
     * @param name resource name.
     * @return the url for the named resource.
     */
    @Throws(FileNotFoundException::class)
    fun getURL(name: String): URL {
        return getURLOrNull(name) ?: throw FileNotFoundException("Cannot be resolved to URL | $name");
    }

    /**
     * Finds a resource with a given name.
     *
     * @param name resource name.
     * @param preferredClassLoader preferred class loader, this class loader is used first, fallback to other
     *      class loaders if the resource not found by preferred class loader.
     * @return URL object for reading the resource; null if the resource could not be found
     * @see Class.getResource
     * @see ClassLoader.getResource
     */
    fun <T> getURLOrNull(name: String, preferredClassLoader: Class<T>): URL? {
        return preferredClassLoader.getResource(name) ?: getURLOrNull(name)
    }

    /**
     * Finds a resource with a given name.
     *
     * @param name resource name.
     * @param preferredClassLoader preferred class loader, this class loader is used first, fallback to other
     *      class loaders if the resource not found by preferred class loader.
     * @return the url for the named resource.
     * @see Class.getResource
     * @see ClassLoader.getResource
     */
    @Throws(FileNotFoundException::class)
    fun <T> getURL(name: String, preferredClassLoader: Class<T>): URL {
        return getURLOrNull(name, preferredClassLoader)
            ?: throw FileNotFoundException("Cannot be resolved to URL | $name");
    }

    /**
     * Finds a resource with a given name.
     *
     * @param resource resource name.
     * @return the path for the named resource.
     * @see Class.getResource
     * @see ClassLoader.getResource
     */
    @Throws(FileNotFoundException::class)
    fun getPath(resource: String): Path {
        return getURL(resource).toURI().toPath()
    }

    /**
     * Finds a resource with a given name.
     *
     * @param resource resource name.
     * @return the path for the named resource.
     * @see Class.getResource
     * @see ClassLoader.getResource
     */
    fun getPathOrNull(resource: String): Path? {
        return getURLOrNull(resource)?.toURI()?.toPath()
    }

    @Throws(FileNotFoundException::class)
    fun getMultiSourceReader(stringResource: String?, resource: String): Reader? {
        return getMultiSourceReader(stringResource, resource, "")
    }

    @Throws(FileNotFoundException::class)
    fun getMultiSourceReader(stringResource: String?, namedResource: String, resourcePrefix: String): Reader? {
        var reader: Reader? = null
        if (!stringResource.isNullOrBlank()) {
            reader = StringReader(stringResource)
        } else {
            if (Files.exists(Paths.get(namedResource))) {
                reader = FileReader(namedResource)
            } else { // Read specified location
                if (!namedResource.startsWith("/") && resourcePrefix.isNotBlank()) {
                    reader = getResourceAsReader("$resourcePrefix/$namedResource")
                }
                // Search in classpath
                if (reader == null) {
                    reader = getResourceAsReader(namedResource)
                }
            }
        }
        return reader
    }

    /**
     * The utility methods will try to use the provided class factories to
     * convert binary name of class to Class object. Used by H2 OSGi Activator
     * in order to provide a class from another bundle ClassLoader.
     */
    interface ClassFactory {
        /**
         * Check whether the factory can return the named class.
         *
         * @param name the binary name of the class
         * @return true if this factory can return a valid class for the provided class name
         */
        fun match(name: String): Boolean

        /**
         * Load the class.
         *
         * @param name the binary name of the class
         * @return the class object
         * @throws ClassNotFoundException If the class is not handle by this factory
         */
        @Throws(ClassNotFoundException::class)
        fun loadClass(name: String): Class<*>?
    }
}

object B4ProjectUtils {
    private val logger = LoggerFactory.getLogger(B4ProjectUtils::class.java)

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
