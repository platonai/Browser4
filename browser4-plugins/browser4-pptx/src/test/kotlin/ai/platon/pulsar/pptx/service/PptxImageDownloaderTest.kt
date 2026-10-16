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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [PptxImageDownloader] deadline and accounting behaviour.
 *
 * Every test replaces [PptxImageDownloader.attemptDownload], so nothing here
 * touches the network.
 */
class PptxImageDownloaderTest {

    private class FakeDownloader(
        config: PptxConfig,
        private val handler: suspend (String) -> PptxImageDownloader.ImageDownloadAttempt,
    ) : PptxImageDownloader(config, OkHttpClient()) {
        override suspend fun attemptDownload(url: String): PptxImageDownloader.ImageDownloadAttempt =
            handler(url)
    }

    private fun imageBlocks(count: Int): List<ContentBlock> =
        (1..count).map { ContentBlock(type = "image", src = "https://example.com/image$it.png") }

    @Test
    @DisplayName("downloadImages stops at the phase deadline and reports a partial result")
    fun testDownloadImagesStopsAtThePhaseDeadline() = runBlocking {
        val config = PptxConfig(
            concurrentDownloads = 1,
            downloadTimeoutSeconds = 30,
            downloadTotalTimeoutSeconds = 1,
        )
        val downloader = FakeDownloader(config) {
            delay(15_000)
            PptxImageDownloader.ImageDownloadAttempt(ByteArray(4))
        }

        val startedAt = System.currentTimeMillis()
        val result = downloader.downloadImages(imageBlocks(4))
        val elapsed = System.currentTimeMillis() - startedAt

        assertTrue(elapsed < 5_000, "the phase deadline must cut the download short, took ${elapsed}ms")
        assertEquals(4, result.total)
        assertEquals(0, result.downloaded)
        assertEquals(4, result.aborted, "images never attempted before the deadline are reported as aborted")
        assertTrue(result.deadlineExceeded)
        assertTrue(result.partial)
        assertTrue(
            downloader.describeShortfall(result).any { it.contains("deadline") },
            "the deadline must be named in the diagnostic"
        )
    }

    @Test
    @DisplayName("downloadImages keeps the images fetched before the phase deadline")
    fun testDownloadImagesKeepsImagesFetchedBeforeTheDeadline() = runBlocking {
        val config = PptxConfig(
            concurrentDownloads = 1,
            downloadTimeoutSeconds = 30,
            downloadTotalTimeoutSeconds = 1,
        )
        val downloader = FakeDownloader(config) { url ->
            if (url.endsWith("image1.png")) {
                PptxImageDownloader.ImageDownloadAttempt(ByteArray(8))
            } else {
                delay(15_000)
                PptxImageDownloader.ImageDownloadAttempt(ByteArray(8))
            }
        }

        val result = downloader.downloadImages(imageBlocks(4))

        assertEquals(1, result.downloaded, "the image downloaded before the deadline must be returned")
        assertEquals(3, result.aborted)
        assertTrue(result.bytes.containsKey("https://example.com/image1.png"))
        assertTrue(result.deadlineExceeded)
    }

    @Test
    @DisplayName("a single slow image hits the per-image timeout without ending the phase")
    fun testPerImageTimeoutIsReported() = runBlocking {
        val config = PptxConfig(
            concurrentDownloads = 2,
            downloadTimeoutSeconds = 1,
            downloadTotalTimeoutSeconds = 30,
        )
        val downloader = FakeDownloader(config) {
            delay(10_000)
            PptxImageDownloader.ImageDownloadAttempt(ByteArray(4))
        }

        val result = downloader.downloadImages(imageBlocks(2))

        assertEquals(0, result.downloaded)
        assertEquals(2, result.failed)
        assertEquals(0, result.aborted, "the phase deadline was not reached")
        assertFalse(result.deadlineExceeded)
        assertEquals(
            PptxImageDownloader.REASON_TIMEOUT, result.failureReasons.keys.single(),
            "the timeout must be reported as the failure reason: ${result.failureReasons}"
        )
    }

    @Test
    @DisplayName("failed, skipped and downloaded images are counted separately")
    fun testDownloadedFailedAndSkippedAreCountedSeparately() = runBlocking {
        val downloader = FakeDownloader(PptxConfig()) { url ->
            when {
                url.contains("svg") -> PptxImageDownloader.ImageDownloadAttempt(
                    null, "SVG cannot be embedded", skipped = true
                )
                url.contains("broken") -> PptxImageDownloader.ImageDownloadAttempt(
                    null, "ConnectException: Connection refused"
                )
                else -> PptxImageDownloader.ImageDownloadAttempt(ByteArray(4))
            }
        }
        val blocks = listOf(
            ContentBlock(type = "image", src = "https://example.com/ok.png"),
            ContentBlock(type = "image", src = "https://example.com/icon.svg"),
            ContentBlock(type = "image", src = "https://example.com/broken.png"),
        )

        val result = downloader.downloadImages(blocks)

        assertEquals(3, result.total)
        assertEquals(1, result.downloaded)
        assertEquals(1, result.failed)
        assertEquals(1, result.skipped)
        assertEquals(1, result.failureReasons["ConnectException: Connection refused"])
        assertEquals(0, result.aborted)

        val warnings = downloader.describeShortfall(result)
        assertTrue(
            warnings.any { it.contains("1/3 failed") },
            "the failing image must be named: $warnings"
        )
        assertTrue(warnings.any { it.contains("skipped by configuration") })
    }

    @Test
    @DisplayName("describeShortfall is empty when every image was downloaded")
    fun testDescribeShortfallIsEmptyForACompleteDownload() {
        val downloader = FakeDownloader(PptxConfig()) { PptxImageDownloader.ImageDownloadAttempt(ByteArray(2)) }
        val complete = PptxImageDownloader.DownloadResult(
            bytes = mapOf("https://example.com/a.png" to ByteArray(2)),
            total = 1,
            failed = 0,
        )

        assertTrue(downloader.describeShortfall(complete).isEmpty())
        assertFalse(complete.partial)
    }

    @Test
    @DisplayName("downloadImages skips the download phase for blocks without images")
    fun testDownloadImagesWithoutImageBlocks() = runBlocking {
        val downloader = FakeDownloader(PptxConfig()) { url ->
            throw IllegalStateException("no image URL may be attempted, got $url")
        }

        val result = downloader.downloadImages(
            listOf(ContentBlock(type = "paragraph", text = "no images here"))
        )

        assertEquals(0, result.total)
        assertEquals(0, result.downloaded)
        assertFalse(result.partial)
    }
}
