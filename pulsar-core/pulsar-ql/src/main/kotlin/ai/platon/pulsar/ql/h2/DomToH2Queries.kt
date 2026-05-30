package ai.platon.pulsar.ql.h2

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.math.vectors.get
import ai.platon.pulsar.common.math.vectors.isEmpty
import ai.platon.pulsar.common.sleepSeconds
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.dom.FeaturedDocument
import ai.platon.pulsar.dom.features.FeatureRegistry.registeredFeatures
import ai.platon.pulsar.dom.features.NodeFeature.Companion.isFloating
import ai.platon.pulsar.dom.nodes.GeoAnchor
import ai.platon.pulsar.dom.select.appendSelectorIfMissing
import ai.platon.pulsar.dom.select.select
import ai.platon.pulsar.dom.select.selectFirstOrNull
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.persist.model.WebPageFormatter
import ai.platon.pulsar.ql.common.ResultSets
import ai.platon.pulsar.ql.common.types.ValueDom
import ai.platon.pulsar.skeleton.common.options.LoadOptions
import ai.platon.pulsar.skeleton.common.urls.NormURL
import ai.platon.pulsar.skeleton.session.PulsarSession
import ai.platon.pulsar.skeleton.workflow.common.url.CompletableListenableHyperlink
import org.apache.commons.math3.linear.RealVector
import org.h2.api.ErrorCode
import org.h2.message.DbException
import org.h2.tools.SimpleResultSet
import org.h2.value.DataType
import org.h2.value.Value
import org.h2.value.ValueArray
import org.h2.value.ValueString
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.sql.ResultSet
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

object DomToH2Queries {
    private val logger = getLogger(this::class)

    /**
     * Load all Web pages
     *
     * @param session The session
     * @param urls The urls to load, can be a single string represented by a [ValueString]
     * or an array of strings represented by a [ValueArray]
     * @return A collection of [WebPage]s
     */
    suspend fun loadAll(session: PulsarSession, urls: Value): Collection<WebPage> {
        var pages: Collection<WebPage> = listOf()

        when (urls) {
            is ValueString -> {
                val normURL = session.normalize(urls.string)
                pages = ArrayList()
                pages.add(session.load(normURL))
            }

            is ValueArray ->
                if (urls.list.isNotEmpty()) {
                    pages = session.loadAll(urls.list.mapTo(mutableSetOf()) { it.string })
                }

            else -> throw DbException.get(ErrorCode.METHOD_NOT_FOUND_1, "Unsupported type ${Value::class}")
        }

        return pages
    }

    /**
     * Load all Web pages, and translate Web pages to targets using the given transformer
     *
     * @param session        The session
     * @param configuredUrls The configured urls, can be a single string represented by a {@link ValueString},
     *                       or an array of strings represented by a {@link ValueArray}
     * @param cssQuery       The css query
     * @param offset         The offset
     * @param limit          The limit
     * @param transformer    The transformer used to translate a Web page into something else
     * @return A collection of O
     */
    suspend fun <O> loadAll(
        session: PulsarSession,
        configuredUrls: Value, restrictCss: String, offset: Int, limit: Int,
        transformer: (Element, String, Int, Int) -> Collection<O>
    ): Collection<O> {
        val collection: Collection<O>

        when (configuredUrls) {
            is ValueString -> {
                val doc = session.loadDocument(configuredUrls.string)
                collection = transformer(doc.document, restrictCss, offset, limit)
            }

            is ValueArray -> {
                collection = ArrayList()
                for (configuredUrl in configuredUrls.list) {
                    val doc = session.loadDocument(configuredUrl.string)
                    collection.addAll(transformer(doc.document, restrictCss, offset, limit))
                }
            }

            else -> throw DbException.get(ErrorCode.FUNCTION_NOT_FOUND_1, "Unknown custom type")
        }

        return collection
    }

