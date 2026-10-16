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

        /** Title used for the leading section on pages whose content starts before
         *  the first heading — an empty title would render as " (continued 1)". */
        const val DEFAULT_LEAD_SECTION_TITLE = "Overview"
    }

    /**
     * Result of a PPTX generation pass.
     *
     * @property path               absolute path to the generated PPTX
     * @property embeddedImageCount images actually placed on slides (not block count)
     * @property skippedImageCount  image blocks that could not be placed on any slide
     *                              (no downloaded bytes, unsupported format, embed failure)
     * @property failedImageCount   image URLs whose download failed
     * @property downloadedImageCount image URLs downloaded successfully
     * @property skippedBlockCount  non-image content blocks that were not rendered
     * @property skippedBlockKinds  not-rendered block count per block kind
     * @property imageDownloadMillis wall clock spent downloading images
     * @property imageDownloadDeadlineExceeded whether the image download phase expired
     * @property warnings           human readable diagnostics for a partial deck
     */
    data class PptxGenerationOutput(
        val path: Path,
        val embeddedImageCount: Int,
        val skippedImageCount: Int,
        val failedImageCount: Int,
        val downloadedImageCount: Int = 0,
        val skippedBlockCount: Int = 0,
        val skippedBlockKinds: Map<String, Int> = emptyMap(),
        val imageDownloadMillis: Long = 0,
        val imageDownloadDeadlineExceeded: Boolean = false,
        val warnings: List<String> = emptyList(),
    )

    /**
     * Generate a PPTX file from extracted content blocks.
     *
     * The image download phase is bounded (see [PptxConfig.downloadTotalTimeoutSeconds]),
     * and blocks that do not fit a slide are carried into a continuation slide instead
     * of being discarded, so the returned [PptxGenerationOutput] always describes the
     * deck that was actually written.
     */
    open suspend fun generate(
        blocks: List<ContentBlock>,
        pageUrl: String,
        pageTitle: String,
        outputDir: Path,
    ): PptxGenerationOutput {
        Files.createDirectories(outputDir)

        val slideShow = XMLSlideShow()
        slideShow.pageSize = Dimension(config.slideWidth, config.slideHeight)

        var embeddedImageCount = 0
        var skippedImageCount = 0
        var skippedBlockCount = 0
        val skippedBlockKinds = mutableMapOf<String, Int>()

        try {
            // 1. Download all referenced images — bounded by the per-image and the
            //    overall download deadline, so a blocked host degrades to a partial
            //    deck instead of hanging past the server's async budget.
            val download = imageDownloader.downloadImages(blocks)
            val imageBytes = download.bytes
            logger.info(
                "PPTX download stage: {}/{} images in {} ms (failed {}, skipped {}, aborted {})",
                download.downloaded, download.total, download.durationMillis,
                download.failed, download.skipped, download.aborted
            )

            // 2. Create title slide
            createTitleSlide(slideShow, pageTitle, pageUrl)

            // 3. Group blocks into sections by heading hierarchy
            val sections = groupIntoSections(blocks, pageTitle)

            // 4. Create content slides for each section
            for (section in sections) {
                val counts = createSectionSlides(slideShow, section, imageBytes)
                embeddedImageCount += counts.embeddedImages
                skippedImageCount += counts.skippedImages
                skippedBlockCount += counts.skippedBlocks
                counts.skippedKinds.forEach { (kind, count) -> skippedBlockKinds.merge(kind, count, Int::plus) }
            }

            // 5. Write to file
            val filename = sanitizeFilename(pageTitle, pageUrl) + "_" + timestamp() + ".pptx"
            val outputPath = outputDir.resolve(filename)

            FileOutputStream(outputPath.toFile()).use { fos ->
                slideShow.write(fos)
            }

            val warnings = buildWarnings(download, skippedBlockCount, skippedBlockKinds, embeddedImageCount)
            warnings.forEach { logger.warn("PPTX generation warning: {}", it) }

            logger.info(
                "Generated PPTX with {} slides for '{}': {}",
                slideShow.slides.size, pageTitle.take(80), outputPath
            )

            return PptxGenerationOutput(
                path = outputPath,
                embeddedImageCount = embeddedImageCount,
                skippedImageCount = skippedImageCount,
                failedImageCount = download.failed,
                downloadedImageCount = download.downloaded,
                skippedBlockCount = skippedBlockCount,
                skippedBlockKinds = skippedBlockKinds.toMap(),
                imageDownloadMillis = download.durationMillis,
                imageDownloadDeadlineExceeded = download.deadlineExceeded,
                warnings = warnings,
            )
        } finally {
            slideShow.close()
        }
    }

    /**
     * Build the diagnostics that describe a partial deck.
     *
     * @param download the image download outcome
     * @param skippedBlockCount content blocks that were not rendered
     * @param skippedBlockKinds not-rendered block count per kind
     * @param embeddedImageCount images placed on slides
     * @return warning lines, empty when the deck is complete
     */
    private fun buildWarnings(
        download: PptxImageDownloader.DownloadResult,
        skippedBlockCount: Int,
        skippedBlockKinds: Map<String, Int>,
        embeddedImageCount: Int,
    ): List<String> {
        val warnings = mutableListOf<String>()
        warnings += imageDownloader.describeShortfall(download)

        if (skippedBlockCount > 0) {
            val detail = skippedBlockKinds
                .filterKeys { it != "image" }
                .entries
                .joinToString(", ") { "${it.key}: ${it.value}" }
            warnings += "$skippedBlockCount content block(s) could not be rendered and are missing " +
                    "from the deck ($detail)."
        }

        if (download.total > 0 && download.downloaded > 0 && download.partial && embeddedImageCount < download.downloaded) {
            warnings += "${download.downloaded - embeddedImageCount} downloaded image(s) are not on any slide " +
                    "(per-slide image cap or unusable image data)."
        }
        return warnings
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

    private data class SectionSlideCounts(
        val embeddedImages: Int,
        val skippedImages: Int,
        val skippedBlocks: Int,
        val skippedKinds: Map<String, Int>,
    )

    /**
     * How one content slide turned out.
     *
     * @property embeddedImages images placed on the slide
     * @property skippedImages  image blocks that can never be placed
     * @property skippedBlocks  non-image blocks that can never be rendered
     * @property skippedKinds   not-rendered count per block kind
     * @property carriedBlocks  blocks that did not fit and belong on the next slide
     */
    private data class ContentSlideOutcome(
        val embeddedImages: Int,
        val skippedImages: Int,
        val skippedBlocks: Int,
        val skippedKinds: Map<String, Int>,
        val carriedBlocks: List<ContentBlock>,
    )

    private fun createSectionSlides(
        slideShow: XMLSlideShow,
        section: Section,
        imageBytes: Map<String, ByteArray>,
    ): SectionSlideCounts {
        if (section.blocks.isEmpty()) return SectionSlideCounts(0, 0, 0, emptyMap())

        var pending = section.blocks
        var continuationIndex = 0
        var embedded = 0
        var skippedImages = 0
        var skippedBlocks = 0
        val skippedKinds = mutableMapOf<String, Int>()

        // Blocks that do not fit a slide are carried into a continuation slide
        // instead of being discarded, so every extracted block reaches the deck.
        while (pending.isNotEmpty()) {
            val slideTitle = continuationTitle(section.title, continuationIndex)
            val outcome = createContentSlide(slideShow, slideTitle, pending, imageBytes)

            embedded += outcome.embeddedImages
            skippedImages += outcome.skippedImages
            skippedBlocks += outcome.skippedBlocks
            outcome.skippedKinds.forEach { (kind, count) -> skippedKinds.merge(kind, count, Int::plus) }

            val carried = outcome.carriedBlocks
            if (carried.size >= pending.size) {
                // Defensive: a slide always renders its first block, so this cannot
                // happen — but never spin forever if that invariant is ever broken.
                logger.warn("PPTX generation: giving up on {} unrenderable blocks", carried.size)
                skippedImages += carried.count { it.type == "image" }
                skippedBlocks += carried.count { it.type != "image" }
                carried.forEach { skippedKinds.merge(it.type.ifBlank { "unknown" }, 1, Int::plus) }
                break
            }

            pending = carried
            continuationIndex++
        }

        return SectionSlideCounts(embedded, skippedImages, skippedBlocks, skippedKinds)
    }

    private fun createContentSlide(
        slideShow: XMLSlideShow,
        slideTitle: String,
        blocks: List<ContentBlock>,
        imageBytes: Map<String, ByteArray>,
    ): ContentSlideOutcome {
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
        val usableBottom = slideHeight - MARGIN
        var yOffset = contentTop
        val contentWidth = slideWidth - MARGIN * 2
        var imageCountOnSlide = 0
        var placedBlocks = 0
        var embedded = 0
        var skippedImages = 0
        var skippedBlocks = 0
        val skippedKinds = mutableMapOf<String, Int>()
        var carried: List<ContentBlock> = emptyList()

        for ((index, block) in blocks.withIndex()) {
            if (!isRenderable(block)) {
                // Nothing to draw (missing text/rows/items/src) or an unknown block
                // kind: count it by kind so the loss is reported, never silent.
                if (block.type == "image") skippedImages++ else skippedBlocks++
                skippedKinds.merge(block.type.ifBlank { "unknown" }, 1, Int::plus)
                continue
            }

            // The first block is always rendered, even when it is taller than the
            // slide, so the section loop is guaranteed to make progress.
            if (index > 0) {
                if (placedBlocks >= config.maxContentBlocksPerSlide) {
                    carried = blocks.subList(index, blocks.size)
                    break
                }
                // Height-aware split: close the slide before a block would overflow
                // it, and put that block on a continuation slide instead of losing it.
                if (yOffset + estimateBlockHeight(block, contentWidth) > usableBottom) {
                    carried = blocks.subList(index, blocks.size)
                    break
                }
            }

            when (block.type) {
                "heading" -> {
                    yOffset = addHeadingBlock(slide, block, yOffset, contentWidth)
                    placedBlocks++
                }
                "paragraph" -> {
                    yOffset = addParagraphBlock(slide, block, yOffset, contentWidth)
                    placedBlocks++
                }
                "image" -> {
                    val bytes = block.src?.let { imageBytes[it] }
                    if (bytes == null) {
                        // Not downloaded (or unusable): carrying it forward would never
                        // succeed, so count it as a loss instead of looping on it.
                        skippedImages++
                        skippedKinds.merge("image", 1, Int::plus)
                    } else if (index > 0 && imageCountOnSlide >= config.maxImagesPerSlide) {
                        // Per-slide image cap reached — continue on the next slide.
                        carried = blocks.subList(index, blocks.size)
                        break
                    } else {
                        val newOffset = addImageBlock(slideShow, slide, block, imageBytes, yOffset, contentWidth)
                        if (newOffset != yOffset) {
                            imageCountOnSlide++
                            embedded++
                            placedBlocks++
                        } else {
                            // addImageBlock returned without placing the image
                            // (embed failure) — count it, not a silent drop.
                            skippedImages++
                            skippedKinds.merge("image", 1, Int::plus)
                        }
                        yOffset = newOffset
                    }
                }
                "table" -> {
                    yOffset = addTableBlock(slide, block, yOffset, contentWidth)
                    placedBlocks++
                }
                "list" -> {
                    yOffset = addListBlock(slide, block, yOffset, contentWidth)
                    placedBlocks++
                }
                "code" -> {
                    yOffset = addCodeBlock(slide, block, yOffset, contentWidth)
                    placedBlocks++
                }
                "blockquote" -> {
                    yOffset = addBlockquoteBlock(slide, block, yOffset, contentWidth)
                    placedBlocks++
                }
                else -> {
                    if (block.type == "image") skippedImages++ else skippedBlocks++
                    skippedKinds.merge(block.type.ifBlank { "unknown" }, 1, Int::plus)
                }
            }
        }

        return ContentSlideOutcome(embedded, skippedImages, skippedBlocks, skippedKinds, carried)
    }

    /**
     * Whether a block carries anything the renderers can draw.
     *
     * Blocks without text/rows/items/source would otherwise disappear between
     * extraction and the deck without any accounting.
     */
    private fun isRenderable(block: ContentBlock): Boolean = when (block.type) {
        "heading", "paragraph", "blockquote", "code" -> !block.text.isNullOrBlank()
        "table" -> !block.rows.isNullOrEmpty()
        "list" -> !block.items.isNullOrEmpty()
        "image" -> !block.src.isNullOrBlank()
        else -> false
    }

    /**
     * Estimate the vertical space [block] needs, mirroring the block renderers, so a
     * slide can be closed before the block would overflow it.
     */
    private fun estimateBlockHeight(block: ContentBlock, contentWidth: Double): Double {
        return when (block.type) {
            "heading" -> LINE_SPACING * 1.5 + 4
            "paragraph" -> estimateTextLines(block.text.orEmpty(), contentWidth, BODY_FONT_SIZE) * LINE_SPACING + 4
            "blockquote" ->
                estimateTextLines(block.text.orEmpty(), contentWidth - 20, BODY_FONT_SIZE) * LINE_SPACING + 14
            "code" -> block.text.orEmpty().lines().take(20).size * (LINE_SPACING * 0.9) + 18
            "table" -> (block.rows?.size ?: 0) * LINE_SPACING + 16
            "list" -> (block.items?.size ?: 0) * LINE_SPACING + 8
            "image" -> {
                val (_, height) = scaleImage(block.width ?: 0, block.height ?: 0, IMAGE_MAX_WIDTH, IMAGE_MAX_HEIGHT)
                height + 6 + if (block.alt.isNullOrBlank()) 0.0 else LINE_SPACING + 4
            }
            else -> LINE_SPACING
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

    /**
     * Group content blocks into sections, one per heading at (or above) the top
     * heading level found on the page.
     *
     * Content that precedes the first heading (Wikipedia's lead section, a blog
     * intro) gets [pageTitle] as its title, falling back to
     * [DEFAULT_LEAD_SECTION_TITLE] — an empty title used to render continuation
     * slides as `" (continued 1)"`.
     *
     * @param blocks the extracted content blocks in document order
     * @param pageTitle the page title, used to title the leading section
     * @return the sections in document order
     */
    private fun groupIntoSections(blocks: List<ContentBlock>, pageTitle: String): List<Section> {
        if (blocks.isEmpty()) return emptyList()

        val leadTitle = leadSectionTitle(pageTitle)
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
            // The page <title> is rendered on the title slide, not as content.
            if (block.type == "title") continue

            if (block.type == "heading" && block.level != null && block.level <= sectionLevel + 1) {
                if (currentSectionBlocks.isNotEmpty()) {
                    sections.add(Section(currentSectionTitle ?: leadTitle, currentSectionBlocks.toList()))
                }
                currentSectionTitle = block.text ?: ""
                currentSectionBlocks = mutableListOf()
            } else {
                currentSectionBlocks.add(block)
            }
        }

        if (currentSectionBlocks.isNotEmpty()) {
            sections.add(Section(currentSectionTitle ?: leadTitle, currentSectionBlocks.toList()))
        }

        if (sections.isEmpty() && blocks.isNotEmpty()) {
            sections.add(Section(leadTitle, blocks.filter { it.type != "title" }))
        }

        return sections
    }

    /**
     * Title of the section that precedes the first heading.
     *
     * @param pageTitle the page title; blank titles fall back to
     *        [DEFAULT_LEAD_SECTION_TITLE] so no slide title is ever empty
     * @return the section title
     */
    private fun leadSectionTitle(pageTitle: String): String =
        pageTitle.trim().takeIf { it.isNotBlank() } ?: DEFAULT_LEAD_SECTION_TITLE

    /**
     * Title of the [continuationIndex]-th slide of a section.
     *
     * The suffix is only added from the second slide on, and a blank section title
     * never produces a title that starts with a space.
     *
     * @param sectionTitle the section's own title
     * @param continuationIndex 0 for the section's first slide
     * @return the slide title
     */
    private fun continuationTitle(sectionTitle: String, continuationIndex: Int): String {
        val base = sectionTitle.trim()
        if (continuationIndex == 0) return base
        return if (base.isEmpty()) "Continued $continuationIndex" else "$base (continued $continuationIndex)"
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

    private fun sanitizeFilename(name: String, fallbackUrl: String = ""): String {
        val sanitized = name
            .replace(Regex("""[<>:"/\\|?*]"""), "_")
            .replace(Regex("""\s+"""), "_")
            .take(100)
            .trimEnd('.', '_')

        if (sanitized.isNotBlank()) return sanitized

        // Fall back to URL-derived name when the page title is empty.
        // Extract a filename-friendly slug from the URL host + path.
        if (fallbackUrl.isNotBlank()) {
            val urlSlug = fallbackUrl
                .removePrefix("https://")
                .removePrefix("http://")
                .replace(Regex("""[<>:"/\\|?*\s]"""), "_")
                .replace(Regex("""_+"""), "_")
                .take(100)
                .trimEnd('.', '_')
            if (urlSlug.isNotBlank()) return urlSlug
        }

        return "presentation"
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
