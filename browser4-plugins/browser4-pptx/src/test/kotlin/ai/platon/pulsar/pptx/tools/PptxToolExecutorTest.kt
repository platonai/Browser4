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
package ai.platon.pulsar.pptx.tools

import ai.platon.pulsar.pptx.config.PptxConfig
import ai.platon.pulsar.pptx.service.ContentBlock
import ai.platon.pulsar.pptx.service.PageContentExtractor
import ai.platon.pulsar.pptx.service.PptxGenerator
import ai.platon.pulsar.pptx.service.PptxGenerator.PptxGenerationOutput
import ai.platon.pulsar.pptx.service.PptxImageDownloader
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Tests for the `pptx_generate` result payload built by [PptxToolExecutor].
 */
class PptxToolExecutorTest {

    private val config = PptxConfig()

    /** Exposes the result payload builder and the registered tool spec. */
    private class TestableExecutor(
        contentExtractor: PageContentExtractor,
        pptxGenerator: PptxGenerator,
        config: PptxConfig,
    ) : PptxToolExecutor(contentExtractor, pptxGenerator, config) {
        fun payload(
            result: PptxGenerationOutput?,
            blockCount: Int,
            slideCount: Int,
            durationMs: Long,
            pageUrl: String,
            error: String? = null,
        ): Map<String, Any?> = buildResultPayload(result, blockCount, slideCount, durationMs, pageUrl, error)

        fun generateHelpText(): String = toolSpec.getValue("generate").help.orEmpty()
    }

    private fun createExecutor(): TestableExecutor {
        val downloader = object : PptxImageDownloader(config, OkHttpClient()) {
            override suspend fun downloadImages(blocks: List<ContentBlock>) =
                PptxImageDownloader.DownloadResult(emptyMap(), 0, 0)
        }
        return TestableExecutor(PageContentExtractor(config), PptxGenerator(config, downloader), config)
    }

    private fun generationOutput(
        embedded: Int = 0,
        skippedImages: Int = 0,
        failed: Int = 0,
        downloaded: Int = 0,
    ) = PptxGenerationOutput(
        path = Path.of("deck.pptx"),
        embeddedImageCount = embedded,
        skippedImageCount = skippedImages,
        failedImageCount = failed,
        downloadedImageCount = downloaded,
    )

    @Test
    @DisplayName("downloadedImages reports the downloader count, not embedded plus skipped")
    fun testDownloadedImagesUsesTheDownloaderCount() {
        val payload = createExecutor().payload(
            result = generationOutput(embedded = 36, skippedImages = 17, failed = 1, downloaded = 51),
            blockCount = 214,
            slideCount = 48,
            durationMs = 12_760,
            pageUrl = "https://en.wikipedia.org/wiki/Solar_System",
        )

        assertEquals(
            51, payload["downloadedImages"],
            "downloadedImages must be the downloader's own count (embedded + skipped = 53 is wrong)"
        )
        assertEquals(36, payload["imageCount"])
        assertEquals(17, payload["skippedImages"])
        assertEquals(1, payload["failedImages"])
        assertEquals(48, payload["slideCount"])
        assertEquals(214, payload["blockCount"])
        assertEquals(emptyList<String>(), payload["warnings"])
    }

    @Test
    @DisplayName("the payload reports the deadline, skipped blocks and warnings of a partial deck")
    fun testPayloadReportsAPartialDeck() {
        val output = generationOutput(embedded = 0, skippedImages = 52, failed = 52, downloaded = 0).copy(
            skippedBlockCount = 2,
            skippedBlockKinds = mapOf("table" to 2),
            imageDownloadMillis = 60_000,
            imageDownloadDeadlineExceeded = true,
            warnings = listOf("Image download deadline (60s) reached"),
        )

        val payload = createExecutor().payload(output, 214, 40, 61_000, "https://example.com")

        assertEquals(true, payload["imageDownloadDeadlineExceeded"])
        assertEquals(60_000L, payload["imageDownloadMs"])
        assertEquals(2, payload["skippedBlocks"])
        assertEquals(mapOf("table" to 2), payload["skippedBlockKinds"])
        assertEquals(listOf("Image download deadline (60s) reached"), payload["warnings"])
    }

    @Test
    @DisplayName("an empty extraction reports an empty deck with the error and no warnings")
    fun testEmptyExtractionPayload() {
        val payload = createExecutor().payload(
            result = null,
            blockCount = 0,
            slideCount = 0,
            durationMs = 5,
            pageUrl = "https://example.com",
            error = "No content blocks extracted from page",
        )

        assertEquals("", payload["filePath"])
        assertEquals(0, payload["downloadedImages"])
        assertEquals(0, payload["skippedBlocks"])
        assertEquals(emptyMap<String, Int>(), payload["skippedBlockKinds"])
        assertEquals(emptyList<String>(), payload["warnings"])
        assertEquals("No content blocks extracted from page", payload["error"])
        assertEquals("https://example.com", payload["pageUrl"])
    }

    @Test
    @DisplayName("tool help documents the image counters and the partial-deck fields")
    fun testHelpDocumentsTheResultContract() {
        val help = createExecutor().generateHelpText()

        listOf(
            "downloadedImages",
            "skippedImages",
            "skippedBlocks",
            "skippedBlockKinds",
            "warnings",
            "pptx.download.total-timeout.seconds",
            "HTTPS_PROXY",
        ).forEach { token ->
            assertTrue(help.contains(token), "the tool help must document '$token'")
        }
    }
}
