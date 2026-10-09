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

import java.nio.file.Path
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

    /**
     * Check if the given URL likely points to an image file.
     */
    fun isImageUrl(url: String): Boolean {
        val path = url.lowercase().substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTENSIONS
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
     * Prefers the URL path filename, then falls back to a generated name from the URL hash.
     */
    fun suggestFilename(url: String, contentType: String? = null): String {
        val path = url.substringBefore('?').substringBefore('#')
        val rawName = path.substringAfterLast('/')

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
