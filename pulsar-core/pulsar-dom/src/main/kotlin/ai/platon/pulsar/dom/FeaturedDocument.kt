package ai.platon.pulsar.dom

import ai.platon.pulsar.common.AppFiles
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.config.AppConstants.INTERNAL_URL_PREFIX
import ai.platon.pulsar.common.math.geometric.DimI
import ai.platon.pulsar.common.math.vectors.isNotEmpty
import ai.platon.pulsar.common.urls.Hyperlink
import ai.platon.pulsar.dom.nodes.*
import ai.platon.pulsar.dom.nodes.node.ext.*
import ai.platon.pulsar.dom.select.*
import org.apache.commons.math3.linear.RealVector
import org.jsoup.nodes.*
import org.jsoup.select.NodeTraversor
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * An HTML Document.
 *
 * A ‘featured document’ refers to a ‘very important document.’ Additionally, the numerical features of each node are
 * calculated, and these numerical features can be used to locate nodes or utilized by machine learning algorithms.
 *
 * [FeaturedDocument] is a wrapper for [org.jsoup.nodes.Document], every node's numerical features are
 * calculated by a [ai.platon.pulsar.dom.features.FeatureCalculator] which can be customized.
 *
 * [FeaturedDocument] provides a set of powerful methods to select elements, text contexts, attributes
 * and so on:
 *
 * * [select]: retrieves a list of elements matching the CSS query.
 * * [selectFirstOrNull]: retrieves the first matching element.
 * * [selectFirstTextOrNull]: retrieves the text content of the first matching element.
 * * [selectFirstAttributeOrNull]: retrieves the attribute value associated to the given name of the first matching element.
 * * [selectHyperlinks]: retrieves all hyperlinks of elements matching the CSS query.
 * * [selectAnchors]: retrieves all anchor elements matching the CSS query.
 * * [selectImages]: retrieves all image elements matching the CSS query.
 *
 * Other methods provided include DOM traversal, node counting, document attribute retrieval, export, and so on.
 *
 * @param document The underlying [org.jsoup.nodes.Document]
 *
 * @see org.jsoup.nodes.Document
 * */
open class FeaturedDocument(val document: Document) {
    companion object {
        private val instanceSequencer = AtomicInteger()

        var SELECTOR_IN_BOX_DEVIATION = 25
        var primaryGridDimension = DimI(30, 15) // about 1 em
        var secondaryGridDimension = DimI(5, 5)
        var densityUnitArea = 400 * 400
        val globalNumDocuments get() = instanceSequencer.get()

        /**
         * The NIL document which is a wrapper for a nil [org.jsoup.nodes.Document]
         * */
        val NIL = FeaturedDocument(NILDocument)

        /**
         * The HTML content of a NIL document
         * */
        val NIL_DOC_HTML = NIL.unbox().outerHtml()

        /**
         * The length of a NIL document's HTML content
         * */
        val NIL_DOC_LENGTH = NIL_DOC_HTML.length

        /**
         * Create a shell document.
         * */
        fun createShell(baseURI: String, charset: String = "UTF-8"): FeaturedDocument {
            val document = Document.createShell(baseURI)
            document.head().append("<meta charset=\"$charset\">")
            return FeaturedDocument(document)
        }

        /**
         * Check if this document is NIL.
         * */
        fun isNil(doc: FeaturedDocument) = doc == NIL

        /**
         * Check if this document is internal.
         * */
        fun isInternal(doc: FeaturedDocument) = doc.location.startsWith(INTERNAL_URL_PREFIX)
    }

    /**
     * The process scope unique sequence.
     * */
    val sequence = instanceSequencer.incrementAndGet()

    /**
     * The normalized URI of the document, it's also the key to retrieve the document from the database
     * and always be the same as [ai.platon.pulsar.persist.WebPage].url.
     * */
    val normalizedURI get() = document.normalizedURI

    /**
     * Get the URL this Document was parsed from. If the starting URL is a redirect,
     * this will return the final URL from which the document was served from.
     */
    val location get() = document.location()

    /**
     * The URL where the HTML was retrieved from. Used to resolve relative URLs to absolute URLs, that occur
     * before the HTML declares a `<base href>` tag.
     *
     * @return base URI
     * @see #absUrl
     */
    val baseURI get() = document.baseUri()

    /**
     * Get the document title.
     * */
    val title get() = document.title()