    suspend fun loadOutPages(
        session: PulsarSession,
        portalUrl: String, restrictCss: String,
        offset: Int = 1, limit: Int = Int.MAX_VALUE,
        normalize: Boolean = true, ignoreQuery: Boolean = false
    ): Collection<WebPage> {
        val transformer = if (ignoreQuery) this::getLinksIgnoreQuery else this::getLinks

        val normURL = session.normalize(portalUrl)
        val limit2 = min(limit, normURL.options.topLinks)

        val document = session.loadDocument(normURL)
        var links =
            transformer(document.document, restrictCss, offset, Int.MAX_VALUE).filter { !URLUtils.isInternal(it) }

        if (normalize) {
            links = links.mapNotNull { session.normalizeOrNull(it)?.urlString }
        }

        val itemOptions = normURL.options.createItemOptions()
        val distinctLinks = session.normalize(links.toSet().take(limit2), itemOptions)

        return loadAll(session, distinctLinks)
    }

    /**
     * Load all pages specified by [normUrls], wait until all pages are loaded or timeout.
     * */
    private fun loadAll(
        session: PulsarSession,
        normUrls: Iterable<NormURL>
    ): List<WebPage> {
        if (!normUrls.iterator().hasNext()) {
            return listOf()
        }

        val futures = session.loadAllAsync(normUrls.distinctBy { it.urlString })

        logger.info("Waiting for {} completable hyperlinks | @{}", futures.size, futures.hashCode())

        val future = CompletableFuture.allOf(*futures.toTypedArray())
        future.join()

        val pages = futures.mapNotNull { it.get() }.filter { it.isNotInternal }

        logger.info("Finished {}/{} pages | @{}", pages.size, futures.size, futures.hashCode())

        return pages
    }

    /**
     * Load all pages specified by [normUrls], wait until all pages are loaded or timeout
     * */
    private fun loadAll2(
        session: PulsarSession,
        normUrls: Iterable<NormURL>,
        options: LoadOptions
    ): Collection<WebPage> {
        val globalCache = session.globalCache
        val queue = globalCache.urlPool.higher3Cache.reentrantQueue
        val timeoutSeconds = options.pageLoadTimeout.seconds + 1
        val links = normUrls
            .asSequence()
            .map { CompletableListenableHyperlink<WebPage>(it.urlString, args = it.args, href = it.hrefSpec) }
            .onEach { it.completeOnTimeout(GoraWebPage.NIL, timeoutSeconds, TimeUnit.SECONDS) }
            .toList()

        queue.addAll(links)
        logger.info(
            "Waiting for {} completable hyperlinks, {}@{}, {}", links.size,
            globalCache.javaClass, globalCache.hashCode(), globalCache.urlPool.hashCode()
        )

        var i = 90
        val pendingLinks = links.toMutableList()
        while (i-- > 0 && pendingLinks.isNotEmpty()) {
            val finishedLinks = pendingLinks.filter { it.isDone }
            if (finishedLinks.isNotEmpty()) {
                logger.debug("Has finished {} links", finishedLinks.size)
            }

            if (i % 30 == 0) {
                logger.debug("Still {} pending links", pendingLinks.size)
            }

            pendingLinks.removeIf { it.isDone }
            sleepSeconds(1)
        }

        // timeout process?
//        val future = CompletableFuture.allOf(*links.toTypedArray())
//        future.join()

        return links.filter { it.isDone }.mapNotNull { it.get() }.filter { it.isNotInternal }
    }

    /**
     * TODO: any type support, only array of strings are supported now
     * */
    fun <O> select(dom: ValueDom, cssQuery: String, transform: (Element) -> O): ValueArray {
        val values = dom.element.select(cssQuery) { ValueString.get(transform(it).toString()) }.toTypedArray()
        return ValueArray.get(values)
    }

    fun <O> selectFirstOrNull(dom: ValueDom, cssQuery: String, transformer: (Element) -> O): O? {
        val result = dom.element.selectFirstOrNull(cssQuery, transformer)
        if (result != null && result is Element) {
            // feature: mark element matching query
            // select first element matched
            // result.attr("sf-match")
        }
        return result
    }

    fun <O> selectNthOrNull(dom: ValueDom, cssQuery: String, n: Int, transform: (Element) -> O): O? {
        val result = dom.element.select(cssQuery, n, 1).firstOrNull()
        if (result != null) {
            // feature: mark element matching query
            // select n-th element matched
            // result.attr("sn-match")
            return transform(result)
        }
        return null
    }

