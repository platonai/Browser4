package ai.platon.pulsar.heavy.ql

import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.sql.SQLTemplate
import ai.platon.pulsar.persist.WebPage
import ai.platon.pulsar.ql.h2.DomToH2Queries
import ai.platon.pulsar.ql.h2.utils.ResultSetUtils
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Created by Vincent on 17-7-29.
 * Copyright @ 2013-2023 Platon AI. All rights reserved.
 */
class TestDomToH2Queries: TestBase() {

    private val portalUrl = "https://www.amazon.com/Best-Sellers/zgbs"
    private val args = "-i 10d -ii 50d -ol a[href~=/dp/] -ignoreFailure"
    private val url = "$portalUrl $args"
    private val restrictCss = "a[href~=/dp/]"

    @org.junit.jupiter.api.Test
    suspend fun testLoadOutPages() {
        val limit = 20
        val pages = DomToH2Queries.loadOutPages(session, url, restrictCss, 1, limit)
        pages.map { it.url }.distinct().forEachIndexed { i, url -> printlnPro("$i.\t$url") }
        assertTrue("Page size: " + pages.size) { pages.size <= limit }
    }

    @Ignore("BufferUnderflowException on Ubuntu (maybe caused by hardware limit)")
    @Test
    fun testLoadOutPagesInParallel() {
        val parallel = 5
        val limit = 10

        val executor = Executors.newWorkStealingPool()
        val futures = IntRange(1, parallel).map {
            executor.submit<Collection<WebPage>> {
                val pages = runBlocking { DomToH2Queries.loadOutPages(session, url, restrictCss, 1, limit) }
                pages.map { it.url }.distinct().forEachIndexed { i, url -> printlnPro("$i.\t$url") }
                assertTrue("Page size: " + pages.size) { pages.size <= limit }
                pages
            }
        }
        val pageLists = futures.map { it.get() }

        assertEquals(pageLists.size, parallel)
        pageLists.forEach {
            assertTrue { it.size <= limit }
        }
    }


    @Test
    fun testExtractUrlFromFromClause() {
        val urls = listOf(
            "http://amazon.com/a/reviews/123?pageNumber=21&a=b",
            """https://www.amazon.com/s?k="Boys^27+Novelty+Belt+Buckles"&rh=n:9057119011&page=1""",
            """https://www.amazon.com/s?k="Boys%27+Novelty+Belt+Buckles"&rh=n:9057119011&page=1""",
            """https://www.amazon.com/s?k="Boys%27+Novelty+Belt+Buckles"&rh=n:9057119011&page=1 -i 1h -retry"""
        )
        val sqlTemplates = listOf(
            """
                select dom_first_text(dom, '#container'), dom_first_text(dom, '.price')
                from load_and_select(@url, ':root body');
            """,
            """
                select dom_first_text(dom, '#container'), dom_first_text(dom, '.price')
                from load_and_select(     @url, ':root body');
            """,
            """
                select dom_first_text(dom, '#container'), dom_first_text(dom, '.price')
                from load_and_select(     @url     , ':root body');
            """,
            """
                select dom_first_text(dom, 'div:contains(https://www.amazon.com/)'), dom_first_text(dom, '.price')
                FROM LOAD_AND_SELECT(     @url     , ':root body');
            """
        ).map { it.trimIndent() }

        urls.forEach { url ->
            sqlTemplates.map { template -> SQLTemplate(template).createSQL(url) }.forEach { sql ->
                val actualUrl = ResultSetUtils.extractUrlFromFromClause(sql)
                assertEquals(url, actualUrl, sql)
            }
        }
    }
}
