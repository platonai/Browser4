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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.FileInputStream
import java.nio.file.Path

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
        config: PptxConfig = PptxConfig()
    ): PptxGenerator {
        val downloader = object : PptxImageDownloader(config, OkHttpClient()) {
            override suspend fun downloadImages(blocks: List<ContentBlock>): Map<String, ByteArray> {
                return imageBytes
            }
        }
        return PptxGenerator(config, downloader)
    }

    private fun readSlideShow(path: Path): XMLSlideShow {
        return XMLSlideShow(FileInputStream(path.toFile()))
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
        )

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
        )

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

        val path = generator.generate(blocks, "https://example.com", "Page", tempDir)

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

        val path = generator.generate(blocks, "https://example.com", "No Headings Page", tempDir)

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

        val path = generator.generate(blocks, "https://example.com", "Long Page", tempDir)

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

        val path = generator.generate(blocks, "https://example.com", "List Page", tempDir)

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

        val path = generator.generate(blocks, "https://example.com", "Table Page", tempDir)

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
        )

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
        )

        // PPTX is a ZIP file — should be readable by POI
        readSlideShow(path).use { ppt ->
            assertNotNull(ppt.pageSize)
            assertTrue(ppt.slides.size >= 2)
        }
    }
}