    fun getTexts(ele: Element, restrictCss: String, offset: Int, limit: Int): Collection<String> {
        return ele.select(restrictCss, offset, limit) { it.text() }
    }

    fun getLinks(ele: Element, restrictCss: String, offset: Int, limit: Int): Collection<String> {
        val cssQuery = appendSelectorIfMissing(restrictCss, "a")
        return ele.select(cssQuery, offset, limit) { it.absUrl("href") }
    }

    fun getLinksIgnoreQuery(ele: Element, restrictCss: String, offset: Int, limit: Int): Collection<String> {
        val cssQuery = appendSelectorIfMissing(restrictCss, "a")
        return ele.select(cssQuery, offset, limit) {
            it.absUrl("href").takeIf { URLUtils.isStandard(it) }?.substringBefore("?")
        }.filterNotNull()
    }

    fun getFeatures(ele: Element, restrictCss: String, offset: Int, limit: Int): Collection<RealVector> {
        return ele.select(restrictCss, offset, limit) { it.extension.features }
    }

    fun toValueArray(elements: Elements): ValueArray {
        val values = arrayOfNulls<ValueDom>(elements.size)
        for (i in elements.indices) {
            values[i] = ValueDom.getOrNil(elements[i])
        }
        return ValueArray.get(values)
    }

    /**
     * Get a result set, the result set contains just one column DOM
     */
    fun <E> toResultSet(colName: String, collection: Iterable<E>): ResultSet {
        val rs = ResultSets.newSimpleResultSet()
        val colType = if (colName.equals("DOM", ignoreCase = true)) ValueDom.type else Value.STRING
        val sqlType = DataType.convertTypeToSQLType(colType)
        rs.addColumn(colName, sqlType, 0, 0)

        if (colType == ValueDom.type) {
            collection.forEach { rs.addRow(it) }
        } else {
            collection.forEach { e -> rs.addRow(ValueString.get(e.toString())) }
        }

        return rs
    }

    /**
     * Get a result set, the result set contains just one column DOM
     */
    fun toDOMResultSet(document: FeaturedDocument, elements: Collection<ValueDom>): ResultSet {
        val rs = ResultSets.newSimpleResultSet()
        val colType = ValueDom.type
        val sqlType = DataType.convertTypeToSQLType(colType)
        rs.addColumn("DOM", sqlType, 0, 0)
        rs.addColumn("DOC", sqlType, 0, 0)

        val docDOM = ValueDom.get(document)
        elements.forEach { rs.addRow(it, docDOM) }

        return rs
    }

    /**
     * Get result set for each field in Web page
     */
    fun toResultSet(anchors: Collection<GeoAnchor>): ResultSet {
        val rs = SimpleResultSet()
        rs.addColumns("URL", "TEXT", "PATH", "LEFT", "TOP", "WIDTH", "HEIGHT")

        anchors.forEach {
            rs.addRow(it.url, it.text, it.path, it.left, it.top, it.width, it.height)
        }

        return rs
    }

    /**
     * Get result set for each field in Web page
     */
    fun toResultSet(page: WebPage): ResultSet {
        val rs = SimpleResultSet()
        rs.addColumns("KEY", "VALUE")

        val fields = WebPageFormatter(page).toMap()
        for (entry in fields.entries) {
            val value = entry.value.toString()
            rs.addRow(entry.key, value)
        }

        return rs
    }

    /**
     * Get a row of data contains the DOM itself and all it's feature values
     * Every float feature has 2 fraction digits
     */
    fun getFeatureRow(ele: Element): Array<Any?> {
        val columnCount = 1 + registeredFeatures.size + 1
        val values = arrayOfNulls<Any>(columnCount)
        values[0] = ValueDom.get(ele)
        val features = if (!ele.extension.features.isEmpty) ele.extension.features else return values

        // TODO: configurable
        val base = 10f
        val fractionDigits = 2
        val factor = base.pow(fractionDigits)
        for (j in 1..registeredFeatures.size) {
            val key = j - 1
            val v = features[key]

            if (isFloating(key)) {
                values[j] = 1.0 * (factor * v).roundToInt() / factor
            } else {
                values[j] = v.toInt()
            }
        }

        return values
    }
}
