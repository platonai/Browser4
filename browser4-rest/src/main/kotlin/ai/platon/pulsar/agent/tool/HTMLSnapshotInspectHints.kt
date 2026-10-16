package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.dom.FeaturedDocument
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jsoup.nodes.Element

// =========================================================================
// The two things `htmlsnapshot inspect` says about a selector that the raw
// `inspectDocument` payload cannot say for itself.
//
// 1. Which selector was actually analysed
//
// `inspectDocument` auto-discovers a repeating pattern whenever the caller's selector matches at
// most one element, and then analyses *that* pattern: with 0 matches it analyses a page-level
// pattern, with exactly 1 it analyses the repeating children of that container.  The payload names
// the substituted selector (`selector`) and keeps the caller's own input (`originalSelector`), but
// it never says how many elements the caller's selector matched — so a renderer cannot distinguish
// "your selector repeats" from "your selector matched nothing, here is what I looked at instead",
// and the CLI narrated a non-matching selector as a repeating sibling group.
//
// `annotateInspectPayload` adds `originalMatchCount`: the number of elements the *caller's* selector
// matched, measured with the same engine.  `0` means "your selector matched nothing — the analysis
// below is about a different element set"; `1` means "your selector matched a single container, the
// analysis is about its repeating children"; `>1` means the caller's selector *is* the analysed one.
//
// 2. Whether the truncation hint is usable where it is printed
//
// The hint is built from `buildElementRef`, a *standalone* element reference (`a`), not a path
// relative to the repeating container (`h3 a`).  Both consumers of the hint evaluate it
// container-scoped — `DOM_FIRST_ATTR(DOM, 'a', 'title')` inside the `FROM`-clause row, and
// `htmlsnapshot get all attr "a" title` — where the first `<a>` of a product card is the image link,
// which has no `title`: the quoted value was printed next to SQL that returns "" for every row.
// `annotateInspectPayload` rewrites each hint's `childSelector` to a container-relative path that is
// validated against every match, and drops the hint when no such path exists.
// =========================================================================

/** Words (or CJK characters) `inspectDocument` truncates a hint's sample value to. */
private const val HINT_SAMPLE_MAX_WORDS = 8

/**
 * How many matches a candidate hint selector is validated against. A path that only works for the
 * first rows is not a pattern, but proving it for a page with thousands of matches is not worth a
 * select per match either — the first [HINT_VALIDATION_LIMIT] rows decide.
 */
private const val HINT_VALIDATION_LIMIT = 20

/**
 * Enrich the `inspectDocument` payload with `originalMatchCount` and with truncation hints whose
 * `childSelector` really resolves inside the repeating container.
 *
 * Pure function of (document, payload): the payload is returned unchanged when it is not the JSON
 * object this enrichment understands, so a future payload shape can never turn inspect into an error.
 *
 * @param document the analysed document (the same one the payload was built from).
 * @param payload the JSON produced by `inspectDocument`.
 * @return the enriched payload, or [payload] itself when nothing could be enriched.
 */
internal fun annotateInspectPayload(document: FeaturedDocument, payload: String): String {
    val mapper = pulsarObjectMapper()
    val root = runCatching { mapper.readTree(payload) as? ObjectNode }.getOrNull() ?: return payload

    val effectiveSelector = root.get("selector")?.asText()?.takeIf { it.isNotBlank() }
    val originalSelector = root.get("originalSelector")?.asText()?.takeIf { it.isNotBlank() }
    val userSelector = originalSelector ?: effectiveSelector ?: return payload

    // The caller's own selector, counted before any substitution: `1` is a container whose children
    // were analysed, `0` is a selector that matched nothing at all.
    countMatches(document, userSelector)?.let { root.put("originalMatchCount", it) }

    if (effectiveSelector != null) repairTruncationHints(document, root, effectiveSelector)
    return root.toString()
}

/** [selector]'s match count, or null when the selector cannot be evaluated at all. */
private fun countMatches(document: FeaturedDocument, selector: String): Int? =
    runCatching { document.select(selector).size }.getOrNull()

/**
 * Rewrite (or drop) every truncation hint of every sample so that the selector it hands the caller
 * is a container-relative path carrying the attribute value it quotes.
 *
 * `samples` mirrors the analysed matches one for one (the backend takes the first three), so sample
 * *i* belongs to match *i*.
 */
private fun repairTruncationHints(document: FeaturedDocument, root: ObjectNode, effectiveSelector: String) {
    val samples = root.get("samples") as? ArrayNode ?: return
    if (samples.isEmpty) return
    val matches = runCatching { document.select(effectiveSelector) }.getOrNull() ?: return
    val mapper = pulsarObjectMapper()

    for (index in 0 until samples.size()) {
        val sample = samples.get(index) as? ObjectNode ?: continue
        val hints = sample.get("truncationHints") as? ArrayNode ?: continue
        if (hints.isEmpty) continue

        val container = matches.getOrNull(index) ?: continue

        val kept = mapper.createArrayNode()
        for (hintIndex in 0 until hints.size()) {
            val hint = hints.get(hintIndex) as? ObjectNode ?: continue
            val attribute = hint.get("attribute")?.asText()?.takeIf { it.isNotBlank() } ?: continue
            val sampleValue = hint.get("sampleValue")?.asText().orEmpty()
            val currentRef = hint.get("childSelector")?.asText().orEmpty()

            val repaired = containerScopedHintSelector(container, attribute, sampleValue, currentRef, matches)
                ?: continue
            hint.put("childSelector", repaired)
            kept.add(hint)
        }

        if (kept.isEmpty) sample.remove("truncationHints")
        else sample.set<ArrayNode>("truncationHints", kept)
    }
}

