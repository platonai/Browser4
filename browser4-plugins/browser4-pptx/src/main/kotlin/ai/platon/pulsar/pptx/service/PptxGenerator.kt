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

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.pptx.config.PptxConfig
import org.apache.poi.sl.usermodel.PictureData
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign
import org.apache.poi.xslf.usermodel.*
import java.awt.Color
import java.awt.Dimension
import java.awt.Rectangle
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Generates PowerPoint (PPTX) files from structured web page content.
 *
 * Uses Apache POI XSLF to create slides with proper layout:
 * - Title slide with page title and URL
 * - Section slides grouped by heading hierarchy
 * - Content slides with paragraphs, images, tables, lists, code blocks
 */
open class PptxGenerator(
    private val config: PptxConfig,
    private val imageDownloader: PptxImageDownloader,
) {
    private val logger = getLogger(PptxGenerator::class)

    // Layout constants (in POI points, 1 inch = 72 points)
    companion object {
        private const val TITLE_FONT_SIZE = 40.0
        private const val SUBTITLE_FONT_SIZE = 18.0
        private const val SLIDE_TITLE_FONT_SIZE = 28.0
        private const val BODY_FONT_SIZE = 16.0
        private const val BODY_FONT_SIZE_SMALL = 14.0
        private const val CODE_FONT_SIZE = 12.0
        private const val MARGIN = 36.0
        private const val TITLE_HEIGHT = 60.0
        private const val LINE_SPACING = 24.0
        private const val IMAGE_MAX_WIDTH = 432.0
        private const val IMAGE_MAX_HEIGHT = 324.0
        private const val FONT_FAMILY = "Arial"
        private const val MONO_FONT = "Consolas"
    }

    /**
     * Generate a PPTX file from extracted content blocks.
     */
    open suspend fun generate(
        blocks: List<ContentBlock>,
        pageUrl: String,
        pageTitle: String,
        outputDir: Path,
    ): Path {
        Files.createDirectories(outputDir)

        val slideShow = XMLSlideShow()
        slideShow.pageSize = Dimension(config.slideWidth, config.slideHeight)

        try {
            // 1. Download all referenced images
            val imageBytes = imageDownloader.downloadImages(blocks)

            // 2. Create title slide
            createTitleSlide(slideShow, pageTitle, pageUrl)

            // 3. Group blocks into sections by heading hierarchy
            val sections = groupIntoSections(blocks)

            // 4. Create content slides for each section
            for (section in sections) {
                createSectionSlides(slideShow, section, imageBytes)
            }

            // 5. Write to file
            val filename = sanitizeFilename(pageTitle) + "_" + timestamp() + ".pptx"
            val outputPath = outputDir.resolve(filename)

            FileOutputStream(outputPath.toFile()).use { fos ->
                slideShow.write(fos)
            }

            logger.info(
                "Generated PPTX with {} slides for '{}': {}",
                slideShow.slides.size, pageTitle.take(80), outputPath
            )

            return outputPath
        } finally {
            slideShow.close()
        }
    }

    // ---- Slide creation ----

    private fun createTitleSlide(slideShow: XMLSlideShow, pageTitle: String, pageUrl: String) {
        val slide = slideShow.createSlide()
        val slideWidth = config.slideWidth.toDouble()
        val slideHeight = config.slideHeight.toDouble()

        // Title text
        val title = truncateText(pageTitle, config.maxTitleLength)
        val titleBox = slide.createTextBox()
        titleBox.setAnchor(Rectangle(
            MARGIN.toInt(),
            (slideHeight * 0.3).toInt(),
            (slideWidth - MARGIN * 2).toInt(),
            (TITLE_HEIGHT * 1.5).toInt()
        ))
        val titlePara = titleBox.addNewTextParagraph()
        titlePara.setTextAlign(TextAlign.CENTER)
        val titleRun = titlePara.addNewTextRun()
        titleRun.setText(title)
        titleRun.setFontSize(TITLE_FONT_SIZE)
        titleRun.setBold(true)
        titleRun.setFontFamily(FONT_FAMILY)
        titleRun.setFontColor(Color(0x1A, 0x1A, 0x1A))

        // Subtitle (URL)
        val subtitleBox = slide.createTextBox()
        subtitleBox.setAnchor(Rectangle(
            MARGIN.toInt(),
            (slideHeight * 0.3 + TITLE_HEIGHT * 1.5 + 20).toInt(),
            (slideWidth - MARGIN * 2).toInt(),
            TITLE_HEIGHT.toInt()
        ))
        val subPara = subtitleBox.addNewTextParagraph()
        subPara.setTextAlign(TextAlign.CENTER)
        val subRun = subPara.addNewTextRun()
        subRun.setText(pageUrl)
        subRun.setFontSize(SUBTITLE_FONT_SIZE)
        subRun.setFontFamily(FONT_FAMILY)
        subRun.setFontColor(Color(0x66, 0x66, 0x66))
    }

    private fun createSectionSlides(
        slideShow: XMLSlideShow,
        section: Section,
        imageBytes: Map<String, ByteArray>,
    ) {
        val blocks = section.blocks
        if (blocks.isEmpty()) return

        var blockIndex = 0
        var continuationIndex = 0

        while (blockIndex < blocks.size) {
            val remainingBlocks = blocks.size - blockIndex
            val chunkSize = if (remainingBlocks <= config.maxContentBlocksPerSlide) {
                remainingBlocks
            } else {
                findSplitPoint(blocks, blockIndex, config.maxContentBlocksPerSlide)
            }

            val chunk = blocks.subList(blockIndex, blockIndex + chunkSize)
            val slideTitle = if (continuationIndex == 0) {
                section.title
            } else {
                "${section.title} (continued $continuationIndex)"
            }

            createContentSlide(slideShow, slideTitle, chunk, imageBytes)

            blockIndex += chunkSize
            continuationIndex++
        }
    }

    private fun createContentSlide(
        slideShow: XMLSlideShow,
        slideTitle: String,
        blocks: List<ContentBlock>,
        imageBytes: Map<String, ByteArray>,
    ) {
        val slide = slideShow.createSlide()
        val slideWidth = config.slideWidth.toDouble()
        val slideHeight = config.slideHeight.toDouble()

        // Slide title
        if (slideTitle.isNotBlank()) {
            val titleBox = slide.createTextBox()
            titleBox.setAnchor(Rectangle(
                MARGIN.toInt(),
                (MARGIN * 0.5).toInt(),
                (slideWidth - MARGIN * 2).toInt(),
                TITLE_HEIGHT.toInt()
            ))
            val titlePara = titleBox.addNewTextParagraph()
            val titleRun = titlePara.addNewTextRun()
            titleRun.setText(truncateText(slideTitle, 100))
            titleRun.setFontSize(SLIDE_TITLE_FONT_SIZE)
            titleRun.setBold(true)
            titleRun.setFontFamily(FONT_FAMILY)
            titleRun.setFontColor(Color(0x1A, 0x1A, 0x1A))
        }

        val contentTop = if (slideTitle.isNotBlank()) MARGIN + TITLE_HEIGHT + 10.0 else MARGIN
        var yOffset = contentTop
        val contentWidth = slideWidth - MARGIN * 2
        var imageCountOnSlide = 0

        for (block in blocks) {
            when (block.type) {
                "heading" -> {
                    yOffset = addHeadingBlock(slide, block, yOffset, contentWidth)
                }
                "paragraph" -> {
                    yOffset = addParagraphBlock(slide, block, yOffset, contentWidth)
                }
                "image" -> {
                    if (imageCountOnSlide < config.maxImagesPerSlide) {
                        val newOffset = addImageBlock(slideShow, slide, block, imageBytes, yOffset, contentWidth)
                        if (newOffset != yOffset) imageCountOnSlide++
                        yOffset = newOffset
                    }
                }
                "table" -> {
                    yOffset = addTableBlock(slide, block, yOffset, contentWidth)
                }
                "list" -> {
                    yOffset = addListBlock(slide, block, yOffset, contentWidth)
                }
                "code" -> {
                    yOffset = addCodeBlock(slide, block, yOffset, contentWidth)
                }
                "blockquote" -> {
                    yOffset = addBlockquoteBlock(slide, block, yOffset, contentWidth)
                }
            }

            if (yOffset > slideHeight - MARGIN) break
        }
    }

    // ---- Individual block renderers ----

    private fun addHeadingBlock(
        slide: XSLFSlide, block: ContentBlock, yOffset: Double, contentWidth: Double
    ): Double {
        val headingBox = slide.createTextBox()
        headingBox.setAnchor(Rectangle(
            MARGIN.toInt(), yOffset.toInt(),
            contentWidth.toInt(), (LINE_SPACING * 1.5).toInt()
        ))
        val para = headingBox.addNewTextParagraph()
        val run = para.addNewTextRun()
        run.setText(block.text ?: "")
        run.setFontSize(if ((block.level ?: 3) <= 2) BODY_FONT_SIZE + 4 else BODY_FONT_SIZE + 2)
        run.setBold(true)
        run.setFontFamily(FONT_FAMILY)
        run.setFontColor(Color(0x33, 0x33, 0x33))
        return yOffset + LINE_SPACING * 1.5 + 4
    }

    private fun addParagraphBlock(
        slide: XSLFSlide, block: ContentBlock, yOffset: Double, contentWidth: Double
    ): Double {
        val text = block.text ?: return yOffset
        val lines = estimateTextLines(text, contentWidth, BODY_FONT_SIZE)
        val height = lines * LINE_SPACING.coerceAtLeast(LINE_SPACING)

        val paraBox = slide.createTextBox()
        paraBox.setAnchor(Rectangle(
            MARGIN.toInt(), yOffset.toInt(),
            contentWidth.toInt(), height.toInt() + 4
        ))
        val para = paraBox.addNewTextParagraph()
        val run = para.addNewTextRun()
        run.setText(text)
        run.setFontSize(BODY_FONT_SIZE)
        run.setFontFamily(FONT_FAMILY)
        run.setFontColor(Color(0x44, 0x44, 0x44))
        return yOffset + height + 4
    }

    private fun addImageBlock(
        slideShow: XMLSlideShow, slide: XSLFSlide, block: ContentBlock,
        imageBytes: Map<String, ByteArray>, yOffset: Double, contentWidth: Double
    ): Double {
        val src = block.src ?: return yOffset
        val bytes = imageBytes[src] ?: return yOffset

        return try {
            val pictureType = detectPictureType(bytes)
            val pictureData = slideShow.addPicture(bytes, pictureType)
            val picture = slide.createPicture(pictureData)

            val (pw, ph) = scaleImage(
                block.width ?: 0, block.height ?: 0,
                IMAGE_MAX_WIDTH, IMAGE_MAX_HEIGHT
            )

            val imageX = MARGIN + (contentWidth - pw) / 2
            picture.setAnchor(Rectangle(imageX.toInt(), yOffset.toInt(), pw.toInt(), ph.toInt()))

            var newY = yOffset + ph + 6

            // Caption
            val altText = block.alt
            if (!altText.isNullOrBlank()) {
                val capBox = slide.createTextBox()
                capBox.setAnchor(Rectangle(
                    MARGIN.toInt(), newY.toInt(),
                    contentWidth.toInt(), LINE_SPACING.toInt()
                ))
                val capPara = capBox.addNewTextParagraph()
                capPara.setTextAlign(TextAlign.CENTER)
                val capRun = capPara.addNewTextRun()
                capRun.setText(altText)
                capRun.setFontSize(BODY_FONT_SIZE_SMALL)
                capRun.setItalic(true)
                capRun.setFontFamily(FONT_FAMILY)
                capRun.setFontColor(Color(0x88, 0x88, 0x88))
                newY += LINE_SPACING + 4
            }
            newY
        } catch (e: Exception) {
            logger.warn("Failed to embed image in PPTX: {}", src.take(120))
            yOffset
        }
    }

    private fun addTableBlock(
        slide: XSLFSlide, block: ContentBlock, yOffset: Double, contentWidth: Double
    ): Double {
        val rows = block.rows ?: return yOffset
        if (rows.isEmpty()) return yOffset

        val tableHeight = rows.size * LINE_SPACING + 8
        val table = slide.createTable()
        table.setAnchor(Rectangle(
            MARGIN.toInt(), yOffset.toInt(),
            contentWidth.toInt(), tableHeight.toInt()
        ))

        for ((rowIdx, row) in rows.withIndex()) {
            val tableRow = table.addRow()
            for (cellText in row) {
                val cell = tableRow.addCell()
                cell.setText(cellText)
                if (rowIdx == 0) {
                    cell.setFillColor(Color(0xE8, 0xE8, 0xE8))
                    for (para in cell.getTextParagraphs()) {
                        for (run in para.getTextRuns()) {
                            run.setBold(true)
                            run.setFontSize(BODY_FONT_SIZE)
                            run.setFontFamily(FONT_FAMILY)
                        }
                    }
                } else {
                    for (para in cell.getTextParagraphs()) {
                        for (run in para.getTextRuns()) {
                            run.setFontSize(BODY_FONT_SIZE_SMALL)
                            run.setFontFamily(FONT_FAMILY)
                        }
                    }
                }
            }
        }

        return yOffset + tableHeight + 8
    }

    private fun addListBlock(
        slide: XSLFSlide, block: ContentBlock, yOffset: Double, contentWidth: Double
    ): Double {
        val items = block.items ?: return yOffset
        val listHeight = items.size * LINE_SPACING + 4

        val listBox = slide.createTextBox()
        listBox.setAnchor(Rectangle(
            (MARGIN + 20).toInt(), yOffset.toInt(),
            (contentWidth - 20).toInt(), listHeight.toInt()
        ))

        for ((idx, item) in items.withIndex()) {
            val para = listBox.addNewTextParagraph()
            val bullet = if (block.ordered == true) "${idx + 1}. " else "• "
            val run = para.addNewTextRun()
            run.setText("$bullet$item")
            run.setFontSize(BODY_FONT_SIZE)
            run.setFontFamily(FONT_FAMILY)
            run.setFontColor(Color(0x44, 0x44, 0x44))
            para.setSpaceBefore(2.0)
            para.setSpaceAfter(2.0)
        }

        return yOffset + listHeight + 4
    }

    private fun addCodeBlock(
        slide: XSLFSlide, block: ContentBlock, yOffset: Double, contentWidth: Double
    ): Double {
        val codeText = block.text ?: return yOffset
        val codeLines = codeText.lines().take(20)
        val codeHeight = codeLines.size * (LINE_SPACING * 0.9) + 12

        val codeBox = slide.createTextBox()
        codeBox.setAnchor(Rectangle(
            MARGIN.toInt(), yOffset.toInt(),
            contentWidth.toInt(), codeHeight.toInt()
        ))
        codeBox.setFillColor(Color(0xF5, 0xF5, 0xF5))

        for (line in codeLines) {
            val para = codeBox.addNewTextParagraph()
            val run = para.addNewTextRun()
            run.setText(line)
            run.setFontSize(CODE_FONT_SIZE)
            run.setFontFamily(MONO_FONT)
            run.setFontColor(Color(0x33, 0x33, 0x33))
            para.setSpaceBefore(1.0)
            para.setSpaceAfter(1.0)
        }

        return yOffset + codeHeight + 6
    }

    private fun addBlockquoteBlock(
        slide: XSLFSlide, block: ContentBlock, yOffset: Double, contentWidth: Double
    ): Double {
        val text = block.text ?: return yOffset
        val lines = estimateTextLines(text, contentWidth - 20, BODY_FONT_SIZE)
        val height = (lines * LINE_SPACING).coerceAtLeast(LINE_SPACING) + 8

        // Left border
        val borderBox = slide.createTextBox()
        borderBox.setAnchor(Rectangle(MARGIN.toInt(), yOffset.toInt(), 4, height.toInt()))
        borderBox.setFillColor(Color(0xCC, 0xCC, 0xCC))

        val quoteBox = slide.createTextBox()
        quoteBox.setAnchor(Rectangle(
            (MARGIN + 20).toInt(), yOffset.toInt(),
            (contentWidth - 20).toInt(), height.toInt()
        ))

        val para = quoteBox.addNewTextParagraph()
        val run = para.addNewTextRun()
        run.setText(text)
        run.setFontSize(BODY_FONT_SIZE)
        run.setItalic(true)
        run.setFontFamily(FONT_FAMILY)
        run.setFontColor(Color(0x66, 0x66, 0x66))

        return yOffset + height + 6
    }

    // ---- Content organization ----

    private fun groupIntoSections(blocks: List<ContentBlock>): List<Section> {
        if (blocks.isEmpty()) return emptyList()

        val headingLevels = blocks
            .filter { it.type == "heading" && it.level != null }
            .map { it.level!! }
            .distinct()
            .sorted()
        val sectionLevel = headingLevels.firstOrNull() ?: 2

        val sections = mutableListOf<Section>()
        var currentSectionBlocks = mutableListOf<ContentBlock>()
        var currentSectionTitle: String? = null

        for (block in blocks) {
            if (block.type == "title") continue

            if (block.type == "heading" && block.level != null && block.level <= sectionLevel + 1) {
                if (currentSectionBlocks.isNotEmpty()) {
                    sections.add(Section(currentSectionTitle ?: "", currentSectionBlocks.toList()))
                }
                currentSectionTitle = block.text ?: ""
                currentSectionBlocks = mutableListOf()
            } else {
                currentSectionBlocks.add(block)
            }
        }

        if (currentSectionBlocks.isNotEmpty()) {
            sections.add(Section(currentSectionTitle ?: "", currentSectionBlocks.toList()))
        }

        if (sections.isEmpty() && blocks.isNotEmpty()) {
            sections.add(Section("", blocks.filter { it.type != "title" }))
        }

        return sections
    }

    private fun findSplitPoint(blocks: List<ContentBlock>, start: Int, maxPerSlide: Int): Int {
        val end = (start + maxPerSlide).coerceAtMost(blocks.size)
        for (i in end - 1 downTo start + 1) {
            when (blocks[i].type) {
                "paragraph", "heading", "list", "blockquote", "code" -> return i - start + 1
            }
        }
        return maxPerSlide
    }

    // ---- Utility functions ----

    private fun scaleImage(origWidth: Int, origHeight: Int, maxWidth: Double, maxHeight: Double): Pair<Double, Double> {
        if (origWidth <= 0 || origHeight <= 0) {
            return Pair(maxWidth * 0.7, maxHeight * 0.5)
        }
        val ratio = origWidth.toDouble() / origHeight.toDouble()
        var w = maxWidth
        var h = w / ratio
        if (h > maxHeight) {
            h = maxHeight
            w = h * ratio
        }
        return Pair(w, h)
    }

    private fun estimateTextLines(text: String, boxWidth: Double, fontSize: Double): Double {
        val charsPerLine = (boxWidth / (fontSize * 0.6)).coerceAtLeast(1.0)
        return (text.length / charsPerLine + 1).coerceAtLeast(1.0)
    }

    private fun detectPictureType(bytes: ByteArray): PictureData.PictureType {
        return when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() ->
                PictureData.PictureType.JPEG
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() ->
                PictureData.PictureType.PNG
            bytes.size >= 6 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() ->
                PictureData.PictureType.GIF
            // WebP: RIFF....WEBP header — POI 5.4 may not have WEBP enum, use PNG as fallback
            bytes.size >= 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
                bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
                bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte() ->
                PictureData.PictureType.PNG
            else -> PictureData.PictureType.JPEG
        }
    }

    private fun sanitizeFilename(name: String): String {
        return name
            .replace(Regex("""[<>:"/\\|?*]"""), "_")
            .replace(Regex("""\s+"""), "_")
            .take(100)
            .trimEnd('.', '_')
            .ifBlank { "presentation" }
    }

    private fun truncateText(text: String, maxLength: Int): String {
        return if (text.length <= maxLength) text else text.take(maxLength - 3) + "..."
    }

    private fun timestamp(): String {
        return DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
    }

    data class Section(
        val title: String,
        val blocks: List<ContentBlock>,
    )
}
