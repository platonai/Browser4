package ai.platon.pulsar.skeleton.workflow.format

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("FormatOptionSchema")
class FormatOptionTest {

    private fun codesOf(raw: Any?): List<FormatIssueCode> =
        FormatOptionSchema.parse(raw).issues.map { it.code }

    // ---- normalization ------------------------------------------------------

    @Test
    @DisplayName("a missing formats parameter means the default markdown only")
    fun missingFormatsMeansDefault() {
        val result = FormatOptionSchema.parse(null)
        assertTrue(result.ok, result.issues.toString())
        assertEquals(listOf("markdown"), result.formats.map { it.type })
    }

    @Test
    @DisplayName("an empty formats array means the default markdown only")
    fun emptyFormatsMeansDefault() {
        val result = FormatOptionSchema.parse(emptyList<String>())
        assertTrue(result.ok, result.issues.toString())
        assertEquals(listOf("markdown"), result.formats.map { it.type })
    }

    @Test
    @DisplayName("a bare string and a single object are both accepted")
    fun singleEntryForms() {
        assertEquals(listOf("links"), FormatOptionSchema.parse("links").formats.map { it.type })
        assertEquals(
            listOf("screenshot"),
            FormatOptionSchema.parse(mapOf("type" to "screenshot")).formats.map { it.type },
        )
    }

    @Test
    @DisplayName("a mixed string/object array is accepted")
    fun mixedArray() {
        val result = FormatOptionSchema.parse(
            listOf("markdown", mapOf("type" to "screenshot", "fullPage" to true), "links")
        )
        assertTrue(result.ok, result.issues.toString())
        assertEquals(listOf("markdown", "screenshot", "links"), result.formats.map { it.type })
        assertEquals(true, result.formats[1].fullPage)
    }

    @Test
    @DisplayName("option keys are accepted in camelCase, snake_case and kebab-case")
    fun optionKeySpellings() {
        val result = FormatOptionSchema.parse(
            listOf(
                mapOf("type" to "json", "check_prompt_injection" to true, "prompt" to "p"),
                mapOf("type" to "screenshot", "full-page" to true),
            )
        )
        assertTrue(result.ok, result.issues.toString())
        assertEquals(true, result.formats[0].checkPromptInjection)
        assertEquals(true, result.formats[1].fullPage)
    }

    @Test
    @DisplayName("format ids are canonicalized case-insensitively")
    fun caseInsensitiveTypes() {
        val result = FormatOptionSchema.parse(
            listOf(
                "RAWHTML",
                mapOf("type" to "deterministicjson", "sql" to "select 1"),
                "ChangeTracking",
                "markdown",
            )
        )
        assertTrue(result.ok, result.issues.toString())
        assertEquals(
            listOf("rawHtml", "deterministicJson", "changeTracking", "markdown"),
            result.formats.map { it.type },
        )
    }

    @Test
    @DisplayName("structured options survive normalization")
    fun structuredOptions() {
        val result = FormatOptionSchema.parse(
            listOf(
                mapOf(
                    "type" to "screenshot",
                    "quality" to 80,
                    "viewport" to mapOf("width" to 1280, "height" to 720),
                ),
                mapOf(
                    "type" to "attributes",
                    "selectors" to listOf(mapOf("selector" to ".price", "attribute" to "data-amount")),
                ),
                mapOf("type" to "json", "schema" to mapOf("type" to "object")),
            )
        )
        assertTrue(result.ok, result.issues.toString())
        assertEquals(80, result.formats[0].quality)
        assertEquals(FormatViewport(1280, 720), result.formats[0].viewport)
        assertEquals(listOf(AttributeSelector(".price", "data-amount")), result.formats[1].selectors)
        assertEquals(mapOf("type" to "object"), result.formats[2].schema)
    }

    @Test
    @DisplayName("already-parsed PageFormat values pass through")
    fun parsedValuesPassThrough() {
        val format = PageFormat("screenshot", fullPage = true, quality = 50)
        val result = FormatOptionSchema.parse(listOf(format))
        assertTrue(result.ok, result.issues.toString())
        assertEquals(listOf(format), result.formats)
    }

