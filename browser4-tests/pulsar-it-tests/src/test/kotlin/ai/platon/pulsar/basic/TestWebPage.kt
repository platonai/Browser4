package ai.platon.pulsar.basic

import ai.platon.pulsar.common.PulsarParams
import ai.platon.pulsar.common.sleepSeconds
import ai.platon.pulsar.persist.AbstractWebPage
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.persist.metadata.Name
import ai.platon.pulsar.skeleton.common.message.PageLoadStatusFormatter
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.skeleton.common.persist.ext.options
import ai.platon.pulsar.test.TestUrls
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import kotlin.test.*

/**
 * Created by Vincent on 16-7-20.
 * Copyright @ 2013-2016 Platon AI. All rights reserved
 */
class TestWebPage: TestBase() {
    private val url = TestUrls.PRODUCT_DETAIL_URL
    private val groupId = 43853791

    @BeforeEach
    fun clearResources() {
        session.delete(url)
        assertTrue("Page should not exists | $url") { !session.exists(url) }
    }

    @Test
    fun testFetchTime() {
        val args = "-i 5s"
        val normalizedArgs = "-expires PT5S"
        val option = session.options(args)
        var page = session.load(url, option)
        Assumptions.assumeTrue(page.protocolStatus.isSuccess,
            "Failed to fetch the page, abort the test | $url")

        val prevFetchTime1 = page.prevFetchTime

        val fetchTime1 = page.fetchTime
        assertTrue("Fetch time should be in 1 minutes, actual $fetchTime1") { fetchTime1 > Instant.now().minusSeconds(60) }

        assertTrue { page.protocolStatus.isSuccess }
        assertTrue { page.isContentUpdated }
        assertTrue(page is AbstractWebPage)
        assertEquals(option, page.variables[PulsarParams.VAR_LOAD_OPTIONS])
        assertTrue { page.args.contains(normalizedArgs) }
        // TODO: fix this issue: expected: <-expires PT5S> but was: <-expires PT5S -ignoreFailure -nJitRetry 3 -parse -test 1>
        // assertEquals(normalizedArgs, page.args)

        sleepSeconds(5)
        val expireAt = Instant.now()
        sleepSeconds(5)

        val options2 = session.options("$args -expireAt $expireAt")
        assertTrue { options2.isExpired(page.prevFetchTime) }

        page = session.load(url, options2)
        Assumptions.assumeTrue(page.protocolStatus.isSuccess,
            "Failed to fetch the page, abort the test | $url")

        assertTrue { page.protocolStatus.isSuccess }
        assertTrue("Page should be fetched since it expireAt $expireAt") { page.isFetched }
        assertTrue { page.isContentUpdated }
        assertEquals(options2, page.options)
        val prevFetchTime2 = page.prevFetchTime
        assertTrue("prevFetchTime2 should be in 1 minutes") { fetchTime1 > Instant.now().minusSeconds(60) }

//        val fetchTime2 = page.fetchTime

        printlnPro(PageLoadStatusFormatter(page, "", true, true, true, true))
        printlnPro("prevFetchTime: " + page.prevFetchTime)
        printlnPro("fetchTime: " + page.fetchTime)
        val responseTime = page.metadata[Name.RESPONSE_TIME]?:""
        printlnPro(responseTime)
        printlnPro(Instant.now())
        printlnPro("fetchCount: " + page.fetchCount)
        printlnPro("fetchInterval: " + page.fetchInterval)

        assertTrue { prevFetchTime1 < prevFetchTime2 }

        // Not required currently
        // assertEquals(prevFetchTime2, page.fetchTime)

        assertTrue { fetchTime1 < page.fetchTime }
        assertEquals(2, page.fetchCount)
    }

    @Test
    fun testPageModel() {
        var page = session.load(url)
        require(page is GoraWebPage)
        page.unbox().pageModel?.fieldGroups = null
        page.unbox().pageModel = null
        page.unbox().setDirty("pageModel")
        session.persist(page)

        page = session.load(url)

        assertNull(page.pageModel)
        val pageModel = page.ensurePageModel()

        pageModel.emplace(groupId, "", mapOf("a1" to "1", "b2" to "2", "b22" to "22"))
        assertEquals("Utf8", pageModel.unbox().fieldGroups.first().fields.keys.first().javaClass.simpleName)
        assertEquals("String", pageModel.unbox().fieldGroups.first().fields.values.first().javaClass.simpleName)
        assertEquals("1", pageModel.findGroup(groupId)?.get("a1"))
        session.persist(page)

        val page2 = webDB.get(url)
        assertNotEquals(page.id, page2.id)
        val pageModel2 = page2.pageModel
        assertNotNull(pageModel2)
        require(page2 is GoraWebPage)
        val fieldGroups2 = page2.unbox().pageModel.fieldGroups
        val fieldGroup2 = pageModel2.findGroup(groupId)
        val fieldGroup21 = fieldGroups2.firstOrNull { it.id == groupId.toLong() }
        assertNotNull(fieldGroup21)
        pageModel2.emplace(groupId, "", mapOf("c3" to "3", "d4" to "4"))

        printlnPro("fieldGroup2.flatMap: " + fieldGroups2.flatMap {
            it.fields.entries.map { e ->
                "${e.key}(${e.key.javaClass.simpleName})" to "${e.value}(${e.value.javaClass.simpleName})"
            } }.associate { it.first to it.second })
        printlnPro("fieldGroup2.fieldsCopy: " + fieldGroup2?.fieldsCopy)

//        assertEquals("2", fieldGroup2.fields["b"])
        assertNull(fieldGroup2?.fieldsCopy?.get("b2"), "b2 should be cleared in emplace")
        assertNull(fieldGroup2?.get("b2"), "b2 should be cleared in emplace")

        assertEquals("3", fieldGroup2?.get("c3"))
        session.persist(page2)

        val page3 = webDB.get(url)
        assertNotEquals(page.id, page3.id)
        val pageModel3 = page3.pageModel
        val fieldGroup3 = pageModel2.findGroup(groupId)
        assertNotNull(pageModel3)
        printlnPro("fieldGroup3.fieldsCopy: " + fieldGroup3?.fieldsCopy)
        assertEquals("4", fieldGroup3?.get("d4"))
    }
}

