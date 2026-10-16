package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass

/**
 * `ToolSpec.help` is authored by plugin authors and conventionally repeats the
 * callable signature as its first line, which `renderHelp` has already emitted
 * from `spec.expression`. Rendering both printed the signature twice in the help
 * block of every plugin's usage error and in the generated documentation.
 */
@DisplayName("Tool help signature rendering")
class ToolHelpSignatureDedupTest {

    /**
     * Minimal executor: enough to reach the protected [AbstractToolExecutor.renderHelp].
     */
    private class ProbeExecutor(spec: ToolSpec) : AbstractToolExecutor() {
        override val domain: String = spec.domain
        override val receiverClass: KClass<*> = Any::class

        init {
            toolSpec[spec.method] = spec
        }

        override suspend fun callFunctionOn(
            domain: String,
            functionName: String,
            args: Map<String, Any?>,
            receiver: Any,
        ): Any? = null

        fun renderedHelp(method: String): String = help(method)
    }

    private fun getInfoSpec() = ToolSpec(
        domain = "media",
        method = "getInfo",
        arguments = listOf(ToolSpec.Arg("filePath", "String")),
        returnType = "ProbeResult",
        description = "Get media file metadata (duration, codec, resolution, bitrate, streams) using ffprobe.",
        help = """
            media.getInfo(filePath: String)

            Returns detailed information about a media file: format, duration, resolution, codec, bitrate, and all streams.
        """.trimIndent(),
    )

    @Test
    @DisplayName("a help block that repeats the signature renders the signature exactly once")
    fun repeatedSignatureIsRenderedOnce() {
        val help = ProbeExecutor(getInfoSpec()).renderedHelp("getInfo")

        val signature = "media.getInfo(filePath: String)"
        assertEquals(
            1,
            help.windowed(signature.length).count { it == signature },
            "the signature must appear exactly once, got:\n$help",
        )
        // The authored prose below the signature is untouched.
        assertTrue(help.contains("Returns detailed information about a media file"), help)
        assertTrue(help.contains("Get media file metadata (duration, codec, resolution, bitrate, streams)"), help)
    }

    @Test
    @DisplayName("a help block whose first line is a different overload keeps every line")
    fun differentOpeningLineIsKept() {
        // media.compress advertises the two-argument overload; its help documents
        // both forms, so the first line is real documentation, not a duplicate.
        val spec = ToolSpec(
            domain = "media",
            method = "compress",
            arguments = listOf(ToolSpec.Arg("inputPath", "String"), ToolSpec.Arg("outputPath", "String")),
            description = "Compress/re-encode a video using H.264 with configurable CRF.",
            help = """
                media.compress(inputPath: String, outputPath: String)
                media.compress(inputPath: String, outputPath: String, crf: Int? = 23)

                Re-encodes with H.264. CRF guide: 18=visually lossless, 23=good default.
            """.trimIndent(),
        )

        val help = ProbeExecutor(spec).renderedHelp("compress")

        assertTrue(help.contains("crf: Int? = 23"), "the overload documentation must survive:\n$help")
        assertTrue(help.contains("CRF guide"), help)
        assertEquals(
            1,
            help.windowed("media.compress(inputPath: String, outputPath: String)".length)
                .count { it == "media.compress(inputPath: String, outputPath: String)" },
            "the advertised signature must still appear exactly once:\n$help",
        )
    }

    @Test
    @DisplayName("a help block that only repeats the signature is dropped entirely")
    fun signatureOnlyHelpIsDropped() {
        val spec = ToolSpec(
            domain = "media",
            method = "getInfo",
            arguments = listOf(ToolSpec.Arg("filePath", "String")),
            description = "Get media file metadata using ffprobe.",
            help = "media.getInfo(filePath: String)",
        )

        val help = ProbeExecutor(spec).renderedHelp("getInfo")

        assertEquals("Get media file metadata using ffprobe.\nmedia.getInfo(filePath: String)", help)
    }

    @Test
    @DisplayName("a help block that repeats the description is still dropped")
    fun descriptionOnlyHelpIsDropped() {
        val spec = ToolSpec(
            domain = "media",
            method = "getInfo",
            arguments = listOf(ToolSpec.Arg("filePath", "String")),
            description = "Get media file metadata using ffprobe.",
            help = "Get media file metadata using ffprobe.",
        )

        val help = ProbeExecutor(spec).renderedHelp("getInfo")

        assertEquals("Get media file metadata using ffprobe.\nmedia.getInfo(filePath: String)", help)
    }

    @Test
    @DisplayName("stripAlreadyRenderedHeader removes only an identical opening line")
    fun stripRemovesOnlyIdenticalOpeningLine() {
        assertEquals(
            "Returns detailed information.",
            stripAlreadyRenderedHeader(
                "media.getInfo(filePath: String)\n\nReturns detailed information.",
                "media.getInfo(filePath: String)",
                null,
            ),
        )
        // Leading blank lines are skipped when looking for the duplicated header.
        assertEquals(
            "Returns detailed information.",
            stripAlreadyRenderedHeader(
                "\n\nmedia.getInfo(filePath: String)\nReturns detailed information.",
                "media.getInfo(filePath: String)",
            ),
        )
        // A different opening line is documentation.
        assertEquals(
            "media.trim(inputPath: String, startTime: String)\nClip.",
            stripAlreadyRenderedHeader(
                "media.trim(inputPath: String, startTime: String)\nClip.",
                "media.getInfo(filePath: String)",
            ),
        )
        // Nothing to strip.
        assertEquals("", stripAlreadyRenderedHeader("media.getInfo(filePath: String)", "media.getInfo(filePath: String)"))
        assertFalse(stripAlreadyRenderedHeader("Returns.", "other()").isBlank())
    }
}
