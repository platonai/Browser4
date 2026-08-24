package ai.platon.pulsar.common

import org.slf4j.LoggerFactory
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.notExists

object B4LLMUtils {

    private val logger = LoggerFactory.getLogger(B4LLMUtils::class.java)

    /**
     * Coordinates of the base library (pulsar) that hosts core interfaces such as WebDriver.
     */
    private const val BASE_LIBRARY_GROUP_ID = "ai.platon.pulsar"
    private const val BASE_LIBRARY_ARTIFACT_ID = "pulsar-browser"
    private const val MAVEN_CENTRAL_URL = "https://repo1.maven.org/maven2"

    @Throws(IOException::class)
    fun copySourceFileAsResource(moduleName: String, filename: String) {
        if (B4ProjectUtils.isInJar()) {
            return
        }

        if (B4ProjectUtils.findProjectRootDir() == null) {
            // we are not in a source code project
            return
        }

        try {
            val file = B4ProjectUtils.findFiles(moduleName, filename).firstOrNull() ?: run {
                logger.warn("Source file '{}' not found in module '{}'", filename, moduleName)
                return
            }
            B4ProjectUtils.copySourceFileAsCodeResource(file)
        } catch (e: IOException) {
            logger.warn("Failed to copy source file '{}' in module '{}': {}", filename, moduleName, e.message)
        }
    }

    /**
     * Reads the content of a source file as a resource string.
     * */
    fun readSourceFileFromResource(moduleName: String, resource: String): String {
        try {
            copySourceFileAsResource(moduleName, resource)
        } catch (e: Exception) {
            logger.warn("Failed to copy source file '{}' for resource reading: {}", resource, e.message)
        }

        val mirroredResource = "$resource.txt"
        val classpathResource = "${B4ProjectUtils.CODE_MIRROR_DIR}/$mirroredResource"
        val content = try {
            B4ResourceLoader.readString(classpathResource)
        } catch (e: Exception) {
            logger.warn("Failed to read classpath resource '{}': {}", classpathResource, e.message)
            ""
        }
        if (content.isNotBlank()) {
            return content
        }

        val projectRootDir = B4ProjectUtils.findProjectRootDir() ?: return content
        val mirroredPath = projectRootDir.resolve(B4ProjectUtils.CODE_RESOURCE_DIR).resolve(mirroredResource)
        return if (Files.exists(mirroredPath)) {
            try {
                Files.readString(mirroredPath)
            } catch (e: IOException) {
                logger.warn("Failed to read mirrored resource '{}': {}", mirroredPath, e.message)
                content
            }
        } else {
            content
        }
    }

    fun writeAsResource(fileName: String, content: String): Path? {
        val baseDir = B4ProjectUtils.findFiles(B4ProjectUtils.CODE_MIRROR_DIR).firstOrNull() ?: return null
        if (baseDir.notExists()) {
            return null
        }

        val path = baseDir.resolve(fileName)
        Files.writeString(path, content)
        return path
    }

    /**
     * Reads a source file from the sources jar of the base library (`ai.platon.pulsar:pulsar-browser`).
     *
     * Core interfaces such as `WebDriver.kt` moved to the base library, so their sources are no
     * longer part of this repository's source tree and cannot be read by [readSourceFileFromResource].
     * The version of the base library is resolved from the classpath (via [clazz], which must be
     * loaded from the base library jar), the matching `-sources.jar` is downloaded from Maven
     * Central once and cached under `~/.cache/browser4/` (overridable via the `browser4.cache.dir`
     * system property), and [fileName] is extracted from the jar.
     *
     * When the extraction succeeds and we are running from a project checkout, the content is also
     * mirrored into the code-mirror resource directory so later offline runs can read it through
     * the regular resource path.
     *
     * @param fileName the source file name to extract, e.g. `WebDriver.kt`.
     * @param clazz a class loaded from the base library jar (used to resolve the library version).
     * @return the source content, or an empty string when the version cannot be resolved, the
     *         sources jar cannot be downloaded, or [fileName] is not present in the jar.
     */
    fun readSourceFileFromBaseLibrary(fileName: String, clazz: Class<*>): String {
        val version = B4ProjectUtils.resolveJarVersion(BASE_LIBRARY_ARTIFACT_ID, clazz)
        if (version.isNullOrBlank()) {
            logger.warn(
                "Could not resolve base library ({}) version from classpath, cannot read {}",
                BASE_LIBRARY_ARTIFACT_ID, fileName
            )
            return ""
        }

        val jarPath = baseLibrarySourcesJarPath(version)
        val downloaded = if (jarPath.notExists()) {
            downloadBaseLibrarySourcesJar(version, jarPath)
        } else {
            true
        }
        if (!downloaded) {
            return ""
        }

        val content = extractSourceFileFromJar(jarPath, fileName)
        if (content == null) {
            logger.warn("Could not find '{}' in base library sources jar '{}'", fileName, jarPath)
            return ""
        }
        mirrorSourceFileAsCodeResource(fileName, content)
        return content
    }

