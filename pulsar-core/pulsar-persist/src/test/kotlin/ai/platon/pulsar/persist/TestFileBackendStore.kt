package ai.platon.pulsar.persist

import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.persist.gora.FileBackendPageStore
import ai.platon.pulsar.persist.model.GoraWebPage
import ai.platon.pulsar.test.TestResourceUtil
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.RandomUtils
import java.nio.file.Files
import kotlin.test.*

class TestFileBackendStore {
    private val url = TestResourceUtil.PRODUCT_DETAIL_URL
    private val persistDirectory = AppPaths.TEST_DIR.resolve("unittests/TestFileBackendStore")
    private val store = FileBackendPageStore(persistDirectory)
    private lateinit var page: WebPage

    @BeforeTest
    fun setup() {
        page = WebPageExt.newTestWebPage(url)
    }

    @AfterTest
    fun tearDown() {
        runCatching { FileUtils.deleteDirectory(persistDirectory.toFile()) }.onFailure { it.printStackTrace() }
    }

    @Test
    fun whenWritePage_ThenAvroFileExists() {
        val path = store.getPersistPath(url, ".avro")
        store.writeAvro(page)
        assertTrue { Files.exists(path) }

        val key = URLUtils.reverseUrl(url)
        val loadedPage = store.readAvro(key)
        assertNotNull(loadedPage)
    }

    @Test
    fun whenWritePage_ThenReadSuccess() {
        store.writeAvro(page)

        val key = URLUtils.reverseUrl(url)
        val loadedPage = store.readAvro(key)
        assertNotNull(loadedPage)
    }

    @Test
    fun whenUpdatePageModel_ThenSuccess() {
        val url2 = url + "/" + RandomUtils.secure().randomInt()
        val page2 = WebPageExt.newTestWebPage(url2)
        val groupId = 100
        page2.ensurePageModel().emplace(100, mapOf("a" to "1", "b" to "2"))
        store.writeAvro(page2)

        val key = URLUtils.reverseUrl(url2)
        val loadedGPage = store.readAvro(key)
        assertNotNull(loadedGPage)
        val loadedPage = GoraWebPage.box(url2, loadedGPage, VolatileConfig.UNSAFE)
        assertNotNull(loadedPage)
        var pageModel = loadedPage.pageModel
        assertNotNull(pageModel)

        val group = pageModel.findGroup(groupId)
        assertNotNull(group)
        assertEquals("1", group["a"])
        assertEquals("2", group["b"])
        assertNull(group["c"])

        // updating
        pageModel.put(groupId, "c", "3")
        store.writeAvro(loadedPage)

        val key2 = URLUtils.reverseUrl(url2)
        // check the updated version
        val loadedGPage2 = store.readAvro(key2)
        assertNotNull(loadedGPage2)
        val loadedPage2 = GoraWebPage.box(url2, loadedGPage2, VolatileConfig.UNSAFE)
        pageModel = loadedPage2.pageModel
        assertNotNull(pageModel)

        assertEquals("3", loadedPage2.ensurePageModel().findValue(groupId, "c"))

        // clear
        pageModel.findGroup(groupId)?.clear()
        store.writeAvro(loadedPage2)
        val loadedGPage3 = store.readAvro(key2)
        assertNotNull(loadedGPage3)
        val loadedPage3 = GoraWebPage.box(url2, loadedGPage3, VolatileConfig.UNSAFE)
        val group3 = loadedPage3.ensurePageModel().findGroup(groupId)
        assertNotNull(group3)
        assertNull(group3["c"])
    }
}
