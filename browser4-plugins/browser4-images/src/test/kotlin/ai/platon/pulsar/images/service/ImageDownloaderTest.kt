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
package ai.platon.pulsar.images.service

import ai.platon.pulsar.images.config.ImageConfig
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Tests for [ImageDownloader] against a loopback stub server (no external
 * network, no browser): saved filenames, decoded dimensions, and the fast,
 * actionable transport failures.
 */
class ImageDownloaderTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService
    private var port: Int = 0

    private val stub = ConcurrentHashMap<String, StubResponse>()

    private class StubResponse(val body: ByteArray, val contentType: String, val delayMs: Long)

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/") { exchange ->
            // URI.path is decoded, so a stub registered for
            // "250px-Flag_(Pantone).svg.png" answers the %28-escaped request too.
            val response = stub[exchange.requestURI.path]
            try {
                if (response == null) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    if (response.delayMs > 0) Thread.sleep(response.delayMs)
                    exchange.responseHeaders.add("Content-Type", response.contentType)
                    exchange.sendResponseHeaders(200, response.body.size.toLong())
                    exchange.responseBody.use { it.write(response.body) }
                }
            } catch (e: Exception) {
                // the client may have aborted (timeout test) — nothing to do
            } finally {
                exchange.close()
            }
        }
        server.start()
        port = server.address.port
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    // ---- filenames ----

    @Test
    @DisplayName("download percent-decodes the saved filename")
    fun testDownloadPercentDecodesFilename() = runBlocking {
        respondWithPng("/250px-Flag_of_Switzerland_(Pantone).svg.png", 8, 6)
        val downloader = downloader(ImageConfig(downloadDir = tempDir.toString()))

        val result = downloader.download(
            "http://127.0.0.1:$port/250px-Flag_of_Switzerland_%28Pantone%29.svg.png"
        )

        assertTrue(result.success, result.error)
        assertEquals("250px-Flag_of_Switzerland_(Pantone).svg.png", Path.of(result.filePath).fileName.toString())
        assertTrue(Files.exists(Path.of(result.filePath)))
    }

    @Test
    @DisplayName("download percent-decodes an en-dash escape in the saved filename")
    fun testDownloadPercentDecodesEnDash() = runBlocking {
        respondWithPng("/Flag_of_Vatican_City_(2023\u2013present).svg.png", 4, 4)
        val downloader = downloader(ImageConfig(downloadDir = tempDir.toString()))

        val result = downloader.download(
            "http://127.0.0.1:$port/Flag_of_Vatican_City_%282023%E2%80%93present%29.svg.png"
        )

        assertTrue(result.success, result.error)
        assertEquals(
            "Flag_of_Vatican_City_(2023\u2013present).svg.png",
            Path.of(result.filePath).fileName.toString()
        )
    }

    // ---- dimensions ----

    @Test
    @DisplayName("download decodes width and height from the saved bytes")
    fun testDownloadDecodesDimensionsFromBytes() = runBlocking {
        respond("/photo.png", pngBytes(12, 9), "image/png")
        val downloader = downloader(ImageConfig(downloadDir = tempDir.toString()))

        val result = downloader.download("http://127.0.0.1:$port/photo.png")

        assertTrue(result.success, result.error)
        assertEquals(12, result.width)
        assertEquals(9, result.height)
    }

    @Test
    @DisplayName("download decodes dimensions of a WebP that ImageIO cannot read")
    fun testDownloadDecodesWebpDimensions() = runBlocking {
        // content negotiation: a .png URL answered with WebP bytes
        respond("/flag.svg.png", webpLossless(250, 305), "image/webp")
        val downloader = downloader(ImageConfig(downloadDir = tempDir.toString()))

        val result = downloader.download("http://127.0.0.1:$port/flag.svg.png")

        assertTrue(result.success, result.error)
        assertEquals("flag.svg.webp", Path.of(result.filePath).fileName.toString())
        assertEquals(250, result.width)
        assertEquals(305, result.height)
    }

    // ---- failures ----

    @Test
    @DisplayName("an unreachable host fails fast with the proxy hint")
    fun testConnectionFailureIsFastAndActionable() = runBlocking {
        val closedPort = closedLoopbackPort()
        val config = ImageConfig(downloadDir = tempDir.toString(), downloadTimeoutSeconds = 5)
        val downloader = downloader(config)

        val result = downloader.download("http://127.0.0.1:$closedPort/photo.png")

        assertFalse(result.success)
        val error = result.error.orEmpty()
        assertTrue(error.contains("Network error"), error)
        assertTrue(error.contains("connection refused"), error)
        assertTrue(error.contains("image.download.proxy"), error)
        assertTrue(error.contains("connect timeout 5s"), error)
        // the transport's own message is preserved, not swallowed by the wrapper
        assertTrue(error.contains("Root cause"), error)
        // an immediate refusal, never the caller's 30s client-side timeout
        assertTrue(result.durationMs < 5_000, "took ${result.durationMs}ms")
    }

    @Test
    @DisplayName("a stalled response fails inside the plugin's own timeout budget")
    fun testStalledResponseFailsWithinTheBudget() = runBlocking {
        respond("/slow.png", pngBytes(4, 4), "image/png", delayMs = 3_000)
        val config = ImageConfig(downloadDir = tempDir.toString(), downloadTimeoutSeconds = 1)
        val downloader = downloader(config)

        val started = System.currentTimeMillis()
        val result = downloader.download("http://127.0.0.1:$port/slow.png")
        val elapsed = System.currentTimeMillis() - started

        assertFalse(result.success)
        val error = result.error.orEmpty()
        assertTrue(error.contains("timed out"), error)
        assertTrue(error.contains("per-download budget 1s"), error)
        assertTrue(error.contains("image.download.proxy"), error)
        assertTrue(elapsed < 2_500, "the plugin budget must fire before the server answers (took ${elapsed}ms)")
        assertNull(result.width)
    }

    @Test
    @DisplayName("a non-image response is rejected instead of being saved")
    fun testNonImageResponseIsRejected() = runBlocking {
        respond("/page.html", "<html><body>hello</body></html>".toByteArray(), "text/html; charset=utf-8")
        val downloader = downloader(ImageConfig(downloadDir = tempDir.toString()))

        val result = downloader.download("http://127.0.0.1:$port/page.html")

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("Not an image"), result.error)
        assertEquals(0L, Files.list(tempDir).use { it.count() })
    }

    // ---- helpers ----

    private fun downloader(config: ImageConfig): ImageDownloader {
        val client = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.downloadTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(config.downloadTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(config.downloadTimeoutSeconds, TimeUnit.SECONDS)
            .build()
        return ImageDownloader(config, client)
    }

    /**
     * Serve [path] with [body]. The path is registered **decoded** — the server
     * decodes the request target before matching, so a `%28`-escaped request
     * finds the `(` stub.
     */
    private fun respond(path: String, body: ByteArray, contentType: String, delayMs: Long = 0) {
        stub[path] = StubResponse(body, contentType, delayMs)
    }

    private fun respondWithPng(path: String, width: Int, height: Int) =
        respond(path, pngBytes(width, height), "image/png")

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    /** `VP8L` lossless WebP header advertising [width] x [height]. */
    private fun webpLossless(width: Int, height: Int): ByteArray {
        val header = ByteArray(30)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        "WEBP".toByteArray(Charsets.US_ASCII).copyInto(header, 8)
        "VP8L".toByteArray(Charsets.US_ASCII).copyInto(header, 12)
        header[20] = 0x2F
        val bits = (width - 1) or ((height - 1) shl 14)
        header[21] = (bits and 0xFF).toByte()
        header[22] = ((bits ushr 8) and 0xFF).toByte()
        header[23] = ((bits ushr 16) and 0xFF).toByte()
        header[24] = ((bits ushr 24) and 0xFF).toByte()
        return header
    }

    /** A loopback port nothing listens on — connecting is refused immediately. */
    private fun closedLoopbackPort(): Int =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
}