    /**
     * Extracts the content of a source file from a (sources) jar.
     *
     * The entry is located by simple file name, e.g. `WebDriver.kt` matches
     * `ai/platon/pulsar/api/WebDriver.kt`. When several entries match, the deepest one wins.
     *
     * @return the entry content decoded as UTF-8, or null when the jar does not exist or the
     *         file cannot be found.
     */
    internal fun extractSourceFileFromJar(jarPath: Path, fileName: String): String? {
        if (jarPath.notExists()) {
            return null
        }
        return try {
            JarFile(jarPath.toFile()).use { jar ->
                val entry = jar.entries().asSequence()
                    .filter { !it.isDirectory && (it.name == fileName || it.name.endsWith("/$fileName")) }
                    .maxByOrNull { it.name.length }
                    ?: return null
                jar.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            }
        } catch (e: IOException) {
            logger.warn("Failed to read '{}' from sources jar '{}': {}", fileName, jarPath, e.message)
            null
        }
    }

    private fun baseLibrarySourcesJarPath(version: String): Path {
        val cacheDir = System.getProperty("browser4.cache.dir")
            ?.takeIf { it.isNotBlank() }
            ?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home") ?: ".", ".cache", "browser4")
        return cacheDir.resolve("$BASE_LIBRARY_ARTIFACT_ID-$version-sources.jar")
    }

    private fun downloadBaseLibrarySourcesJar(version: String, dest: Path): Boolean {
        val groupPath = BASE_LIBRARY_GROUP_ID.replace('.', '/')
        val url = URL(
            "$MAVEN_CENTRAL_URL/$groupPath/$BASE_LIBRARY_ARTIFACT_ID/$version/" +
                "$BASE_LIBRARY_ARTIFACT_ID-$version-sources.jar"
        )
        try {
            Files.createDirectories(dest.parent)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Browser4")
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                logger.warn("Failed to download base library sources jar: HTTP {} | {}", responseCode, url)
                return false
            }
            connection.inputStream.use { input ->
                Files.newOutputStream(dest).use { output -> input.copyTo(output) }
            }
            logger.info("Downloaded base library sources jar: {}", url)
            return true
        } catch (e: IOException) {
            logger.warn("Failed to download base library sources jar {}: {}", url, e.message)
            dest.deleteIfExists()
            return false
        }
    }

    /**
     * Mirrors a base library source file into the code-mirror resource directory (dev only),
     * so subsequent offline runs can read it via [readSourceFileFromResource].
     */
    private fun mirrorSourceFileAsCodeResource(fileName: String, content: String) {
        if (B4ProjectUtils.isInJar()) {
            return
        }
        val rootDir = B4ProjectUtils.findProjectRootDir() ?: return
        val target = rootDir.resolve(B4ProjectUtils.CODE_RESOURCE_DIR).resolve("$fileName.txt")
        try {
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
            logger.info("Mirrored base library source '{}' to '{}'", fileName, target)
        } catch (e: IOException) {
            logger.warn("Failed to mirror base library source '{}' to '{}': {}", fileName, target, e.message)
        }
    }
}