    /**
     * Get this document's head element.
     *
     * As a side effect, if this Document does not already have a HTML structure,
     * it will be created. If you do not want that, use `selectFirst("head")` instead.
     *
     * @return head element.
     */
    val head: Element get() = document.head()

    /**
     * Get this document's [body] element.
     *
     * As a <b>side effect</b>, if this Document does not already have an HTML structure,
     * it will be created with a [body] element. If you do not want that,
     * use {@code #selectFirst("body")} instead.
     *
     * @return [body] element for documents with a [body], a new [body]
     * element if the document had no contents for frameset documents.
     */
    val body: Element get() = document.body()

    /**
     * Gets the <b>normalized, combined text</b> of this element and all its children. Whitespace is normalized and
     * trimmed.
     * <p>For example, given HTML {@code <p>Hello  <b>there</b> now! </p>}, {@code p.text()} returns {@code "Hello there
     * now!"}
     * <p>If you do not want normalized text, use {@link #wholeText()}. If you want just the text of this document (and not
     * children), use {@link #ownText()}
     * <p>Note that this method returns the textual content that would be presented to a reader. The contents of data
     * nodes (such as {@code <script>} tags are not considered text. Use {@link #data()} or {@link #html()} to retrieve
     * that content.
     *
     * @return unencoded, normalized text, or empty string if none.
     * @see #wholeText()
     * @see #ownText()
     * @see #textNodes()
     */
    val text get() = document.text()

    /**
     * Gets the (normalized) text owned by this element only; does not get the combined text of all children.
     * <p>
     * For example, given HTML {@code <p>Hello <b>there</b> now!</p>}, {@code p.ownText()} returns {@code "Hello now!"},
     * whereas {@code p.text()} returns {@code "Hello there now!"}.
     * Note that the text within the {@code b} element is not returned, as it is not a direct child of the {@code p} element.
     *
     * @return unencoded text, or empty string if none.
     * @see text
     * @see textNodes
     */
    val ownText get() = document.ownText()

    /**
     * Get the (unencoded) text of all children of this element, including any newlines and spaces present in the
     * original.
     *
     * @return unencoded, un-normalized text
     * @see text
     */
    val wholeText get() = document.wholeText()

    /**
     * Retrieves the document's inner HTML. E.g. on a {@code <div>} with one empty {@code <p>}, would return
     * {@code <p></p>}. (Whereas {@link #outerHtml()} would return {@code <div><p></p></div>}.)
     *
     * @return String of HTML.
     * @see #outerHtml()
     */
    val html get() = document.html()

    /**
     * Get the outer HTML of this document. For example, on a {@code p} element, may return {@code <p>Para</p>}.
     * @return outer HTML
     * @see Element#html()
     * @see Element#textContent()
     */
    val outerHtml get() = document.outerHtml()

    /**
     * Returns the charset used in this document. This method is equivalent
     * to {@link OutputSettings#charset()}.
     *
     * @return Current Charset
     */
    val charset get() = document.charset()

    /**
    Get the node name of this document. Use for debugging purposes and not logic switching (for that, use instanceof).
    @return node name
     */
    val nodeName get() = document.nodeName()

    /**
     * Get the id attribute of this element.
     *
     * @return The id attribute, if present, or an empty string if not.
     */
    val id get() = document.id()

    /**
     * Gets the literal value of this element's "class" attribute, which may include multiple class names, space
     * separated. (E.g. on <code>&lt;div class="header gray"&gt;</code> returns, "<code>header gray</code>")
     * @return The literal class attribute, or <b>empty string</b> if no class attribute set.
     */
    val className get() = document.className()

    /**
     * Get the combined data of this element. Data is e.g. the inside of a {@code <script>} tag. Note that data is NOT the
     * text of the element. Use {@link text} to get the text that would be visible to a user, and {@code data()}
     * for the contents of scripts, comments, CSS styles, etc.
     *
     * @return the data, or empty string if none
     *
     * @see dataNodes
     */
    val data get() = document.data()

    /**
     * Get this element's child data nodes. The list is unmodifiable but the data nodes may be manipulated.
     * <p>
     * This is effectively a filter on {@link #childNodes()} to get Data nodes.
     * </p>
     * @return child data nodes. If this element has no data nodes, returns an
     * empty list.
     * @see data
     */
    val dataNodes: List<DataNode> get() = document.dataNodes()

