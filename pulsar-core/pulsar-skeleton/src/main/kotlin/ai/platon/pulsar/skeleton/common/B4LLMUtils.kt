package ai.platon.pulsar.skeleton.common

import ai.platon.pulsar.common.ResourceLoader
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

        val resource = "$resource.txt"
        return ResourceLoader.readString("${B4ProjectUtils.CODE_MIRROR_DIR}/$resource")
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