/**
 * A selector that resolves, **inside** [container], to the element carrying the full value of
 * [attribute] — `h3 a` for a product card whose clipped label's title lives on the anchor — or null
 * when no such path exists, in which case the hint must not be printed.
 *
 * The hint's own reference is kept when it already works row-scoped ([currentRef]); otherwise the
 * attribute carrier is located inside the container and a container-relative path to it is derived
 * and validated against every analysed match, so the caller never gets a selector that returns the
 * empty string the hint was supposed to replace.
 */
private fun containerScopedHintSelector(
    container: Element,
    attribute: String,
    sampleValue: String,
    currentRef: String,
    matches: List<Element>,
): String? {
    if (currentRef.isNotBlank() && containerCarriesValue(container, currentRef, attribute, sampleValue)) {
        return currentRef
    }

    val carrier = findAttributeCarrier(container, attribute, sampleValue) ?: return null
    return candidateHintSelectors(container, carrier, attribute)
        .firstOrNull { candidate -> validatesAcrossMatches(candidate, attribute, matches) }
}

/** True when [selector] resolves inside [container] to an element whose [attribute] is [sampleValue]. */
private fun containerCarriesValue(
    container: Element,
    selector: String,
    attribute: String,
    sampleValue: String,
): Boolean {
    val element = runCatching { container.selectFirst(selector) }.getOrNull() ?: return false
    return matchesSampleValue(element.attr(attribute), sampleValue)
}

/** The descendant of [container] whose [attribute] carries [sampleValue], or null. */
private fun findAttributeCarrier(container: Element, attribute: String, sampleValue: String): Element? =
    runCatching { container.select("[$attribute]") }.getOrNull()
        ?.firstOrNull { matchesSampleValue(it.attr(attribute), sampleValue) }

/**
 * True when the element's *own* [attribute] value is the one the sample quotes.
 *
 * `sampleValue` is the attribute value truncated to the first
 * [HINT_SAMPLE_MAX_WORDS] words (or characters, for CJK), so a prefix comparison in either
 * normalisation is what identifies the same element.
 */
private fun matchesSampleValue(attributeValue: String, sampleValue: String): Boolean {
    val full = attributeValue.trim().replace(WHITESPACE, " ")
    if (full.isBlank()) return false
    val sample = sampleValue.trim().replace(WHITESPACE, " ")
    if (sample.isBlank()) return true
    if (full == sample || full.startsWith(sample)) return true
    val firstWords = full.split(" ").take(HINT_SAMPLE_MAX_WORDS).joinToString(" ")
    return firstWords == sample
}

/**
 * The selectors to consider for [target] *relative to* [container], most general first: the tag
 * path (`h3 a`), the same path with the first class of each step (`h3.x a.y`), and the target's
 * attribute-qualified tag (`a[title]`).
 */
private fun candidateHintSelectors(container: Element, target: Element, attribute: String): List<String> {
    val chain = ancestorChain(container, target) ?: return emptyList()
    if (chain.isEmpty()) return emptyList()

    val tagPath = chain.joinToString(" ") { it.tagName().lowercase() }
    val classPath = chain.joinToString(" ") { element ->
        val firstClass = element.className().trim().split(WHITESPACE).firstOrNull { it.isNotBlank() }
        if (firstClass == null) element.tagName().lowercase()
        else "${element.tagName().lowercase()}.$firstClass"
    }
    val attributePath = "${target.tagName().lowercase()}[$attribute]"

    return listOf(tagPath, classPath, attributePath).distinct()
}

/** The elements strictly between [container] (exclusive) and [target] (inclusive), outermost first. */
private fun ancestorChain(container: Element, target: Element): List<Element>? {
    val chain = mutableListOf<Element>()
    var current: Element? = target
    while (current != null && current !== container) {
        chain.add(current)
        current = current.parent()
    }
    return if (current == null) null else chain.asReversed()
}

/**
 * True when [selector], evaluated inside **every** analysed match, reaches an element whose
 * [attribute] is non-blank — the property that makes the printed SQL/hint useful for every row.
 */
private fun validatesAcrossMatches(selector: String, attribute: String, matches: List<Element>): Boolean {
    val sample = matches.take(HINT_VALIDATION_LIMIT)
    if (sample.isEmpty()) return false
    return sample.all { match ->
        runCatching { match.selectFirst(selector) }.getOrNull()?.attr(attribute)?.isNotBlank() == true
    }
}

private val WHITESPACE = Regex("\\s+")