    @Test
    @DisplayName("v1 aliases normalize to their canonical format")
    fun v1Aliases() {
        val fullPage = FormatOptionSchema.parse("screenshot@fullPage").formats.single()
        assertEquals("screenshot", fullPage.type)
        assertTrue(fullPage.fullPage)

        val extract = FormatOptionSchema.parse(listOf(mapOf("type" to "extract", "prompt" to "p"))).formats.single()
        assertEquals("json", extract.type)
    }

    @Test
    @DisplayName("an entry that is neither a string nor an object is rejected")
    fun invalidEntry() {
        assertEquals(listOf(FormatIssueCode.INVALID_FORMAT_ENTRY), codesOf(listOf(42)))
        assertEquals(listOf(FormatIssueCode.INVALID_FORMAT_ENTRY), codesOf(mapOf("fullPage" to true)))
        assertTrue(FormatOptionSchema.normalize(listOf(42)).isEmpty())
    }

    // ---- combination rules --------------------------------------------------

    @Test
    @DisplayName("an unknown format is rejected with its original spelling")
    fun unknownFormat() {
        val result = FormatOptionSchema.parse(listOf("markdwon"))
        assertEquals(listOf(FormatIssueCode.UNKNOWN_FORMAT), result.issues.map { it.code })
        assertEquals("markdwon", result.issues.single().format)
        assertTrue(result.issues.single().message.contains("Unknown format"))
    }

    @Test
    @DisplayName("only one screenshot format may be requested")
    fun duplicateScreenshot() {
        assertEquals(listOf(FormatIssueCode.DUPLICATE_SCREENSHOT), codesOf(listOf("screenshot", "screenshot")))
        assertEquals(
            listOf(FormatIssueCode.DUPLICATE_SCREENSHOT),
            codesOf(listOf("screenshot", mapOf("type" to "screenshot", "fullPage" to true))),
        )
    }

    @Test
    @DisplayName("changeTracking requires markdown")
    fun changeTrackingRequiresMarkdown() {
        assertEquals(
            listOf(FormatIssueCode.CHANGE_TRACKING_REQUIRES_MARKDOWN),
            codesOf(listOf("changeTracking")),
        )
        assertTrue(FormatOptionSchema.parse(listOf("markdown", "changeTracking")).ok)
    }

    @Test
    @DisplayName("json and deterministicJson are mutually exclusive")
    fun jsonConflict() {
        val codes = codesOf(
            listOf(
                mapOf("type" to "json", "prompt" to "p"),
                mapOf("type" to "deterministicJson", "sql" to "select 1"),
            )
        )
        assertTrue(codes.contains(FormatIssueCode.JSON_CONFLICT), codes.toString())
    }

    @Test
    @DisplayName("rawBase64 must be the only format")
    fun rawBase64Exclusive() {
        assertTrue(FormatOptionSchema.parse(listOf("rawBase64")).ok)
        assertEquals(
            listOf(FormatIssueCode.RAW_BASE64_EXCLUSIVE),
            codesOf(listOf("rawBase64", "links")),
        )
    }

    // ---- per-format rules ---------------------------------------------------

    @Test
    @DisplayName("json requires a prompt or a schema")
    fun jsonRequiresPromptOrSchema() {
        assertEquals(listOf(FormatIssueCode.JSON_REQUIRES_PROMPT_OR_SCHEMA), codesOf(listOf("json")))
        assertTrue(FormatOptionSchema.parse(listOf(mapOf("type" to "json", "prompt" to "p"))).ok)
        assertTrue(
            FormatOptionSchema.parse(listOf(mapOf("type" to "json", "schema" to mapOf("type" to "object")))).ok
        )
    }

    @Test
    @DisplayName("attributes requires at least one complete selector")
    fun attributesSelectors() {
        assertEquals(listOf(FormatIssueCode.ATTRIBUTES_SELECTORS_REQUIRED), codesOf(listOf(mapOf("type" to "attributes"))))
        assertEquals(
            listOf(FormatIssueCode.ATTRIBUTE_SELECTOR_INCOMPLETE),
            codesOf(
                listOf(
                    mapOf(
                        "type" to "attributes",
                        "selectors" to listOf(mapOf("selector" to ".price", "attribute" to "")),
                    )
                )
            ),
        )
        assertTrue(
            FormatOptionSchema.parse(
                listOf(
                    mapOf(
                        "type" to "attributes",
                        "selectors" to listOf(mapOf("selector" to ".price", "attribute" to "data-amount")),
                    )
                )
            ).ok
        )
    }