    /**
     * Get this element's child text nodes. The list is unmodifiable but the text nodes may be manipulated.
     * <p>
     * This is effectively a filter on {@link #childNodes()} to get Text nodes.
     * @return child text nodes. If this element has no text nodes, returns an
     * empty list.
     * </p>
     * For example, with the input HTML: {@code <p>One <span>Two</span> Three <br> Four</p>} with the {@code p} element selected:
     * <ul>
     *     <li>{@code p.text()} = {@code "One Two Three Four"}</li>
     *     <li>{@code p.ownText()} = {@code "One Three Four"}</li>
     *     <li>{@code p.children()} = {@code Elements[<span>, <br>]}</li>
     *     <li>{@code p.childNodes()} = {@code List<Node>["One ", <span>, " Three ", <br>, " Four"]}</li>
     *     <li>{@code p.textNodes()} = {@code List<TextNode>["One ", " Three ", " Four"]}</li>
     * </ul>
     */
    val textNodes: List<TextNode> get() = document.textNodes()

    /**
     * Get this document's children. Presented as an unmodifiable list: new children can not be added,
     * but the child nodes themselves can be manipulated.
     *
     * @return list of children. If no children, returns an empty list.
     */
    val childNodes: List<Node> get() = document.childNodes()

    /**
     * Retrieves the document's outer HTML with pretty printing.
     * */
    val prettyHtml: String
        get() {
            document.outputSettings().prettyPrint(true)
            return outerHtml.replace("s-features", "\n\t\t\ts-features")
                .replace("s-named-features", "\n\t\t\ts-named-features").replace("s-caption", "\n\t\t\ts-caption")
        }

    /**
     * Get this document's numeric feature vector.
     *
     * @return a real-valued vector with basic algebraic operations.
     */
    val features: RealVector
        get() = document.extension.features

    /**
     * The constructor
     *
     * @param baseURI The URL where the HTML was retrieved from. Used to resolve relative URLs to absolute URLs, that occur
     * before the HTML declares a `<base href>` tag.
     * */
    constructor(baseURI: String) : this(Document(baseURI))

    /**
     * The constructor
     * */
    constructor(other: FeaturedDocument) : this(other.unbox())

    init {
        initialize()
    }

    /**
     * Get the underlying document.
     *
     * @return the underlying Jsoup document.
     */
    fun unbox() = document

    /**
     * Check if this document is nil.
     */
    fun isNil() = isNil(this)

    /**
     * Check if this document is internal.
     */
    fun isInternal() = isInternal(this)

    /**
     * Check if this document is internal.
     */
    fun isNotInternal() = !isInternal()

    /**
     * Guess the document's title.
     *
     * The title should be guessed for some site without a <title> tag inside a <head> tag.
     * */
    fun guessTitle(): String {
        return title.takeUnless { it.isBlank() } ?: selectFirstTextOrNull("title") ?: selectFirstTextOrNull("h1")
        ?: selectFirstTextOrNull("h2") ?: ""
    }

    /**
     * Make all links in the document to be absolute.
     * */
    fun absoluteLinks() {
        forEachElement {
            if (it.hasAttr("href")) {
                it.attr("href", it.attr("abs:href"))
            } else if (it.hasAttr("src")) {
                it.attr("src", it.attr("abs:src"))
            }
        }
    }

    /**
     * Find elements that match the CSS query. Matched elements
     * may include the document, or any of its children.
     *
     * This method is generally more powerful to use than the DOM-type {@code getElementBy*} methods, because
     * multiple filters can be combined.
     *
     * @param query A power-CSS query
     * @param offset The offset of the first element to return
     * @param limit The maximum number of elements to return
     * @return A list of elements that match the query
     * */
    @JvmOverloads
    fun select(query: String, offset: Int = 1, limit: Int = Int.MAX_VALUE) = document.select2(query, offset, limit)

    /**
     * Find elements that match the CSS query. Matched elements
     * may include the document, or any of its children.
     *
     * This method is generally more powerful to use than the DOM-type {@code getElementBy*} methods, because
     * multiple filters can be combined.
     *
     * @param query A power-CSS query
     * @param offset The offset of the first element to return
     * @param limit The maximum number of elements to return
     * @param transformer A transformer to transform the element to the desired type
     * @return A list of elements that match the query
     * */
    fun <T> select(query: String, offset: Int = 1, limit: Int = Int.MAX_VALUE, transformer: (Element) -> T) =
        document.select(query, offset, limit, transformer = transformer)

