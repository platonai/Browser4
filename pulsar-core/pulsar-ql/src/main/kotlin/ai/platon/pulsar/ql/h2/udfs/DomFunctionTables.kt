package ai.platon.pulsar.ql.h2.udfs

import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.dom.features.FeatureRegistry
import ai.platon.pulsar.dom.features.NodeFeature
import ai.platon.pulsar.dom.features.defined.SIB
import ai.platon.pulsar.dom.nodes.node.ext.getFeature
import ai.platon.pulsar.dom.select.select
import ai.platon.pulsar.dom.select.select2
import ai.platon.pulsar.dom.select.selectAnchors
import ai.platon.pulsar.ql.common.ResultSets
import ai.platon.pulsar.ql.common.annotation.H2Context
import ai.platon.pulsar.ql.common.annotation.UDFGroup
import ai.platon.pulsar.ql.common.annotation.UDFunction
import ai.platon.pulsar.ql.common.types.ValueDom
import ai.platon.pulsar.ql.h2.DomToH2Queries
import ai.platon.pulsar.ql.h2.DomToH2Queries.toDOMResultSet
import ai.platon.pulsar.ql.h2.DomToH2Queries.toResultSet
import ai.platon.pulsar.ql.h2.H2SessionFactory
import ai.platon.pulsar.ql.h2.domValue
import kotlinx.coroutines.runBlocking
import org.h2.jdbc.JdbcConnection
import org.h2.tools.SimpleResultSet
import org.h2.value.DataType
import org.h2.value.Value
import org.h2.value.ValueArray
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.sql.ResultSet
import java.util.*
import kotlin.math.max

@Suppress("unused")
@UDFGroup(namespace = "DOM")
object DomFunctionTables {

    /**
     * Load all urls
     * For example:
     * CALL loadAll(ARRAY('http://...1', 'http://...2', 'http://...3'));
     */
    @JvmStatic
    @UDFunction(hasShortcut = true, description = "Load all pages specified by the given urls")
    fun loadAll(@H2Context conn: JdbcConnection, urls: ValueArray): ResultSet {
        val session = H2SessionFactory.getSession(conn)
        if (session.isColumnRetrieval(conn)) {
            return toResultSet("DOM", listOf<ValueDom>())
        }

        val pages = runBlocking { DomToH2Queries.loadAll(session, urls) }
        val doms = pages.map { session.parseValueDom(it) }

        return toResultSet("DOM", doms)
    }

    @UDFunction(hasShortcut = true, description = "Load a page and select the specified element by cssQuery")
    @JvmStatic
    @JvmOverloads
    fun loadAndSelect(
        @H2Context conn: JdbcConnection,
        url: String, cssQuery: String, offset: Int = 1, limit: Int = Integer.MAX_VALUE
    ): ResultSet {
        val session = H2SessionFactory.getSession(conn)
        if (session.isColumnRetrieval(conn)) {
            return toDOMResultSet(FeaturedDocument.NIL, listOf())
        }

        val document = runBlocking { session.loadDocument(url) }
        val elements = document.select(cssQuery, offset, limit)
        return toDOMResultSet(document, elements.map { domValue(it) })
    }

    @JvmStatic
    @JvmOverloads
    @UDFunction(description = "Select all elements by cssQuery")
    fun select(
        @H2Context conn: JdbcConnection,
        dom: ValueDom,
        cssQuery: String,
        offset: Int = 1,
        limit: Int = Integer.MAX_VALUE
    ): ResultSet {
        val session = H2SessionFactory.getSession(conn)
        if (session.isColumnRetrieval(conn)) {
            return toResultSet("DOM", listOf<ValueDom>())
        }

        val doms = dom.element.select(cssQuery, offset, limit) { ValueDom.get(it) }
        return toResultSet("DOM", doms)
    }

