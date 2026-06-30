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
package ai.platon.pulsar.media.service

import java.nio.file.Path
import kotlin.io.path.*

/**
 * Utility functions for the browser4-media plugin.
 */
object MediaUtils {

    /** Known video file extensions */
    private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "avi", "flv", "wmv", "ts", "m3u8", "mpd")

    /** Known audio file extensions */
    private val AUDIO_EXTENSIONS = setOf("mp3", "aac", "ogg", "wav", "flac", "m4a", "opus", "wma")

    /** Common video MIME types */
    private val VIDEO_MIME_TYPES = setOf(
        "video/mp4", "video/webm", "video/x-matroska", "video/quicktime",
        "video/x-msvideo", "video/x-flv", "video/x-ms-wmv",
        "application/vnd.apple.mpegurl", "application/x-mpegurl", // HLS
        "application/dash+xml", // DASH
        "video/MP2T", // MPEG-TS
    )

    /**
     * Validate and normalize a URL string.
     * Returns the trimmed URL, or throws [IllegalArgumentException] if it's blank.
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
     * Check if the given URL likely points to a media file.
     */
    fun isMediaUrl(url: String): Boolean {
        val path = url.lowercase().substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS || ext in AUDIO_EXTENSIONS
    }

    /**
     * Check if the given URL likely points to a video file.
     */
    fun isVideoUrl(url: String): Boolean {
        val path = url.lowercase().substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    /**
     * Check if a MIME type indicates video content.
     */
    fun isVideoMimeType(contentType: String?): Boolean {
        if (contentType == null) return false
        val normalized = contentType.lowercase().substringBefore(';').trim()
        return normalized in VIDEO_MIME_TYPES || normalized.startsWith("video/")
    }

    /**
     * Suggest a filename from a URL and optional Content-Type header.
     * Prefers Content-Disposition filename, then falls back to URL path.
     */
    fun suggestFilename(url: String, contentType: String? = null): String {
        // Extract from URL path
        val path = url.substringBefore('?').substringBefore('#')
        val rawName = path.substringAfterLast('/')

        if (rawName.isNotBlank() && rawName.contains('.')) {
            return sanitizeFilename(rawName)
        }

        // Fallback: generate from URL hash + guessed extension
        val hash = url.hashCode().toString(16).take(8)
        val ext = guessExtension(contentType)
        return "video_${hash}.$ext"
    }

    /**
     * Guess a file extension from a MIME type.
     */
    fun guessExtension(contentType: String?): String {
        if (contentType == null) return "mp4"
        val normalized = contentType.lowercase().substringBefore(';').trim()
        return when {
            normalized.contains("mp4") || normalized.contains("mpeg4") -> "mp4"
            normalized.contains("webm") -> "webm"
            normalized.contains("matroska") || normalized.contains("x-matroska") -> "mkv"
            normalized.contains("quicktime") -> "mov"
            normalized.contains("mpegurl") || normalized.contains("m3u8") -> "m3u8"
            normalized.contains("mp2t") -> "ts"
            normalized.contains("mpeg") || normalized.contains("video/") -> "mp4"
            normalized.contains("audio/mpeg") || normalized.contains("mp3") -> "mp3"
            normalized.contains("aac") -> "aac"
            normalized.contains("ogg") -> "ogg"
            normalized.contains("wav") -> "wav"
            else -> "mp4"
        }
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
     * Throws [SecurityException] if the path escapes the base directory.
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
}
