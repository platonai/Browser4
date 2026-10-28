package ai.platon.pulsar.agentic.agents

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * The task's field specification (`asin_field_description.xlsx`) is the task's INPUT
 * contract: 75 English field names in row 1 and their Chinese descriptions in row 2.
 *
 * Origin (2026-10-28 supervision run): the fixture was missing from the 4.15.x branch;
 * the agent spent ten minutes hunting it, then compared its CSV against a 121-column
 * query output because it could not read the field list. A garbled `sharedStrings.xml`
 * (U+FFFD) makes the descriptions worthless and a wrong header count makes "20 rows ×
 * 75 columns" unverifiable.
 *
 * A fixture test, not a runtime dependency: it locks the contract the data-gate checks.
 */
@DisplayName("asin field specification fixture")
class AsinFieldSpecFixtureTest {

    private val fixture: Path = Path.of(System.getProperty("user.dir"))
        .parent
        .resolve("browser4-tests/pulsar-tests-common/src/test/resources/asin_field_description.xlsx")

    private fun readEntry(zip: ZipFile, name: String): String =
        zip.getInputStream(zip.getEntry(name)).use { it.readBytes().toString(StandardCharsets.UTF_8) }

    @Test
    @DisplayName("the fixture exists with its 75-field header and no mangled text")
    fun fixtureIsIntact() {
        assumeTrue(Files.isRegularFile(fixture), "fixture not present on this branch")

        ZipFile(fixture.toFile()).use { zip ->
            val shared = readEntry(zip, "xl/sharedStrings.xml")
            assertFalse(
                shared.contains('\uFFFD'),
                "sharedStrings.xml contains U+FFFD — the Chinese descriptions are mangled",
            )
            assertTrue(shared.contains("品类"), "the row-2 Chinese descriptions must survive")

            val sheet = readEntry(zip, "xl/worksheets/sheet1.xml")
            val header = headerRow(sheet, shared)
            assertEquals(75, header.size, "the specification has 75 fields: $header")

            // Spot-check the names the CSV contract depends on: the deliverable header
            // must use these, not a query's own column names (e.g. `product_overview`).
            listOf("asin", "keywords", "category", "price", "overviewbullets", "featurebullets")
                .forEach { field ->
                    assertTrue(header.contains(field), "missing field '$field' in $header")
                }
            assertEquals(header.size, header.toSet().size, "field names must be unique")
        }
    }

    /** Row 1 of the first sheet, resolving `t="s"` cells through [shared]. */
    private fun headerRow(sheet: String, shared: String): List<String> {
        val strings = Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(shared)
            .map { si ->
                Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                    .find(si.groupValues[1])?.groupValues?.get(1) ?: ""
            }
            .toList()
        val row = Regex("<row r=\"1\".*?</row>", RegexOption.DOT_MATCHES_ALL).find(sheet)?.value
            ?: error("sheet has no row 1")
        return Regex("<c ([^>]*)>(?:<v>([^<]*)</v>)?</c>").findAll(row).map { cell ->
            val attrs = cell.groupValues[1]
            val value = cell.groupValues[2]
            if (attrs.contains("t=\"s\"") && value.isNotEmpty()) strings[value.toInt()] else value
        }.toList()
    }
}