    @JvmStatic
    @JvmOverloads
    @UDFunction(hasShortcut = true, description = "Load a page and extract all links inside all the selected elements")
    fun loadAndGetLinks(
        @H2Context conn: JdbcConnection,
        portalUrl: Value, restrictCss: String = ":root", offset: Int = 1, limit: Int = Integer.MAX_VALUE
    ): ResultSet {
        val session = H2SessionFactory.getSession(conn)
        if (session.isColumnRetrieval(conn)) {
            return toResultSet("LINK", listOf<String>())
        }

        val links = runBlocking {
            DomToH2Queries.loadAll(
                session,
                portalUrl,
                restrictCss,
                offset,
                limit,
                DomToH2Queries::getLinks
            )
        }
        return toResultSet("LINK", links)
    }

    @JvmOverloads
    @JvmStatic
    @UDFunction(description = "Get all links inside the all selected elements")
    fun links(
        @H2Context conn: JdbcConnection,
        dom: ValueDom, cssQuery: String = ":root", offset: Int = 1, limit: Int = Integer.MAX_VALUE
    ): ResultSet {
        val session = H2SessionFactory.getSession(conn)
        if (session.isColumnRetrieval(conn)) {
            return toResultSet("LINK", listOf<String>())
        }

        return toResultSet("LINK", DomToH2Queries.getLinks(dom.element, cssQuery, offset, limit))
    }

    @JvmStatic
    @JvmOverloads
    @UDFunction(hasShortcut = true, description = "Load a page and find all anchors specified by cssQuery")
    fun loadAndGetAnchors(
        @H2Context conn: JdbcConnection,
        portalUrl: String, restrictCss: String = ":root", offset: Int = 1, limit: Int = Integer.MAX_VALUE
    ): ResultSet {
        val session = H2SessionFactory.getSession(conn)
        if (session.isColumnRetrieval(conn)) {
            return toResultSet(listOf())
        }

        val doc = runBlocking { session.loadDocument(portalUrl) }
        val anchors = doc.document.selectAnchors(restrictCss, offset, limit)

        return toResultSet(anchors)
    }

    /**
     * Load portalUrl page(s) and follow all links
     *
     * @param portalUrl The portalUrl page(s) to fetch,
     * a portalUrl can be
     * 1) a configured urls, or
     * 2) a String[]
     * 3) a List
     * @return The [ResultSet]
     */
    @JvmOverloads
    @JvmStatic
    @UDFunction(hasShortcut = true, description = "Load out pages from a portal url")
    fun loadOutPages(
        @H2Context conn: JdbcConnection,
        portalUrl: String,
        restrictCss: String = ":root",
        offset: Int = 1,
        limit: Int = Integer.MAX_VALUE,
        normalize: Boolean = true
    ): ResultSet {
        return loadOutPagesAsRsInternal(conn, portalUrl, restrictCss, offset, limit, normalize = normalize)
    }

    @UDFunction(
        hasShortcut = true,
        description = "Load out pages from a portal url, ignore url queries in the target url"
    )
    @JvmOverloads
    @JvmStatic
    fun loadOutPagesIgnoreUrlQuery(
        @H2Context conn: JdbcConnection,
        portalUrl: String,
        restrictCss: String = ":root",
        offset: Int = 1,
        limit: Int = Int.MAX_VALUE,
        normalize: Boolean = true
    ): ResultSet {
        return loadOutPagesAsRsInternal(
            conn,
            portalUrl, restrictCss, offset, limit, normalize = normalize, ignoreQuery = true
        )
    }

