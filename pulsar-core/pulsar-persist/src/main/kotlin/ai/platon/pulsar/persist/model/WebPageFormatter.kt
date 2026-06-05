/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.persist.model

import ai.platon.pulsar.common.serialize.json.prettyPulsarObjectMapper
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.WebPageExt
import org.apache.gora.util.ByteUtils
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Instant
import java.time.LocalDateTime
import java.util.stream.Collectors

class WebPageFormatter(val page: WebPage) {
    private var withText = false
    private var withContent = false
    private var withLinks = false
    private var withFields = false
    private var withEntities = false
    private val pageExt = WebPageExt(page)
    private val zoneId = page.zoneId

    fun withText(withText: Boolean): WebPageFormatter {
        this.withText = withText
        return this
    }

    fun withText(): WebPageFormatter {
        withText = true
        return this
    }

    fun withContent(withContent: Boolean): WebPageFormatter {
        this.withContent = withContent
        return this
    }

    fun withContent(): WebPageFormatter {
        withContent = true
        return this
    }

    fun withLinks(withLinks: Boolean): WebPageFormatter {
        this.withLinks = withLinks
        return this
    }

    fun withLinks(): WebPageFormatter {
        withLinks = true
        return this
    }

    fun withFields(withFields: Boolean): WebPageFormatter {
        this.withFields = withFields
        return this
    }

    fun withFields(): WebPageFormatter {
        withFields = true
        return this
    }

    fun withEntities(withEntities: Boolean): WebPageFormatter {
        this.withEntities = withEntities
        return this
    }

    fun withEntities(): WebPageFormatter {
        withEntities = true
        return this
    }

    fun toMap(): Map<String, Any> {
        val fields: MutableMap<String, Any?> = LinkedHashMap()
        /* General */
        fields["key"] = page.key
        fields["url"] = page.url
        fields["options"] = page.args
        fields["createTime"] = format(page.createTime)
        fields["distance"] = page.distance
        fields["protocolStatus"] = page.protocolStatus.name
        fields["protocolStatusMessage"] = page.protocolStatus.toString()
        if (page.content != null) {
            fields["contentLength"] = page.content!!.array().size
        }
        fields["fetchCount"] = page.fetchCount
        fields["fetchInterval"] = page.fetchInterval.toString()
        fields["retriesSinceFetch"] = page.fetchRetries
        fields["prevFetchTime"] = format(page.prevFetchTime)
        fields["fetchTime"] = format(page.fetchTime)
        fields["prevModifiedTime"] = format(page.prevModifiedTime)
        fields["modifiedTime"] = format(page.modifiedTime)
        fields["baseUrl"] = page.location
        /* Parse */fields["parseStatus"] = page.parseStatus.name
        fields["parseStatusMessage"] = page.parseStatus.toString()
        fields["encoding"] = page.encoding
        fields["prevSignature"] = page.prevSignatureAsString
        fields["signature"] = page.signatureAsString
        fields["pageCategory"] = page.pageCategory.name
        // May be too long
        fields["pageTitle"] = page.pageTitle
        fields["contentTitle"] = page.contentTitle
        fields["inlinkAnchor"] = page.anchor
        fields["title"] = pageExt.sniffTitle()
        fields["metadata"] = page.metadata.asStringMap()
        fields["headers"] = page.headers.asStringMap()
        fields["linkCount"] = page.links.size
        fields["inlinkCount"] = page.inlinks.size
        fields["linksMessage"] = ("Total "
                + page.links.size + " links, "
                + page.vividLinks.size + " vivid links, "
                + page.inlinks.size + " inlinks")
        if (withLinks) {
            fields["links"] = page.links.stream().map { obj: CharSequence -> obj.toString() }.collect(Collectors.toList())
//            fields["inlinks"] = page.inlinks.entries.stream()
//                    .map { il: Map.Entry<CharSequence, CharSequence> -> il.key.toString() + "\t" + il.value }.collect(Collectors.joining("\n"))
        }
        if (withText) {
            fields["contentText"] = page.contentText
            fields["pageText"] = page.pageText
        }
        if (withContent && page.content != null) {
            fields["content"] = page.contentAsString
        }
        if (withEntities) {
            val pageModel = page.pageModel
            if (pageModel != null) {
                val pageEntities = pageModel.unboxedFieldGroups.map { FieldGroupFormatter(it).fields.entries }
                fields["pageEntities"] = pageEntities
            }
        }
        return fields.filterValues { it != null }.entries.associate { it.key to it.value!! }
    }

    fun toMap(fields: Set<String>): Map<String, Any> {
        return toMap().entries.filter { fields.contains(it.key) }
            .associate { it.key to it.value }
    }

