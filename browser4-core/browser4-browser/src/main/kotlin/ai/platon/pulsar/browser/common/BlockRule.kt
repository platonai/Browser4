package ai.platon.pulsar.browser.common

import ai.platon.cdt.kt.protocol.types.network.ResourceType
import ai.platon.pulsar.common.Wildchar

/**
 * The block rules of urls and resources
 * */
open class BlockRule {
    companion object {
        val IMAGE_URL_PATTERNS = listOf(
            "*.png", "*.jpg", "*.jpeg", "*.gif", "*.ico", "*.webp", "*.svg",
            "*.png?*", "*.jpg?*", "*.gif?*", "*.ico?*", "*.webp?*",
        )

        val MEDIA_URL_PATTERNS = listOf(
            "*.woff", "*.woff2", "*.mp4"
        ) + IMAGE_URL_PATTERNS
    }

    open val blockingResourceTypes: MutableList<ResourceType>
        get() = listOf(ResourceType.IMAGE, ResourceType.MEDIA, ResourceType.FONT).toMutableList()

    /**
     * amazon.com note:
     * The following have to pass, or the site refuses to serve:
     * .woff,
     * .mp4
     * */
    open val mustPassUrls: MutableList<String>
        get() = mutableListOf()

    /**
     * Blocking urls patten using widcards
     * */
    open val blockingUrls: MutableList<String>
        get() = listOf(
            "*.png", "*.jpg", "*.jpeg", "*.gif", "*.ico", "*.webp",
            "*.woff", "*.woff2",
            "*.mp4", "*.svg",
            "*.png?*", "*.jpg?*", "*.gif?*", "*.ico?*", "*.webp?*",
            "https://img*"
        ).filterNot { it in mustPassUrls }.toMutableList()

    open val mustPassUrlRegexes: MutableList<Regex>
        get() = listOf(
            "about:blank",
            "data:.+",
        ).map { it.toRegex() }.union(mustPassUrls.map { Wildchar(it).toRegex() }).toMutableList()

    open val blockingUrlRegexes: MutableList<Regex>
        get() = blockingUrls.map { Wildchar(it).toRegex() }.toMutableList()
}
