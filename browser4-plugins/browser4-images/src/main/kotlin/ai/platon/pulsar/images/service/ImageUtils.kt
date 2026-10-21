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

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.*

/**
 * Utility functions for the browser4-images plugin.
 */
object ImageUtils {

    /** Known raster image file extensions */
    private val RASTER_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "ico",
        "tiff", "tif", "avif", "heic", "heif"
    )

    /** SVG extension (handled separately since it's XML, not raster) */
    private val VECTOR_EXTENSIONS = setOf("svg")

    /** All known image extensions */
    val IMAGE_EXTENSIONS: Set<String> = RASTER_EXTENSIONS + VECTOR_EXTENSIONS

    /** Common image MIME types */
    val IMAGE_MIME_TYPES: Set<String> = setOf(
        "image/jpeg", "image/png", "image/gif", "image/webp",
        "image/svg+xml", "image/bmp", "image/x-icon", "image/vnd.microsoft.icon",
        "image/tiff", "image/avif", "image/heic", "image/heif"
    )

    /** File extension → MIME type, used to derive a provisional type from a URL. */
    private val MIME_TYPE_BY_EXTENSION: Map<String, String> = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "svg" to "image/svg+xml",
        "bmp" to "image/bmp",
        "ico" to "image/x-icon",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "avif" to "image/avif",
        "heic" to "image/heic",
        "heif" to "image/heif",
    )

    /** Bytes needed to inspect any WebP variant's RIFF header. */
    private const val WEBP_HEADER_SIZE = 30

    /**
     * Check if the given URL likely points to an image file.
     */
    fun isImageUrl(url: String): Boolean {
        val path = url.lowercase().substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    /**
     * Guess the MIME type of an image URL: `data:` URIs report their own type,
     * everything else is derived from the path's file extension.
     *
     * The value is provisional — only the response `Content-Type` is
     * authoritative — but it makes the documented detection `type` field useful
     * for DOM-detected entries (`<img>`, CSS backgrounds, meta tags), which
     * declare no type attribute of their own.
     *
     * @return the MIME type, or null when the URL carries no usable hint
     */
    fun guessMimeType(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return null

        if (value.startsWith("data:", ignoreCase = true)) {
            val declared = value.substringAfter(':').substringBefore(',').substringBefore(';').trim().lowercase()
            return declared.takeIf { it.startsWith("image/") }
        }

        val path = value.lowercase().substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "")
        return MIME_TYPE_BY_EXTENSION[ext]
    }

    /**
     * Decode percent-escapes in a URL path segment, so a saved file is named
     * `250px-Flag_of_Switzerland_(Pantone).svg.webp` instead of
     * `250px-Flag_of_Switzerland_%28Pantone%29.svg.webp`.
     *
     * A literal `+` is preserved (it is a legal path character, not a space),
     * and a malformed escape leaves the segment untouched — decoding happens
     * before [sanitizeFilename], which then removes any path separator or `..`
     * a decoded value may introduce.
     */
    fun percentDecode(segment: String): String {
        if (!segment.contains('%')) return segment
        return try {
            URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            segment
        }
    }

    /**
     * Check if a MIME type indicates image content.
     */
    fun isImageMimeType(contentType: String?): Boolean {
        if (contentType == null) return false
        val normalized = contentType.lowercase().substringBefore(';').trim()
        return normalized in IMAGE_MIME_TYPES || normalized.startsWith("image/")
    }

    /**
     * Validate and normalize a URL string.
     * Returns the trimmed URL, or throws [IllegalArgumentException] if it's blank or has no scheme.
     */
    fun validateUrl(url: String): String {
        val trimmed = url.trim()
        require(trimmed.isNotBlank()) { "URL must not be blank" }
        require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            "URL must start with http:// or https://: $trimmed"
        }
        return trimmed
    }

    /**
     * Suggest a filename from a URL and optional Content-Type header.
     * Prefers the URL path filename (percent-decoded), then falls back to a
     * generated name from the URL hash.
     */
    fun suggestFilename(url: String, contentType: String? = null): String {
        val path = url.substringBefore('?').substringBefore('#')
        val rawName = percentDecode(path.substringAfterLast('/'))

        if (rawName.isNotBlank() && rawName.contains('.')) {
            val ext = rawName.substringAfterLast('.', "").lowercase()
            if (ext in IMAGE_EXTENSIONS) {
                return sanitizeFilename(rawName)
            }
            // Has an extension but it's not a known image extension — still use it
            return sanitizeFilename(rawName)
        }

        // Fallback: generate from URL hash + guessed extension
        val hash = url.hashCode().toString(16).take(8)
        val ext = guessExtension(contentType)
        return "image_${hash}.$ext"
    }

    /**
     * Guess a file extension from a MIME type.
     */
    fun guessExtension(contentType: String?): String {
        if (contentType == null) return "jpg"
        val normalized = contentType.lowercase().substringBefore(';').trim()
        return when {
            normalized.contains("jpeg") || normalized.contains("jpg") -> "jpg"
            normalized.contains("png") -> "png"
            normalized.contains("gif") -> "gif"
            normalized.contains("webp") -> "webp"
            normalized.contains("svg") -> "svg"
            normalized.contains("bmp") -> "bmp"
            normalized.contains("icon") -> "ico"
            normalized.contains("tiff") -> "tiff"
            normalized.contains("avif") -> "avif"
            normalized.contains("heic") -> "heic"
            normalized.contains("heif") -> "heif"
            normalized.startsWith("image/") -> "jpg"
            else -> "jpg"
        }
    }

    /**
     * Align a filename's extension with the actual Content-Type of the response.
     *
     * Servers may negotiate a different image format than the URL/filename suggests
     * (e.g. Wikimedia content negotiation returning WebP bytes for a .png URL).
     * When the Content-Type is a known image MIME type and the filename extension
     * disagrees with it, the extension is replaced so the saved filename reflects
     * the actual bytes. jpg/jpeg are treated as equivalent. Returns the filename
     * unchanged when the Content-Type is null/unknown, or the filename has no
     * extension to replace.
     */
    fun alignFilenameExtension(filename: String, contentType: String?): String {
        if (!isImageMimeType(contentType)) return filename
        val ext = guessExtension(contentType)
        val dotIndex = filename.lastIndexOf('.')
        if (dotIndex < 0) return "$filename.$ext"
        val currentExt = filename.substring(dotIndex + 1).lowercase()
        val equivalent = currentExt == ext ||
            (currentExt == "jpeg" && ext == "jpg") || (currentExt == "jpg" && ext == "jpeg")
        return if (equivalent) filename else filename.substring(0, dotIndex) + "." + ext
    }

    /**
     * Read the pixel dimensions of a saved image file without decoding its
     * pixels.
     *
     * Standard raster formats go through [ImageIO]; WebP — which Wikimedia and
     * many CDNs negotiate even for `.png`/`.jpg` URLs — has no ImageIO reader in
     * the JDK, so its RIFF header is parsed directly by [parseWebpDimensions].
     * Dimensions are best-effort metadata: an unreadable or unsupported file
     * yields null and never fails an otherwise successful download.
     *
     * @param path the saved image file
     * @return width to height, or null when the format cannot be inspected
     */
    fun readImageDimensions(path: Path): Pair<Int, Int>? {
        val fromImageIo = runCatching { readImageIoDimensions(path) }.getOrNull()
        if (fromImageIo != null) return fromImageIo
        return runCatching { readWebpDimensions(path) }.getOrNull()
    }

    /**
     * Parse a WebP RIFF header: `VP8X` (extended), `VP8L` (lossless) and
     * `VP8 ` (lossy). Anything else — including a truncated header — is null.
     *
     * @param header the first bytes of the file (at least [WEBP_HEADER_SIZE])
     * @param length the number of valid bytes in [header]
     */
    fun parseWebpDimensions(header: ByteArray, length: Int = header.size): Pair<Int, Int>? {
        if (length < WEBP_HEADER_SIZE || header.size < WEBP_HEADER_SIZE) return null
        if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        if (String(header, 8, 4, Charsets.US_ASCII) != "WEBP") return null

        fun byteAt(index: Int) = header[index].toInt() and 0xFF

        return when (String(header, 12, 4, Charsets.US_ASCII)) {
            "VP8X" -> {
                val width = 1 + (byteAt(24) or (byteAt(25) shl 8) or (byteAt(26) shl 16))
                val height = 1 + (byteAt(27) or (byteAt(28) shl 8) or (byteAt(29) shl 16))
                width to height
            }
            // 1-byte signature, then 14 bits of width-1 and 14 bits of height-1
            "VP8L" -> {
                if (byteAt(20) != 0x2F) return null
                val bits = byteAt(21) or (byteAt(22) shl 8) or (byteAt(23) shl 16) or (byteAt(24) shl 24)
                ((bits and 0x3FFF) + 1) to (((bits ushr 14) and 0x3FFF) + 1)
            }
            // frame tag (3 bytes) + start code 0x9D 0x01 0x2A, then 14-bit dimensions
            "VP8 " -> {
                if (byteAt(23) != 0x9D || byteAt(24) != 0x01 || byteAt(25) != 0x2A) return null
                val width = (byteAt(26) or (byteAt(27) shl 8)) and 0x3FFF
                val height = (byteAt(28) or (byteAt(29) shl 8)) and 0x3FFF
                if (width <= 0 || height <= 0) null else width to height
            }
            else -> null
        }
    }

    private fun readImageIoDimensions(path: Path): Pair<Int, Int>? {
        val stream = ImageIO.createImageInputStream(path.toFile()) ?: return null
        return stream.use { input ->
            val readers = ImageIO.getImageReaders(input)
            if (!readers.hasNext()) return@use null
            val reader = readers.next()
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width > 0 && height > 0) width to height else null
            } finally {
                reader.dispose()
            }
        }
    }

    private fun readWebpDimensions(path: Path): Pair<Int, Int>? {
        val header = ByteArray(WEBP_HEADER_SIZE)
        val length = Files.newInputStream(path).use { input ->
            var read = 0
            while (read < header.size) {
                val count = input.read(header, read, header.size - read)
                if (count < 0) break
                read += count
            }
            read
        }
        return parseWebpDimensions(header, length)
    }

    /**
     * A short human-readable reason phrase for common HTTP status codes.
     * HTTP/2 responses carry no reason phrase, so OkHttp's Response.message()
     * is often empty — this keeps error messages complete.
     */
    fun defaultHttpReason(code: Int): String = when (code) {
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        410 -> "Gone"
        429 -> "Too Many Requests"
        in 500..599 -> "Server Error"
        else -> ""
    }

    /**
     * Sanitize a filename to prevent path traversal and illegal characters.
     */
    fun sanitizeFilename(name: String): String {
        return name
            .replace(Regex("""[<>:"/\\|?*\x00-\x1f]"""), "_")
            .replace(Regex("""\.{2,}"""), "_")
            .trim()
            .take(255)
    }

    /**
     * Validate that a resolved path is within the allowed base directory.
     * Throws [IllegalArgumentException] if the path escapes the base directory.
     */
    fun requirePathWithinBase(baseDir: Path, target: Path) {
        val resolved = baseDir.resolve(target).normalize().toAbsolutePath()
        val base = baseDir.normalize().toAbsolutePath()
        require(resolved.startsWith(base)) {
            "Path traversal detected: $target is outside of $base"
        }
    }

    /**
     * Format file size for human-readable display.
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
            else -> "${"%.2f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
        }
    }

    /**
     * Check if a URL is a data URI.
     */
    fun isDataUri(url: String): Boolean {
        return url.trimStart().startsWith("data:", ignoreCase = true)
    }

    /**
     * Check if a URL or type indicates an SVG image.
     */
    fun isSvg(url: String, contentType: String? = null): Boolean {
        val path = url.lowercase().substringBefore('?').substringBefore('#')
        if (path.endsWith(".svg")) return true
        return contentType?.lowercase()?.startsWith("image/svg") == true
    }
}
