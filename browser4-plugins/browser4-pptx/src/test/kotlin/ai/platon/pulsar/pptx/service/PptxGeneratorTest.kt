/**
 * Copyright (c) Vincent Zhang, ivincent.zhang@gmail.com, Platon.AI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.pptx.service

import ai.platon.pulsar.pptx.config.PptxConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Tests for [PptxGenerator] — verifies PPTX generation from content blocks.
 *
 * Uses anonymous subclass overrides for the image downloader.
 * No MockK dependency required.
 */
class PptxGeneratorTest {

    @TempDir
    lateinit var tempDir: Path

    private fun createGenerator(
        imageBytes: Map<String, ByteArray> = emptyMap(),
        config: PptxConfig = PptxConfig(),
        downloadResult: PptxImageDownloader.DownloadResult? = null,
    ): PptxGenerator {
        val downloader = object : PptxImageDownloader(config, OkHttpClient()) {
            override suspend fun downloadImages(blocks: List<ContentBlock>): DownloadResult {
                return downloadResult ?: DownloadResult(imageBytes, imageBytes.size, 0)
            }
        }
        return PptxGenerator(config, downloader)
    }

    private fun readSlideShow(path: Path): XMLSlideShow {
        return XMLSlideShow(FileInputStream(path.toFile()))
    }

    /** All text visible in the deck, so a test can prove a block reached a slide. */
    private fun deckText(path: Path): String = readSlideShow(path).use { ppt ->
        ppt.slides.joinToString("\n") { slide ->
            slide.shapes.joinToString("\n") { shape -> (shape as? XSLFTextShape)?.text ?: "" }
        }
    }

    /** The first text shape of every slide — the title shape when the slide has one. */
    private fun slideTitles(path: Path): List<String> = readSlideShow(path).use { ppt ->
        ppt.slides.map { slide ->
            slide.shapes.filterIsInstance<XSLFTextShape>().firstOrNull()?.text ?: ""
        }
    }

    private fun slideCount(path: Path): Int = readSlideShow(path).use { it.slides.size }