    @Test
    @DisplayName("deterministicJson requires an X-SQL statement")
    fun deterministicJsonRequiresSql() {
        assertEquals(
            listOf(FormatIssueCode.DETERMINISTIC_JSON_REQUIRES_SQL),
            codesOf(listOf("deterministicJson")),
        )
        assertEquals(
            listOf(FormatIssueCode.DETERMINISTIC_JSON_REQUIRES_SQL),
            codesOf(listOf(mapOf("type" to "deterministicJson", "schema" to mapOf("type" to "object")))),
        )
        val ok = FormatOptionSchema.parse(listOf(mapOf("type" to "deterministicJson", "sql" to "select 1 as a")))
        assertTrue(ok.ok, ok.issues.toString())
        assertEquals("select 1 as a", ok.formats.single().sql)
    }

    @Test
    @DisplayName("question and highlights require their text argument")
    fun questionAndHighlights() {
        assertEquals(listOf(FormatIssueCode.QUESTION_REQUIRED), codesOf(listOf(mapOf("type" to "question"))))
        assertEquals(listOf(FormatIssueCode.HIGHLIGHTS_QUERY_REQUIRED), codesOf(listOf(mapOf("type" to "highlights"))))
        assertTrue(FormatOptionSchema.parse(listOf(mapOf("type" to "question", "question" to "Who?"))).ok)
        assertTrue(FormatOptionSchema.parse(listOf(mapOf("type" to "highlights", "query" to "price"))).ok)
    }

    @Test
    @DisplayName("deprecated query requires a prompt and a known mode")
    fun queryFormat() {
        assertEquals(listOf(FormatIssueCode.QUERY_PROMPT_REQUIRED), codesOf(listOf(mapOf("type" to "query"))))
        assertEquals(
            listOf(FormatIssueCode.INVALID_MODE),
            codesOf(listOf(mapOf("type" to "query", "prompt" to "p", "mode" to "bogus"))),
        )
        assertTrue(
            FormatOptionSchema.parse(listOf(mapOf("type" to "query", "prompt" to "p", "mode" to "directQuote"))).ok
        )
    }

    @Test
    @DisplayName("enum-valued options reject unknown values")
    fun enumOptions() {
        assertEquals(
            listOf(FormatIssueCode.INVALID_MODE),
            codesOf(listOf(mapOf("type" to "branding", "mode" to "bogus"))),
        )
        assertTrue(FormatOptionSchema.parse(listOf(mapOf("type" to "branding", "mode" to "fast"))).ok)
        assertEquals(
            listOf(FormatIssueCode.INVALID_MODE),
            codesOf(listOf("markdown", mapOf("type" to "changeTracking", "modes" to listOf("bogus")))),
        )
        assertTrue(
            FormatOptionSchema.parse(
                listOf("markdown", mapOf("type" to "changeTracking", "modes" to listOf("json", "git-diff")))
            ).ok
        )
    }

    @Test
    @DisplayName("screenshot quality and viewport are range checked")
    fun screenshotRanges() {
        assertEquals(listOf(FormatIssueCode.INVALID_QUALITY), codesOf(listOf(mapOf("type" to "screenshot", "quality" to 0))))
        assertEquals(
            listOf(FormatIssueCode.INVALID_QUALITY),
            codesOf(listOf(mapOf("type" to "screenshot", "quality" to 101))),
        )
        assertEquals(
            listOf(FormatIssueCode.INVALID_VIEWPORT),
            codesOf(
                listOf(
                    mapOf(
                        "type" to "screenshot",
                        "viewport" to mapOf("width" to FormatOptionSchema.MAX_VIEWPORT_WIDTH + 1, "height" to 600),
                    )
                )
            ),
        )
        assertTrue(
            FormatOptionSchema.parse(listOf(mapOf("type" to "screenshot", "quality" to 100))).ok
        )
    }

    // ---- result helpers -----------------------------------------------------

    @Test
    @DisplayName("requireValid throws one message carrying every issue")
    fun requireValidThrows() {
        val result = FormatOptionSchema.parse(listOf("markdwon", mapOf("type" to "question")))
        assertFalse(result.ok)
        val error = assertThrows(IllegalArgumentException::class.java) { result.requireValid() }
        assertTrue(error.message!!.contains("Unknown format"), error.message)
        assertTrue(error.message!!.contains("question"), error.message)
    }