    /**
     * Load portalUrl page(s) and follow all links
     *
     * a portalUrl can be
     * 1) a configured urls, or
     * 2) a String[]
     * 3) a List
     * @return The [ResultSet]
     */
    @UDFunction(hasShortcut = true, description = "Load out pages from a portal url, and select the specified element")
    @JvmOverloads
    @JvmStatic
    fun loadOutPagesAndSelect(
        @H2Context conn: JdbcConnection,
        portal: String,
        restrictCss: String = ":root",
        offset: Int = 1,
        limit: Int = Integer.MAX_VALUE,
        targetCss: String = ":root",
        normalize: Boolean = true,
        ignoreQuery: Boolean = false
    ): ResultSet {
        val session = H2SessionFactory.getSession(conn)
        if (session.isColumnRetrieval(conn)) {
            return toResultSet("DOM", listOf<ValueDom>())
        }

        val docs = runBlocking {
            DomToH2Queries.loadOutPages(session, portal, restrictCss, offset, limit, normalize, ignoreQuery)
                .map { session.parse(it) }
        }

        val elements = if (targetCss == ":root") {
            docs.map { it.document }
        } else {
            docs.flatMap { it.select(targetCss) }
        }

        return toResultSet("DOM", elements.filterNotNull().map { domValue(it) })
    }

    /**
     * Load portalUrl page(s) and follow all links
     *
     * @param portalUrl The portalUrl page(s) to fetch,
     * a portalUrl can be
     * 1) a configured urls, or
     * 2) a String[]
     * 3) a List
     * @return The [ResultSet]
     */
    @UDFunction(hasShortcut = true, description = "Load out pages from a portal url, and select the specified element")
    @JvmOverloads
    @JvmStatic
    fun loadOutPagesAndSelectFirst(
        @H2Context conn: JdbcConnection,
        portalUrl: String,
        restrictCss: String = ":root",
        offset: Int = 1,
        limit: Int = Integer.MAX_VALUE,
        targetCss: String = ":root",
        normalize: Boolean = true,
        ignoreQuery: Boolean = false
    ): ResultSet {
        return loadOutPagesAsRsInternal(
            conn,
            portalUrl,
            restrictCss,
            offset,
            limit,
            targetCss = targetCss,
            normalize = normalize,
            ignoreQuery = ignoreQuery
        )
    }

    private fun loadOutPagesAsRsInternal(
        @H2Context conn: JdbcConnection,
        portalUrl: String,
        restrictCss: String = ":root",
        offset: Int = 1,
        limit: Int = Int.MAX_VALUE,
        targetCss: String = ":root",
        normalize: Boolean = true,
        ignoreQuery: Boolean = false
    ): ResultSet {
        val session = H2SessionFactory.getSession(conn)
        if (session.isColumnRetrieval(conn)) {
            return toResultSet("DOM", listOf<ValueDom>())
        }

        val pages = runBlocking {
            DomToH2Queries.loadOutPages(
                session,
                portalUrl,
                restrictCss,
                offset,
                limit,
                normalize,
                ignoreQuery
            )
        }

        val docs = pages.map { session.parse(it) }

        val elements = if (targetCss == ":root") {
            docs.map { it.document }
        } else {
            docs.map { it.selectFirst(targetCss) }
        }

        return toResultSet("DOM", elements.asIterable().filterNotNull().map { domValue(it) })
    }

    /**
     * Get the feature
     *
     * @param portalUrl The configured url
     * @return The [ResultSet] with element features for all match elements
     */
    @UDFunction(hasShortcut = true, description = "Load a page and show the features of it's elements")
    @JvmStatic
    @JvmOverloads
    fun loadAndGetFeatures(
        @H2Context conn: JdbcConnection,
        portalUrl: String,
        cssQuery: String = "DIV,P,UL,OL,LI,DL,DT,DD,TABLE,TR,TD,H1,H2,H3",
        offset: Int = 1,
        limit: Int = 100
    ): ResultSet {
        val session = H2SessionFactory.getSession(conn)
        val page = runBlocking { session.load(portalUrl) }
        val dom = if (page.isNil) ValueDom.NIL else session.parseValueDom(page)
        return features(conn, dom, cssQuery, offset, limit)
    }

