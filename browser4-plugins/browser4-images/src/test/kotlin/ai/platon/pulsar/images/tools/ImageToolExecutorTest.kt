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
package ai.platon.pulsar.images.tools

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.tools.ToolResultTextRenderer
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.images.config.ImageConfig
import ai.platon.pulsar.images.service.ImageDetector
import ai.platon.pulsar.images.service.ImageDownloader
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Tests for [ImageToolExecutor]: the JSON-friendly result shape a caller
 * receives, the filter counts, and the dimension-basis argument.
 *
 * Detection is stubbed and downloads go to a loopback server, so the tool path
 * runs end to end without a browser or an external network.
 */
class ImageToolExecutorTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService
    private var port: Int = 0
    private val stub = ConcurrentHashMap<String, Pair<ByteArray, String>>()

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/") { exchange ->
            val response = stub[exchange.requestURI.path]
            try {
                if (response == null) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    exchange.responseHeaders.add("Content-Type", response.second)
                    exchange.sendResponseHeaders(200, response.first.size.toLong())
                    exchange.responseBody.use { it.write(response.first) }
                }
            } catch (e: Exception) {
                // client aborted — nothing to do
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

    // ---- #5: structured results ----

    @Test
    @DisplayName("image.download returns a structured JSON object, not a toString() wrapper")
    fun testDownloadReturnsStructuredJson() = runBlocking {
        respond("/photo.png", pngBytes(12, 9), "image/png")
        val tool = tool(emptyList())

        val evaluate = tool.callFunctionOn(
            ToolCall("image", "download", args("url" to "http://127.0.0.1:$port/photo.png")),
            Any(),
        )

        assertTrue(evaluate.value is Map<*, *>, "a Map reaches the renderer as JSON; anything else becomes a toString wrapper")
        val json = pulsarObjectMapper().readTree(ToolResultTextRenderer.render(evaluate))
        assertEquals("http://127.0.0.1:$port/photo.png", json["url"].asText())
        assertTrue(json["success"].asBoolean(), json.toString())
        assertTrue(json["filePath"].asText().endsWith("photo.png"))
        assertTrue(json["bytesDownloaded"].asLong() > 0)
        // #9: dimensions are decoded from the downloaded bytes
        assertEquals(12, json["width"].asInt())
        assertEquals(9, json["height"].asInt())
        // the wrapper the issue reported
        assertFalse(json.has("description"), json.toString())
        assertFalse(json.has("type"), json.toString())
    }

    @Test
    @DisplayName("image.downloadBatch returns a structured JSON summary too")
    fun testDownloadBatchReturnsStructuredJson() = runBlocking {
        respond("/a.png", pngBytes(3, 3), "image/png")
        val tool = tool(emptyList())

        val evaluate = tool.callFunctionOn(
            ToolCall(
                "image", "downloadBatch",
                args("urls" to listOf("http://127.0.0.1:$port/a.png"), "outputPath" to tempDir.toString())
            ),
            Any(),
        )

        val json = pulsarObjectMapper().readTree(ToolResultTextRenderer.render(evaluate))
        assertEquals(1, json["totalAttempted"].asInt())
        assertEquals(1, json["successful"].asInt())
        assertEquals(0, json["filteredOut"].asInt())
        assertEquals(3, json["results"][0]["width"].asInt())
    }

    // ---- #4: filter counts and dimension-less sources ----

    @Test
    @DisplayName("downloadAll reports detected/attempted/filtered counts and keeps dimension-less sources")
    fun testDownloadAllReportsFilterCounts() = runBlocking {
        respond("/big.png", pngBytes(250, 167), "image/png")
        respond("/icon.png", pngBytes(32, 32), "image/png")
        respond("/og.png", pngBytes(1280, 720), "image/png")
        val sources = listOf(
            imageSource("/big.png", width = 250, height = 167),
            imageSource("/icon.png", width = 32, height = 32),
            // the page's biggest image: an OG meta entry with no dimensions at all
            imageSource("/og.png", tagName = "meta", alt = "og:image"),
        )
        val tool = tool(sources)

        val evaluate = tool.callFunctionOn(
            ToolCall(
                "image", "downloadAll",
                args(
                    "minWidth" to 100, "minHeight" to 60,
                    "outputPath" to tempDir.toString(),
                )
            ),
            stubDriver(),
        )

        val json = pulsarObjectMapper().readTree(ToolResultTextRenderer.render(evaluate))
        assertEquals(3, json["totalDetected"].asInt(), json.toString())
        assertEquals(1, json["filteredOut"].asInt(), json.toString())
        assertEquals(2, json["totalAttempted"].asInt(), json.toString())
        assertEquals(2, json["successful"].asInt(), json.toString())

        val urls = json["results"].map { it["url"].asText() }
        assertTrue(urls.any { it.endsWith("/og.png") }, "the dimension-less og:image must still be downloaded: $urls")
        assertTrue(urls.none { it.endsWith("/icon.png") }, "the known 32x32 icon must be filtered out: $urls")
    }

    // ---- #7: one dimension basis per call ----

    @Test
    @DisplayName("detectImages filters rendered sizes by default and intrinsic sizes on request")
    fun testDetectImagesDimensionBasis() = runBlocking {
        // rendered at 50x50, source file is 150x150
        val source = imageSource("/enwiki-25.svg", width = 50, height = 50, naturalWidth = 150, naturalHeight = 150)
        val tool = tool(listOf(source))

        val rendered = tool.callFunctionOn("image", "detectImages", args("minWidth" to 100), stubDriver()) as List<*>
        assertTrue(rendered.isEmpty(), "the rendered 50px logo must not pass minWidth=100 by default")

        val natural = tool.callFunctionOn(
            "image", "detectImages",
            args("minWidth" to 100, "dimensionBasis" to "natural"),
            stubDriver(),
        ) as List<*>
        assertEquals(1, natural.size, "dimensionBasis=natural compares the 150px intrinsic size")
    }

    @Test
    @DisplayName("an unknown dimensionBasis value fails loudly instead of guessing")
    fun testUnknownDimensionBasisIsRejected() {
        val tool = tool(emptyList())

        val exception = assertThrows<IllegalArgumentException> {
            runBlocking {
                tool.callFunctionOn(
                    "image", "downloadAll",
                    args("dimensionBasis" to "diagonal"),
                    stubDriver(),
                )
            }
        }

        assertTrue(exception.message.orEmpty().contains("rendered|natural"), exception.message)
    }

    // ---- helpers ----

    private fun tool(sources: List<ImageDetector.ImageSource>): ImageToolExecutor {
        val config = ImageConfig(downloadDir = tempDir.toString(), downloadTimeoutSeconds = 5)
        val client = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.downloadTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(config.downloadTimeoutSeconds, TimeUnit.SECONDS)
            .build()
        return ImageToolExecutor(
            StubDetector(sources),
            ImageDownloader(config, client),
            config,
        )
    }

    /** A detector that returns a fixed list — detection itself is covered elsewhere. */
    private class StubDetector(
        private val sources: List<ImageDetector.ImageSource>,
    ) : ImageDetector(ImageConfig()) {
        override suspend fun detect(driver: WebDriver): List<ImageDetector.ImageSource> = sources
    }

    /** A WebDriver that is never called: the stub detector provides the sources. */
    private fun stubDriver(): WebDriver = Proxy.newProxyInstance(
        WebDriver::class.java.classLoader,
        arrayOf(WebDriver::class.java),
    ) { _, method, _ -> defaultValue(method.returnType) } as WebDriver

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> ' '
        else -> null
    }

    private fun imageSource(
        path: String,
        tagName: String = "img",
        width: Int? = null,
        height: Int? = null,
        naturalWidth: Int? = null,
        naturalHeight: Int? = null,
        alt: String? = null,
    ) = ImageDetector.ImageSource(
        tagName = tagName,
        srcUrl = path,
        resolvedUrl = "http://127.0.0.1:$port$path",
        width = width,
        height = height,
        naturalWidth = naturalWidth,
        naturalHeight = naturalHeight,
        alt = alt,
    )

    private fun args(vararg pairs: Pair<String, Any?>): MutableMap<String, Any?> = mutableMapOf(*pairs)

    private fun respond(path: String, body: ByteArray, contentType: String) {
        stub[path] = body to contentType
    }

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }
}
