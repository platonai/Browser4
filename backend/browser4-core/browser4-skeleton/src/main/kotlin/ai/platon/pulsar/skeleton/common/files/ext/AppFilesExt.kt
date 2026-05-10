package ai.platon.pulsar.skeleton.common.files.ext

import ai.platon.pulsar.common.AppFiles
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.AppPaths.WEB_CACHE_DIR
import ai.platon.pulsar.dom.Documents
import ai.platon.pulsar.persist.AbstractWebPage
import ai.platon.pulsar.persist.ProtocolStatus
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.metadata.Name
import org.jsoup.nodes.Document
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

fun AppFiles.export(status: ProtocolStatus, content: String, page: WebPage): Path {
    return AppFiles.export(StringBuilder(), status, content, page)
}

fun AppFiles.export(
    sb: StringBuilder,
    status: ProtocolStatus,
    content: String,
    page: WebPage,
    suffix: String = ".htm",
): Path {
    val dirName = AppPaths.fromHost(page.url)

    val document = Documents.parse(content, page.baseURI)
    document.absoluteLinks()
    val prettyHtml = document.prettyHtml
    val length = prettyHtml.length

    sb.setLength(0)
    sb.append(status.minorName).append('/').append(dirName)
    if (length < 2000) {
        sb.append("/a").append(length / 500 * 500)
    } else {
        sb.append("/b").append(length / 20000 * 20000)
    }
    sb.append("-")

    val fileNameIdent = sb.toString()
    val path = export(page, prettyHtml.toByteArray(), prefix = fileNameIdent, suffix = suffix, group = "default")

    page.metadata.set(Name.ORIGINAL_EXPORT_PATH, path.toString())

    if (page is AbstractWebPage) {
        page.setVar(Name.ORIGINAL_EXPORT_PATH.name, path)
    }

    return path
}

fun AppFiles.export(
    page: WebPage, content: ByteArray, prefix: String = "", suffix: String = ".htm", group: String = "default"
): Path {
    val browser = page.lastBrowser.name.lowercase(Locale.getDefault())

    val path0 = AppPaths.fromUri(page.url, prefix, suffix)
    val path = WEB_CACHE_DIR.resolve(group).resolve(browser).resolve(path0)
    AppFiles.saveTo(content, path, true)

    return path
}

fun AppFiles.export(page: WebPage, prefix: String = "", suffix: String = ".html"): Path {
    val filename = page.headers.decodedDispositionFilename ?: AppPaths.fromUri(page.location, prefix, suffix)
    val path = WEB_CACHE_DIR.resolve(filename)
    Files.deleteIfExists(path)
    AppFiles.saveTo(page.content?.array() ?: "(empty)".toByteArray(), path)
    return path
}

fun AppFiles.export(doc: Document, ident: String = ""): Path {
    val filename = AppPaths.fromUri(doc.baseUri(), "", ".html")
    val path = WEB_CACHE_DIR.resolve(ident).resolve(filename)
    return saveTo(doc.outerHtml(), path)
}