    /**
     * Find the first element that match the CSS query. Matched element
     * may be the document, or any of its children.
     *
     * This method is generally more powerful to use than the DOM-type {@code getElementBy*} methods, because
     * multiple filters can be combined.
     *
     * @param query A power-CSS query
     * @return The first element that match the query
     * @throws NoSuchElementException if no element matches the query
     * */
    @Throws(NoSuchElementException::class)
    fun selectFirst(query: String) =
        document.selectFirstOrNull(query) ?: throw NoSuchElementException("No element matching $query")

    /**
     * Find the first element that match the CSS query. Matched element
     * may be the document, or any of its children.
     *
     * This method is generally more powerful to use than the DOM-type {@code getElementBy*} methods, because
     * multiple filters can be combined.
     *
     * @param query A power-CSS query
     * @param transformer A transformer to transform the element to the desired type
     * @return The first element that match the query
     * @throws NoSuchElementException if no element matches the query
     * */
    @Throws(NoSuchElementException::class)
    fun <T> selectFirst(query: String, transformer: (Element) -> T) =
        document.selectFirstOrNull(query)?.let { transformer(it) }
            ?: throw NoSuchElementException("No element matching $query")

    /**
     * Find the first element that match the CSS query. Matched element
     * may be the document, or any of its children.
     *
     * This method is generally more powerful to use than the DOM-type {@code getElementBy*} methods, because
     * multiple filters can be combined.
     *
     * @param query A power-CSS query
     * @return The first element that match the query, if no element matches the query, return null
     * */
    fun selectFirstOrNull(query: String) = document.selectFirstOrNull(query)

    /**
     * Find the first element that match the CSS query. Matched element
     * may be the document, or any of its children.
     *
     * This method is generally more powerful to use than the DOM-type {@code getElementBy*} methods, because
     * multiple filters can be combined.
     *
     * @param query A power-CSS query
     * @param transformer A transformer to transform the element to the desired type
     * @return The first element that match the query, if no element matches the query, return null
     * */
    fun <T> selectFirstOrNull(query: String, transformer: (Element) -> T) =
        document.selectFirstOrNull(query)?.let { transformer(it) }

    /**
     * Find elements that match the CSS query. Matched elements
     * may include the document, or any of its children.
     *
     * This method is generally more powerful to use than the DOM-type {@code getElementBy*} methods, because
     * multiple filters can be combined.
     *
     * Return the first element, if no element matches the query, return an Optional object.
     *
     * @param query A power-CSS query
     * @return The first element that match the query
     * */
    fun selectFirstOptional(query: String) = Optional.ofNullable(document.selectFirstOrNull(query))

    /**
     * Find elements that match the CSS query. Matched elements
     * may include the document, or any of its children.
     *
     * This method is generally more powerful to use than the DOM-type {@code getElementBy*} methods, because
     * multiple filters can be combined.
     *
     * Return the first element, if no element matches the query, return an Optional object.
     *
     * @param query A power-CSS query
     * @param transformer A transformer to transform the element to the desired type
     * @return The first element that match the query
     * */
    fun <T> selectFirstOptional(query: String, transformer: (Element) -> T) =
        Optional.ofNullable(document.selectFirstOrNull(query)?.let { transformer(it) })

    /**
     * Find text of elements that match the CSS query. Matched elements
     * may include the document, or any of its children.
     *
     * @param query A power-CSS query
     * @param attrName The attribute name of the text to return
     * @param offset The offset of the first element to return
     * @param limit The maximum number of elements to return
     * @return A list of attribute values of elements that match the query
     * */
    @JvmOverloads
    fun selectTextAll(query: String, attrName: String, offset: Int = 1, limit: Int = Int.MAX_VALUE) =
        document.selectAttributes(query, attrName, offset, limit)

    /**
     * Find the text content of the first element that match the CSS query.
     * Matched element may be the document, or any of its children.
     *
     * @param query A power-CSS query
     * @return The text content of the first element that match the query
     * @throws NoSuchElementException if no element matches the query
     * */
    @Throws(NoSuchElementException::class)
    fun selectFirstText(query: String) =
        selectFirstTextOrNull(query) ?: throw NoSuchElementException("No element matching $query")