    private fun tinyPng(): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out)
        return out.toByteArray()
    }

    @Test
    @DisplayName("generate with empty blocks creates title-only PPTX")
    fun testGenerateEmptyBlocks() = runBlocking {
        val generator = createGenerator()
        val path = generator.generate(
            blocks = emptyList(),
            pageUrl = "https://example.com",
            pageTitle = "Test Page",
            outputDir = tempDir,
        ).path

        assertTrue(path.toFile().exists())
        assertTrue(path.toFile().length() > 0)

        readSlideShow(path).use { ppt ->
            // Should have at least the title slide
            assertTrue(ppt.slides.size >= 1, "Expected at least 1 slide, got ${ppt.slides.size}")
        }
    }

    @Test
    @DisplayName("generate creates title slide with page info")
    fun testGenerateTitleSlide() = runBlocking {
        val generator = createGenerator()
        val path = generator.generate(
            blocks = listOf(
                ContentBlock(type = "title", text = "My Page Title", level = 0)
            ),
            pageUrl = "https://example.com/page",
            pageTitle = "My Page Title",
            outputDir = tempDir,
        ).path

        assertTrue(path.toFile().exists())
        readSlideShow(path).use { ppt ->
            assertTrue(ppt.slides.size >= 1)
            // Verify the file is a valid PPTX (ZIP with XML)
            val firstSlide = ppt.slides[0]
            assertNotNull(firstSlide)
        }
    }

    @Test
    @DisplayName("generate creates section slides for headings")
    fun testGenerateSectionSlides() = runBlocking {
        val generator = createGenerator()
        val blocks = listOf(
            ContentBlock(type = "title", text = "Page", level = 0),
            ContentBlock(type = "heading", text = "Section A", level = 2),
            ContentBlock(type = "paragraph", text = "Content for section A."),
            ContentBlock(type = "heading", text = "Section B", level = 2),
            ContentBlock(type = "paragraph", text = "Content for section B."),
            ContentBlock(type = "heading", text = "Subsection", level = 3),
            ContentBlock(type = "paragraph", text = "More content."),
        )

        val path = generator.generate(blocks, "https://example.com", "Page", tempDir).path

        assertTrue(path.toFile().exists())
        readSlideShow(path).use { ppt ->
            // Title slide + Section A slide + Section B slide = at least 3 slides
            assertTrue(ppt.slides.size >= 3, "Expected at least 3 slides, got ${ppt.slides.size}")
        }
    }

    @Test
    @DisplayName("generate creates slides for content without headings")
    fun testGenerateNoHeadings() = runBlocking {
        val generator = createGenerator()
        val blocks = listOf(
            ContentBlock(type = "title", text = "No Headings Page", level = 0),
            ContentBlock(type = "paragraph", text = "Just a paragraph."),
            ContentBlock(type = "paragraph", text = "Another paragraph."),
        )

        val path = generator.generate(blocks, "https://example.com", "No Headings Page", tempDir).path

        assertTrue(path.toFile().exists())
        readSlideShow(path).use { ppt ->
            assertTrue(ppt.slides.size >= 2, "Expected at least 2 slides (title + content), got ${ppt.slides.size}")
        }
    }

    @Test
    @DisplayName("generate handles large content with slide splitting")
    fun testGenerateSplitsLongSections() = runBlocking {
        val generator = createGenerator(config = PptxConfig(maxContentBlocksPerSlide = 3))
        val blocks = mutableListOf<ContentBlock>(
            ContentBlock(type = "title", text = "Long Page", level = 0),
            ContentBlock(type = "heading", text = "Long Section", level = 2),
        )
        // Add many paragraphs to trigger splitting
        repeat(10) { i ->
            blocks.add(ContentBlock(type = "paragraph", text = "Paragraph number $i."))
        }

        val path = generator.generate(blocks, "https://example.com", "Long Page", tempDir).path

        assertTrue(path.toFile().exists())
        readSlideShow(path).use { ppt ->
            // Title + multiple content slides for the long section
            assertTrue(ppt.slides.size >= 3, "Expected at least 3 slides due to splitting, got ${ppt.slides.size}")
        }
    }

    @Test
    @DisplayName("generate includes list items")
    fun testGenerateWithList() = runBlocking {
        val generator = createGenerator()
        val blocks = listOf(
            ContentBlock(type = "title", text = "List Page", level = 0),
            ContentBlock(type = "heading", text = "Items", level = 2),
            ContentBlock(type = "list", items = listOf("Item 1", "Item 2", "Item 3"), ordered = false),
        )

        val path = generator.generate(blocks, "https://example.com", "List Page", tempDir).path

        assertTrue(path.toFile().exists())
        readSlideShow(path).use { ppt ->
            assertTrue(ppt.slides.size >= 2)
        }
    }

    @Test
    @DisplayName("generate includes table")
    fun testGenerateWithTable() = runBlocking {
        val generator = createGenerator()
        val blocks = listOf(
            ContentBlock(type = "title", text = "Table Page", level = 0),
            ContentBlock(type = "heading", text = "Data", level = 2),
            ContentBlock(
                type = "table",
                rows = listOf(
                    listOf("Name", "Score"),
                    listOf("Alice", "95"),
                    listOf("Bob", "87"),
                )
            ),
        )

        val path = generator.generate(blocks, "https://example.com", "Table Page", tempDir).path

        assertTrue(path.toFile().exists())
        readSlideShow(path).use { ppt ->
            assertTrue(ppt.slides.size >= 2)
        }
    }

    @Test
    @DisplayName("generated file has .pptx extension")
    fun testFileExtension() = runBlocking {
        val generator = createGenerator()
        val path = generator.generate(
            blocks = listOf(ContentBlock(type = "title", text = "Test", level = 0)),
            pageUrl = "https://example.com",
            pageTitle = "Test",
            outputDir = tempDir,
        ).path

        assertTrue(path.fileName.toString().endsWith(".pptx"))
    }

    @Test
    @DisplayName("generated file is valid PPTX (ZIP-based format)")
    fun testFileIsValidPptx() = runBlocking {
        val generator = createGenerator()
        val path = generator.generate(
            blocks = listOf(
                ContentBlock(type = "title", text = "Valid PPTX", level = 0),
                ContentBlock(type = "heading", text = "Section", level = 2),
                ContentBlock(type = "paragraph", text = "Content here."),
            ),
            pageUrl = "https://example.com",
            pageTitle = "Valid PPTX",
            outputDir = tempDir,
        ).path

        // PPTX is a ZIP file — should be readable by POI
        readSlideShow(path).use { ppt ->
            assertNotNull(ppt.pageSize)
            assertTrue(ppt.slides.size >= 2)
        }
    }

    @Test
    @DisplayName("blocks that exceed the per-slide cap continue on a slide instead of being dropped")
    fun testOverflowCarriesBlocksIntoContinuationSlides() = runBlocking {
        val generator = createGenerator(config = PptxConfig(maxContentBlocksPerSlide = 3))
        val paragraphs = (1..12).map { ContentBlock(type = "paragraph", text = "Paragraph number $it.") }
        val blocks = listOf(
            ContentBlock(type = "title", text = "Overflow Page", level = 0),
            ContentBlock(type = "heading", text = "Overflow Section", level = 2),
        ) + paragraphs

        val output = generator.generate(blocks, "https://example.com", "Overflow Page", tempDir)

        assertEquals(0, output.skippedBlockCount, "no block may be dropped on overflow")
        assertTrue(output.skippedBlockKinds.isEmpty(), "no block kind may be reported as skipped")
        assertTrue(slideCount(output.path) >= 5, "12 blocks with a cap of 3 need at least 4 content slides")

        val text = deckText(output.path)
        paragraphs.forEach { block ->
            assertTrue(text.contains(block.text!!), "block '${block.text}' is missing from the deck")
        }
    }

    @Test
    @DisplayName("a block too tall for the remaining slide space moves to the next slide")
    fun testHeightOverflowCarriesTheBlockForward() = runBlocking {
        val generator = createGenerator()
        val paragraphs = (1..6).map { index ->
            ContentBlock(type = "paragraph", text = "Tall paragraph $index " + "word ".repeat(120))
        }
        val blocks = listOf(
            ContentBlock(type = "title", text = "Tall Page", level = 0),
            ContentBlock(type = "heading", text = "Tall Section", level = 2),
        ) + paragraphs

        val output = generator.generate(blocks, "https://example.com", "Tall Page", tempDir)

        assertEquals(0, output.skippedBlockCount)
        val text = deckText(output.path)
        paragraphs.forEach { block ->
            val paragraphText = block.text.orEmpty()
            assertTrue(
                text.contains(paragraphText),
                "tall block '${paragraphText.take(24)}' is missing from the deck"
            )
        }
    }

    @Test
    @DisplayName("leading section without a heading is titled from the page title, never ' (continued N)'")
    fun testUntitledLeadSectionGetsFallbackTitle() = runBlocking {
        val generator = createGenerator(config = PptxConfig(maxContentBlocksPerSlide = 2))
        val blocks = mutableListOf(ContentBlock(type = "title", text = "Solar System", level = 0))
        repeat(5) { index -> blocks.add(ContentBlock(type = "paragraph", text = "Lead paragraph $index.")) }

        val output = generator.generate(blocks, "https://en.wikipedia.org/wiki/Solar_System", "Solar System", tempDir)
        val titles = slideTitles(output.path)

        assertTrue(
            titles.count { it == "Solar System" } >= 2,
            "the lead section must be titled from the page title: $titles"
        )
        assertTrue(
            titles.any { it.startsWith("Solar System (continued") },
            "continuation slides must keep the section title: $titles"
        )
        assertTrue(titles.none { it.startsWith(" ") }, "no slide title may start with a space: $titles")
        assertTrue(titles.none { it.isBlank() }, "titles must not be blank: $titles")
    }

    @Test
    @DisplayName("a page without a title falls back to the default lead section title")
    fun testBlankPageTitleFallsBackToDefaultLeadTitle() = runBlocking {
        val generator = createGenerator()
        val output = generator.generate(
            blocks = listOf(ContentBlock(type = "paragraph", text = "Only content on this page.")),
            pageUrl = "https://example.com/untitled",
            pageTitle = "",
            outputDir = tempDir,
        )

        assertTrue(
            slideTitles(output.path).contains(PptxGenerator.DEFAULT_LEAD_SECTION_TITLE),
            "expected the default lead section title, got ${slideTitles(output.path)}"
        )
    }

    @Test
    @DisplayName("images beyond the per-slide cap are embedded on a continuation slide")
    fun testImagesBeyondPerSlideCapContinueOnTheNextSlide() = runBlocking {
        val png = tinyPng()
        // Wide images stay short, so the per-slide image cap (not the height) ends the slide.
        val images = (1..4).map { index ->
            ContentBlock(
                type = "image",
                src = "https://example.com/$index.png",
                width = 400,
                height = 100,
            )
        }
        val generator = createGenerator(
            imageBytes = images.associate { it.src!! to png },
            config = PptxConfig(maxImagesPerSlide = 2),
        )
        val blocks = listOf(
            ContentBlock(type = "title", text = "Gallery", level = 0),
            ContentBlock(type = "heading", text = "Gallery", level = 2),
        ) + images

        val output = generator.generate(blocks, "https://example.com", "Gallery", tempDir)

        assertEquals(4, output.embeddedImageCount, "every downloaded image must reach a slide")
        assertEquals(0, output.skippedImageCount)
        assertTrue(slideCount(output.path) >= 3, "the image cap must split the gallery over 2 content slides")
    }

    @Test
    @DisplayName("blocks with nothing to render are counted by kind instead of vanishing")
    fun testUnrenderableBlocksAreCountedByKind() = runBlocking {
        val generator = createGenerator()
        val blocks = listOf(
            ContentBlock(type = "title", text = "Counters", level = 0),
            ContentBlock(type = "heading", text = "Section", level = 2),
            ContentBlock(type = "paragraph", text = null),
            ContentBlock(type = "table", rows = emptyList()),
            ContentBlock(type = "mystery", text = "unsupported kind"),
        )

        val output = generator.generate(blocks, "https://example.com", "Counters", tempDir)

        assertEquals(3, output.skippedBlockCount)
        assertEquals(mapOf("paragraph" to 1, "table" to 1, "mystery" to 1), output.skippedBlockKinds)
        assertTrue(
            output.warnings.any { it.contains("3 content block") },
            "the loss must be reported as a warning: ${output.warnings}"
        )
    }

    @Test
    @DisplayName("a failed image download still produces a deck and reports the shortfall")
    fun testImageDownloadShortfallIsReportedAsWarning() = runBlocking {
        val images = (1..3).map { ContentBlock(type = "image", src = "https://example.com/$it.png") }
        val generator = createGenerator(
            downloadResult = PptxImageDownloader.DownloadResult(
                bytes = emptyMap(),
                total = 3,
                failed = 3,
                failureReasons = mapOf("ConnectException: Connection refused" to 3),
            )
        )
        val blocks = listOf(
            ContentBlock(type = "title", text = "Offline Page", level = 0),
            ContentBlock(type = "heading", text = "Section", level = 2),
            ContentBlock(type = "paragraph", text = "Text content survives a broken image host."),
        ) + images

        val output = generator.generate(blocks, "https://example.com", "Offline Page", tempDir)

        assertTrue(output.path.toFile().exists(), "the deck must still be written without images")
        assertEquals(0, output.downloadedImageCount)
        assertEquals(3, output.failedImageCount)
        assertEquals(0, output.embeddedImageCount)
        assertEquals(3, output.skippedImageCount)
        assertTrue(deckText(output.path).contains("Text content survives a broken image host."))
        assertTrue(
            output.warnings.any { it.contains("HTTPS_PROXY") },
            "the warning must point at the proxy setting: ${output.warnings}"
        )
    }
}