    @Test
    @DisplayName("requireValid returns the parsed formats when nothing is wrong")
    fun requireValidReturns() {
        val formats = FormatOptionSchema.parse(listOf("markdown", "links")).requireValid()
        assertEquals(listOf("markdown", "links"), formats.map { it.type })
    }

    @Test
    @DisplayName("lookup extensions resolve aliases and report requested types")
    fun lookupExtensions() {
        val formats = FormatOptionSchema.parse(listOf("markdown", "screenshot@fullPage")).formats
        assertTrue(formats.hasFormat("screenshot"))
        assertTrue(formats.hasFormat("SCREENSHOT"))
        assertNull(formats.formatOf("links"))
        assertTrue(formats.formatOf("screenshot")!!.fullPage)
        assertEquals(setOf("markdown", "screenshot"), formats.formatTypes())
    }

    // ---- format table -------------------------------------------------------

    @Test
    @DisplayName("the format table knows its groups and canonical spellings")
    fun formatTable() {
        assertTrue(PageFormats.isKnown("rawhtml"))
        assertTrue(PageFormats.isKnown("screenshot@fullPage"))
        assertFalse(PageFormats.isKnown("markdwon"))
        assertTrue(PageFormats.isKnown("branding"))
        assertTrue(PageFormats.isContributed("branding"))
        assertFalse(PageFormats.isContributed("markdown"))
        assertTrue(PageFormats.isDeprecated("query"))
        assertTrue(PageFormats.CORE.containsAll(listOf("markdown", "rawHtml", "changeTracking", "readability", "pdf")))
        assertEquals(PageFormats.ALL.size, PageFormats.CORE.size + PageFormats.CONTRIBUTED.size + PageFormats.DEPRECATED.size)
    }

    @Test
    @DisplayName("formats map onto the document fields they populate")
    fun documentFieldMapping() {
        assertEquals(setOf("json"), PageFormats.documentFieldsOf("json"))
        assertEquals(setOf("json"), PageFormats.documentFieldsOf("deterministicJson"))
        assertEquals(setOf("answer"), PageFormats.documentFieldsOf("question"))
        assertEquals(setOf("answer", "highlights"), PageFormats.documentFieldsOf("query"))
        assertEquals(setOf("video", "videos"), PageFormats.documentFieldsOf("video"))
        // A binary format writes its file either way, so the path is always there; the
        // bytes ride alongside it only when the format asked for them. Same rule for
        // both binary formats.
        assertEquals(setOf("screenshot", "screenshotBase64"), PageFormats.documentFieldsOf("screenshot"))
        assertEquals(setOf("pdf", "pdfBase64"), PageFormats.documentFieldsOf("pdf"))
        assertEquals(setOf("branding"), PageFormats.documentFieldsOf("branding"))
        assertTrue(PageFormats.documentFieldsOf("markdwon").isEmpty())
        assertEquals("json", PageFormats.formatOfField("json"))
        assertEquals("markdown", PageFormats.formatOfField("markdown"))
        assertNull(PageFormats.formatOfField("metadata"))
    }

    @Test
    @DisplayName("contributor ids follow the documented pattern")
    fun contributorIdPattern() {
        assertTrue(PageFormats.isValidId("branding"))
        assertTrue(PageFormats.isValidId("deterministicJson"))
        assertFalse(PageFormats.isValidId("Branding"))
        assertFalse(PageFormats.isValidId("brand-ing"))
        assertFalse(PageFormats.isValidId(""))
    }

    @Test
    @DisplayName("the writer-writable fields are derived from the contributed formats")
    fun contributedFieldsAreDerived() {
        assertEquals(setOf("branding", "product", "menu", "highlights"), PageFormats.contributedFields())
        // Derived, not listed a second time: every contributed format still maps
        // onto a field, and no core format's field is writable by a contributor.
        assertEquals(
            PageFormats.CONTRIBUTED.flatMap { PageFormats.documentFieldsOf(it) }.toSet(),
            PageFormats.contributedFields(),
        )
        // A contributor never competes with a core provider for a field: `answer`
        // belongs to `question` and is not writable, `highlights` is not a core
        // field and is.
        val coreFields = PageFormats.CORE.flatMap { PageFormats.documentFieldsOf(it) }.toSet()
        assertTrue(PageFormats.contributedFields().none { it in coreFields })
        assertFalse("answer" in PageFormats.contributedFields())
    }
}
