package ai.platon.pulsar.skeleton.workflow.common

import org.apache.tika.Tika
import org.apache.tika.mime.MimeTypes

class MimeTypeResolver {
    val tika: Tika = Tika()

    fun resolveMimeType(contentType: String?, url: String, data: ByteArray?): String? {
        // Avoid calling magic detection when there is no content.
        if (data != null && data.isNotEmpty()) {
            return tika.detect(data)
        }

        val cleaned = contentType
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        if (cleaned != null) {
            return cleaned
        }

        // Fall back to URL/extension-based detection.
        return tika.detect(url).takeIf { it.isNotBlank() } ?: MimeTypes.OCTET_STREAM
    }

    companion object {
        const val SEPARATOR: String = ";"

        fun cleanMimeType(origType: String): String? {
            // take the origType and split it on ';'
            val tokenizedMimeType: Array<String?> = origType.split(SEPARATOR.toRegex())
                .dropLastWhile { it.isEmpty() }.toTypedArray()
            return if (tokenizedMimeType.size > 1) {
                // there was a ';' in there, take the first value
                tokenizedMimeType[0]
            } else {
                // there wasn't a ';', so just return the orig type
                origType
            }
        }
    }
}