    /**
     * Find the text content of the first element that match the CSS query.
     * Matched element may be the document, or any of its children.
     *
     * @param query A power-CSS query
     * @return The text content of the first element that match the query
     * */
    fun selectFirstTextOrNull(query: String) = document.selectFirstOrNull(query)?.text()

    /**
     * Find the text content of the first element that match the CSS query.
     * Matched element may be the document, or any of its children.
     *
     * @param query A power-CSS query
     * @return The text content of the first element that match the query
     * */
    fun selectFirstTextOptional(query: String) = Optional.ofNullable(selectFirstTextOrNull(query))

    /**
     * Find the attribute value of the first element that match the CSS query.
     * Matched element may be the document, or any of its children.
     *
     * @param query A power-CSS query
     * @param attrName The attribute name
     * @param offset The offset of the first element to return
     * @param limit The maximum number of elements to return
     * @return A list of attribute values of elements that match the query
     * */
    @JvmOverloads
    fun selectAttributes(query: String, attrName: String, offset: Int = 1, limit: Int = Int.MAX_VALUE) =
        document.selectAttributes(query, attrName, offset, limit)

    /**
     * Find the attribute value of the first element that match the CSS query.
     * Matched element may be the document, or any of its children.
     *
     * @param query A power-CSS query
     * @param attrName The attribute name
     * @return The attribute value of the first element that match the query
     * */
    @JvmOverloads
    fun selectFirstAttribute(query: String, attrName: String, defaultValue: String = "") =
        selectFirstAttributeOrNull(query, attrName) ?: defaultValue

    /**
     * Find the attribute value of the first element that match the CSS query.
     * Matched element may be the document, or any of its children.
     *
     * @param query A power-CSS query
     * @param attrName The attribute name
     * @return The attribute value of the first element that match the query
     * */
    fun selectFirstAttributeOrNull(query: String, attrName: String) = selectFirstOrNull(query)?.attr(attrName)

    /**
     * Find the attribute value of the first element that match the CSS query.
     * Matched element may be the document, or any of its children.
     *
     * @param query A power-CSS query
     * @param attrName The attribute name
     * @return The attribute value of the first element that match the query
     * */
    fun selectFirstAttributeOptional(query: String, attrName: String) =
        Optional.ofNullable(selectFirstAttributeOrNull(query, attrName))

    /**
     * Find hyperlinks in elements matching the CSS query.
     *
     * @param query A power-CSS query
     * @param offset The offset of the first element to return
     * @param limit The maximum number of elements to return
     * @return A list of hyperlinks of elements that match the query
     * */
    @JvmOverloads
    fun selectHyperlinks(query: String, offset: Int = 1, limit: Int = Int.MAX_VALUE): List<Hyperlink> =
        document.selectHyperlinks(query, offset, limit)

    /**
     * Find anchor elements matching the CSS query.
     *
     * @param query A power-CSS query
     * @param offset The offset of the first element to return
     * @param limit The maximum number of elements to return
     * @return A list of anchor elements that match the query
     * */
    @JvmOverloads
    fun selectAnchors(query: String, offset: Int = 1, limit: Int = Int.MAX_VALUE): List<GeoAnchor> =
        document.selectAnchors(query, offset, limit)

    /**
     * Find image elements matching the CSS query.
     *
     * @param query A power-CSS query
     * @param offset The offset of the first element to return
     * @param limit The maximum number of elements to return
     * @return A list of image elements that match the query
     * */
    @JvmOverloads
    fun selectImages(query: String, offset: Int = 1, limit: Int = Int.MAX_VALUE): List<String> =
        document.selectImages(query, offset, limit)

    /**
     * Traverse the DOM and apply the [action] to each [Node].
     *
     * @param action The action to apply to each [Node]
     * */
    fun forEach(action: (Node) -> Unit) {
        NodeTraversor.traverse({ node: Node, _ -> action(node) }, document)
    }

    /**
     * Traverse the DOM and apply the [action] to each [Node] that matches [predicate].
     *
     * @param predicate The predicate to match [Node]
     * @param action The action to apply to each [Node]
     * */
    fun forEachMatching(predicate: (Node) -> Boolean, action: (Node) -> Unit) =
        document.forEachMatching(predicate, action)

    /**
     * Traverse the DOM and apply the [action] to each [Element].
     *
     * @param action The action to apply to each [Element]
     * */
    fun forEachElement(action: (Element) -> Unit) = document.forEachElement(true, action)