    fun format(): String {
        val sb = StringBuilder()
        sb.append("url:\t" + page.url + "\n")
                .append("baseUrl:\t" + page.location + "\n")
                .append("protocolStatus:\t" + page.protocolStatus + "\n")
                .append("depth:\t" + page.distance + "\n")
                .append("pageCategory:\t" + page.pageCategory + "\n")
                .append("fetchCount:\t" + page.fetchCount + "\n")
                .append("fetchInterval:\t" + page.fetchInterval + "\n")
                .append("retriesSinceFetch:\t" + page.fetchRetries + "\n")
        sb.append("\n")
                .append("options:\t" + page.args + "\n")
        sb.append("\n")
                .append("createTime:\t" + format(page.createTime) + "\n")
                .append("prevFetchTime:\t" + format(page.prevFetchTime) + "\n")
                .append("fetchTime:\t" + format(page.fetchTime) + "\n")
                .append("prevModifiedTime:\t" + format(page.prevModifiedTime) + "\n")
                .append("modifiedTime:\t" + format(page.modifiedTime) + "\n")

        sb.append("\n")
                .append("pageTitle:\t" + page.pageTitle + "\n")
                .append("contentTitle:\t" + page.contentTitle + "\n")
                .append("anchor:\t" + page.anchor + "\n")
                .append("title:\t" + pageExt.sniffTitle() + "\n")
        sb.append("\n")
                .append("parseStatus:\t" + page.parseStatus.toString() + "\n")
                .append("prevSignature:\t" + page.prevSignatureAsString + "\n")
                .append("signature:\t" + page.signatureAsString + "\n")

        val metadata = page.metadata.asStringMap()
        if (metadata.isNotEmpty()) {
            sb.append("\n")
            metadata.entries.stream().filter { it.value.startsWith("meta_") }
                    .forEach { (key, value) -> sb.append("metadata $key:\t$value\n") }
            metadata.entries.stream().filter { e -> e.value.startsWith("meta_") }
                    .forEach { (key, value) -> sb.append("metadata $key:\t$value\n") }
        }
        val headers = page.headers.unbox()
        if (headers != null && headers.isNotEmpty()) {
            sb.append("\n")
            headers.forEach { (key, value) -> sb.append("header $key:\t$value\n") }
        }

        sb.append("\n")
        sb.append("Total " + page.links.size + " links, ")
                .append(page.vividLinks.size.toString() + " vivid links, ")
                .append(page.inlinks.size.toString() + " inlinks\n")

        if (withLinks) {
            sb.append("\n")
            sb.append("links:\n")
            page.links.forEach { l -> sb.append("links:\t$l\n") }
            sb.append("vividLinks:\n")
            page.vividLinks.forEach { (k, v) -> sb.append("liveLinks:\t$k\t$v\n") }
            sb.append("inlinks:\n")
            page.inlinks.forEach { (key, value) -> sb.append("inlink:\t$key\t$value\n") }
        }

        if (withContent) {
            val content = page.content
            if (content != null) {
                sb.append("\n")
                sb.append("contentType:\t" + page.contentType + "\n")
                        .append("content:START>>>\n")
                        .append(ByteUtils.toString(content.array()))
                        .append("\n<<<END:content\n")
            }
        }
        if (withText) {
            if (page.contentText != null) {
                sb.append("\n")
                sb.append("contentText:START>>>\n")
                        .append(page.contentText)
                        .append("\n<<<END:contentText\n")
            }
            if (page.pageText != null) {
                sb.append("pageText:START>>>\n")
                        .append(page.pageText)
                        .append("\n<<<END:pageText\n")
            }
        }
        if (withEntities) {
            val pageModel = page.pageModel
            if (pageModel != null) {
                sb.append("\n").append("entityField:START>>>\n")
                pageModel.unboxedFieldGroups
                    .flatMap { FieldGroupFormatter(it).fields.entries }
                    .joinTo(sb) { it.key + ": " + it.value }
                sb.append("\n<<<END:pageText\n")
            }
        }
        sb.append("\n")
        return sb.toString()
    }

    fun createDocument(): Document {
        val doc = Document.createShell(page.location)
        doc.head().appendElement("title").appendText(page.pageTitle ?: "")
        doc.body().appendElement("h1").appendText(page.contentTitle ?: "")
        doc.body().appendElement("div")
                .attr("class", "content")
                .append(page.contentText ?: "")
        createLinksElement(doc.body())
        return doc
    }

    fun createLinksElement(parent: Element) {
        val links = parent.appendElement("div")
                .attr("class", "links")
                .appendElement("ul")
        var i = 0
        val vividLinks = page.vividLinks ?: return
        for (l in vividLinks) {
            ++i
            links.appendElement("li")
                    .appendElement("a").attr("href", l.toString()).appendText(l.key.toString())
        }
    }

    fun toJson(): String {
        return prettyPulsarObjectMapper().writeValueAsString(toMap())
    }

    private fun format(instant: Instant): String {
        return LocalDateTime.ofInstant(instant, zoneId).toString()
    }

    override fun toString(): String {
        return format()
    }
}
