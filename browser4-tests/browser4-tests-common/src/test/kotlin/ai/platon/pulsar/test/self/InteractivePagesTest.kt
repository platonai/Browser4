package ai.platon.pulsar.test.self

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import org.junit.jupiter.api.DisplayName

data class InteractiveIndexEntry(
    val file: String,
    val id: String,
    val testId: String,
    val type: String
)

@Tag("TestInfraCheck")
class InteractivePagesTest {

    private val mapper = jacksonObjectMapper()

    private fun readResource(path: String): String {
        val url = javaClass.classLoader.getResource("static/generated/$path")
            ?: fail("Resource not found: $path")
        return url.openStream().use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }

    private fun loadIndex(): List<InteractiveIndexEntry> {
        val json = readResource("interactive-elements-index.json")
        return mapper.readValue(json)
    }

    @Test
        @DisplayName("all indexed elements exist with matching id and data-testid")
    fun allIndexedElementsExistWithMatchingIdAndDataTestid() {
        val index = loadIndex()
        val byFile = index.groupBy { it.file }
        byFile.forEach { (file, entries) ->
            val html = readResource(file)
            entries.forEach { e ->
                assertTrue(html.contains("id=\"${e.id}\""), "Missing id='${e.id}' in $file")
                assertTrue(html.contains("data-testid=\"${e.testId}\""), "Missing data-testid='${e.testId}' in $file")
            }
        }
    }

    @Test
        @DisplayName("ids are unique per file and data-testids mirror ids for structural elements")
    fun idsAreUniquePerFileAndDataTestidsMirrorIdsForStructuralElements() {
        val index = loadIndex()
        val byFile = index.groupBy { it.file }
        // Match id attribute preceded by whitespace to avoid data-testid.
        val idPattern = Pattern.compile("""\sid="([^"]+)"""")
        val testIdPattern = Pattern.compile("""\bdata-testid="([^"]+)"""")

        byFile.forEach { (file, _) ->
            val html = readResource(file)
            val idMatcher = idPattern.matcher(html)
            val ids = mutableListOf<String>()
            while (idMatcher.find()) ids += idMatcher.group(1)
            val duplicates = ids.groupBy { it }.filter { it.value.size > 1 }.keys
            assertTrue(duplicates.isEmpty(), "Duplicate ids in $file: $duplicates")

            val testIdMatcher = testIdPattern.matcher(html)
            val testIds = mutableListOf<String>()
            while (testIdMatcher.find()) testIds += testIdMatcher.group(1)
            val duplicateTestIds = testIds.groupBy { it }.filter { it.value.size > 1 }.keys
            assertTrue(duplicateTestIds.isEmpty(), "Duplicate data-testids in $file: $duplicateTestIds")
        }
    }

    @Test
        @DisplayName("index covers all major structural sections (header, section) that have explicit ids")
    fun indexCoversAllMajorStructuralSectionsHeaderSectionThatHaveExplicitIds() {
        val index = loadIndex()
        val indexKey = index.map { it.file to it.id }.toSet()
        val structuralTagPattern = Pattern.compile("""<(header|section)\s+[^>]*id="([^"]+)"""")
        val files = index.map { it.file }.toSet()
        files.forEach { file ->
            val html = readResource(file)
            val m = structuralTagPattern.matcher(html)
            val missing = mutableListOf<String>()
            while (m.find()) {
                val id = m.group(2)
                if ((file to id) !in indexKey) missing += id
            }
            assertTrue(missing.isEmpty(), "File $file has structural ids not in index: $missing")
        }
    }
}
