package ai.platon.browser4.common

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal class TextExtractor(
    val text: String,
    val filter: (String) -> Boolean = { true }
) : B4UrlExtractor() {
    fun extract(): Set<String> {
        val urls = mutableSetOf<String>()
        text.split("\n").forEach { line -> extractTo(line, urls, filter) }
        return urls
    }
}

internal class ResourceExtractor(
    val resource: String,
    val filter: (String) -> Boolean = { true }
) : B4UrlExtractor() {
    fun extract(): Set<String> {
        val urls = mutableSetOf<String>()
        B4ResourceLoader.readAllLines(resource, filter).forEach { extractTo(it, urls, filter) }
        return urls
    }
}

internal class FileExtractor(
    val path: Path,
    val filter: (String) -> Boolean = { true }
) : B4UrlExtractor() {
    fun extract(): Set<String> {
        if (!Files.exists(path)) {
            return setOf()
        }

        val urls = mutableSetOf<String>()
        Files.readAllLines(path).filter(filter).forEach { extractTo(it, urls, filter) }
        return urls
    }
}

internal class DirectoryExtractor(
    val baseDir: Path,
    val filter: (String) -> Boolean = { true }
) : B4UrlExtractor() {
    fun extract(): Set<String> {
        if (!Files.exists(baseDir)) {
            return setOf()
        }

        val urls = mutableSetOf<String>()
        Files.list(baseDir).filter { Files.isRegularFile(it) }.forEach { path ->
            Files.newBufferedReader(path).forEachLine {
                extractTo(it, urls, filter)
            }
        }
        return urls
    }
}

/**
 * TODO: use ai.platon.pulsar.common.LinkExtractors instead of this class, and remove this class.
 * */
@Deprecated("use ai.platon.pulsar.common.LinkExtractors instead of this class, and remove this class.",
    replaceWith = ReplaceWith("ai.platon.pulsar.common.LinkExtractors"))
object B4LinkExtractors {
    @JvmStatic
    fun fromText(text: String) = TextExtractor(text).extract()

    @JvmStatic
    fun fromText(text: String, filter: (String) -> Boolean) = TextExtractor(text, filter).extract()

    @JvmStatic
    fun hasLink(text: String) = TextExtractor(text).extract().isNotEmpty()

    @JvmStatic
    fun fromResource(resource: String) = ResourceExtractor(resource).extract()

    @JvmStatic
    fun fromResource(resource: String, filter: (String) -> Boolean) = ResourceExtractor(resource, filter).extract()

    @JvmStatic
    fun fromFile(path: Path) = FileExtractor(path).extract()

    @JvmStatic
    fun fromFile(path: Path, filter: (String) -> Boolean) = FileExtractor(path, filter).extract()

    @JvmStatic
    fun fromFile(path: String) = FileExtractor(Paths.get(path)).extract()

    @JvmStatic
    fun fromFile(path: String, filter: (String) -> Boolean) = FileExtractor(Paths.get(path), filter).extract()

    @JvmStatic
    fun fromDirectory(baseDir: Path) = DirectoryExtractor(baseDir).extract()

    @JvmStatic
    fun fromDirectory(baseDir: Path, filter: (String) -> Boolean) = DirectoryExtractor(baseDir, filter).extract()

    @JvmStatic
    fun fromDirectory(baseDir: String) = DirectoryExtractor(Paths.get(baseDir)).extract()

    @JvmStatic
    fun fromDirectory(baseDir: String, filter: (String) -> Boolean) = DirectoryExtractor(Paths.get(baseDir), filter).extract()
}
