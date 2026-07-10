package ai.platon.browser4.common

import org.slf4j.LoggerFactory
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.notExists

object B4LLMUtils {

    private val logger = LoggerFactory.getLogger(B4LLMUtils::class.java)

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
}