    /**
     * Traverse the DOM and apply the [action] to each [Element] that matches [predicate].
     *
     * @param predicate The predicate to match [Element]
     * @param action The action to apply to each [Element]
     * */
    fun forEachElementMatching(predicate: (Element) -> Boolean, action: (Element) -> Unit) =
        document.forEachElementMatching(predicate, action)

    /**
     * Count nodes matching [predicate].
     *
     * @param predicate The predicate to match [Node]
     * */
    fun count(predicate: (Node) -> Boolean = { true }) = document.count(predicate)

    /**
     * Count elements matching [predicate].
     *
     * @param predicate The predicate to match [Element]
     * */
    fun countElements(predicate: (Element) -> Boolean = { true }) = document.countElements(predicate)

    /**
     * Retrieves the feature with the given key.
     *
     * @param key The key of the feature
     * */
    fun getFeature(key: Int) = document.getFeature(key)

    /**
     * Format node features.
     *
     * @param featureKeys The keys of the features to format
     * */
    fun formatFeatures(vararg featureKeys: Int) = document.formatEachFeatures(*featureKeys)

    /**
     * Format named node features.
     * */
    fun formatNamedFeatures() = document.formatNamedFeatures()

    /**
     * Remove attributes associated with the given keys.
     * */
    fun removeAttrs(vararg attributeKeys: String) {
        NodeTraversor.traverse({ node: Node, _ -> node.extension.removeAttrs(*attributeKeys) }, document)
    }

    /**
     * Remove all script nodes in the document.
     * */
    fun removeScripts() {
        val removal = mutableSetOf<Node>()
        NodeTraversor.traverse({ node, _ -> if (node.nodeName() == "script") removal.add(node) }, document)
        removal.forEach { it.takeIf { it.hasParent() }?.remove() }
    }

    /**
     * Remove all script nodes in the document.
     * */
    fun stripScripts() = removeScripts()

    /**
     * Remove all style nodes from the document, and remove all style attributes from all elements.
     * */
    fun removeStyles() {
        val removal = mutableSetOf<Node>()
        NodeTraversor.traverse({ node, _ ->
            if (node.nodeName() == "style" || node.attr("type") == "text/css" || node.attr("ref") == "stylesheet") {
                removal.add(node)
            }
            node.removeAttr("style")
        }, document)
        removal.forEach { it.remove() }
    }

    fun stripStyles() = removeStyles()

    /**
     * Export the document.
     *
     * @return The path of the exported document
     * */
    fun export(): Path {
        val filename = AppPaths.fromUri(location, "", ".htm")
        val path = AppPaths.WEB_CACHE_DIR.resolve("featured").resolve(filename)
        return exportTo(path)
    }

    /**
     * Export the document to the given path.
     *
     * @param path The path to export the document
     * @return The path of the exported document
     * */
    fun exportTo(path: Path): Path {
        return AppFiles.saveTo(outerHtml.toByteArray(), path, deleteIfExists = true)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }

        return other is FeaturedDocument && location == other.location
    }

    /**
     * Get the hash code of the document.
     * */
    override fun hashCode() = location.hashCode()

    /**
     * Get the string representation of the document.
     * */
    override fun toString() = document.uniqueName

    private fun initialize() {
        // Only one thread is allow to access the document
        // NIL document might be accessed by multiple threads
        val threadId = Thread.currentThread().id
        document.threadIds.add(threadId)
        if (document.threadIds.size != 1) {
            val threads = document.threadIds.joinToString()
            System.err.println("Warning: multiple threads ($threads) are process document | $location")
        }

        if (document.isInitialized.compareAndSet(false, true)) {
            calculateFeatures()
        }

        document.threadIds.remove(threadId)
    }

    private fun calculateFeatures() {
        FeatureCalculatorFactory.calculator.calculate(document)
        require(features.isNotEmpty)

        document.unitArea = densityUnitArea
        document.primaryGrid = primaryGridDimension
        document.secondaryGrid = secondaryGridDimension
        document.grid = document.primaryGrid

        calculateInducedFeatures()
    }

    /**
     * Calculate features depend on other features
     * */
    private fun calculateInducedFeatures() {
        // Calculate text node density
        val unitArea = document.unitArea
        document.forEach {
            // add a smooth number to make sure the dividend is not zero
            it.textNodeDensity = 1.0 * it.numTextNodes / it.area.coerceAtLeast(1) * unitArea
        }
    }
}