    /**
     * @param dom         Element to parse
     * @param cssSelector css selector
     * @param offset      offset of element set, start at 1
     * @param limit       limit of element set
     * @return The [ResultSet] with element features for all match elements
     */
    @UDFunction(description = "Get the features of the given element")
    @JvmOverloads
    @JvmStatic
    fun features(
        @H2Context conn: JdbcConnection,
        dom: ValueDom,
        cssSelector: String = "DIV,P,UL,OL,LI,DL,DT,DD,TABLE,TR,TD",
        offset: Int = 1,
        limit: Int = 100
    ): ResultSet {
        val session = H2SessionFactory.getSession(conn)

        val rs = createFeatureResultSet()
        if (session.isColumnRetrieval(conn)) {
            return rs
        }

        if (dom.isNil) {
            return rs
        }

        /**
         * Notice: be careful use rs.addRow(*it) to make sure a vararg is passed into rs.addRow
         */
        dom.element.select(cssSelector, offset, limit) { DomToH2Queries.getFeatureRow(it) }
            .forEach { rs.addRow(*it) }

        return rs
    }

    /**
     * Get the feature
     *
     * @param portalUrl The configured url
     * @return The [ResultSet] with element features for all match elements
     */
    @UDFunction(hasShortcut = true, description = "Load and get the elements with most siblings")
    @JvmOverloads
    @JvmStatic
    fun loadAndGetElementsWithMostSibling(
        @H2Context conn: JdbcConnection,
        portalUrl: String,
        restrictCss: String = "DIV,P,UL,OL,LI,DL,DT,DD,TABLE,TR,TD",
        offset: Int = 1,
        limit: Int = Integer.MAX_VALUE
    ): ResultSet {
        val session = H2SessionFactory.getSession(conn)
        val page = runBlocking { session.load(portalUrl) }
        val dom = if (page.isNil) ValueDom.NIL else session.parseValueDom(page)
        return getElementsWithMostSibling(conn, dom, restrictCss, offset, limit)
    }

    /**
     * @param dom         Element to parse
     * @param restrictCss css selector
     * @param offset      offset of element set, start at 1
     * @param limit       limit of element set
     * @return The [ResultSet] with element features for all match elements
     */
    @UDFunction(hasShortcut = true, description = "Get the elements with most siblings of the given element")
    @JvmOverloads
    @JvmStatic
    fun getElementsWithMostSibling(
        @H2Context conn: JdbcConnection,
        dom: ValueDom,
        restrictCss: String = "DIV,P,UL,OL,LI,DL,DT,DD,TABLE,TR,TD",
        offset: Int = 1,
        limit: Int = Integer.MAX_VALUE
    ): ResultSet {
        val session = H2SessionFactory.getSession(conn)
        val rs = createFeatureResultSet()
        if (session.isColumnRetrieval(conn)) {
            return rs
        }

        if (dom.isNil) {
            return rs
        }

        val elements = dom.element.select2(restrictCss)

        // traversal on each of the selected elements, add all descendants to result
        val result = Elements()
        elements.traverse { node, _ ->
            if (node is Element) {
                result.add(node)
            }
        }

        val drop = max(offset - 1, 0)
        result.sortedByDescending { it.getFeature(SIB) }.asSequence()
            .drop(drop).take(limit)
            .map { DomToH2Queries.getFeatureRow(it) }
            .forEach { rs.addRow(it) }

        return rs
    }

    private fun createFeatureResultSet(): SimpleResultSet {
        val rs = ResultSets.newSimpleResultSet()

        rs.addColumn("DOM", DataType.convertTypeToSQLType(ValueDom.type), 0, 0)
        for (name in FeatureRegistry.featureNames) {
            if (NodeFeature.isFloating(name)) {
                val type = DataType.convertTypeToSQLType(Value.DOUBLE)
                rs.addColumn(name.uppercase(Locale.getDefault()), type, 0, 0)
            } else {
                val type = DataType.convertTypeToSQLType(Value.INT)
                rs.addColumn(name.uppercase(Locale.getDefault()), type, 0, 0)
            }
        }

        return rs
    }
}
