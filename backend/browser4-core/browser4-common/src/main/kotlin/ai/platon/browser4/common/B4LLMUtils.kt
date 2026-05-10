package ai.platon.browser4.common

import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.notExists

object B4LLMUtils {

    @Throws(IOException::class)
    fun copySourceFileAsResource(moduleName: String, filename: String) {
        if (B4ProjectUtils.isInJar()) {
            return
        }

        if (B4ProjectUtils.findProjectRootDir() == null) {
            // we are not in a source code project
            return
        }

        val file = B4ProjectUtils.findFiles(moduleName, filename).firstOrNull() ?: throw FileNotFoundException(filename)
        B4ProjectUtils.copySourceFileAsCodeResource(file)
    }

    /**
     * Reads the content of a source file as a resource string.
     * */
    fun readSourceFileFromResource(moduleName: String, resource: String): String {
        copySourceFileAsResource(moduleName, resource)

        val mirroredResource = "$resource.txt"
        val classpathResource = "${B4ProjectUtils.CODE_MIRROR_DIR}/$mirroredResource"
        val content = B4ResourceLoader.readString(classpathResource)
        if (content.isNotBlank()) {
            return content
        }

        val projectRootDir = B4ProjectUtils.findProjectRootDir() ?: return content
        val mirroredPath = projectRootDir.resolve(B4ProjectUtils.CODE_RESOURCE_DIR).resolve(mirroredResource)
        return if (Files.exists(mirroredPath)) {
            Files.readString(mirroredPath)
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
