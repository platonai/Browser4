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
package ai.platon.pulsar.persist

import ai.platon.pulsar.common.DateTimes
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.config.AppConstants.MEM_STORE_CLASS
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.config.VolatileConfig
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.persist.gora.generated.GWebPage
import ai.platon.pulsar.persist.model.GoraWebPage
import org.apache.avro.util.Utf8
import org.apache.commons.lang3.RandomStringUtils
import org.apache.gora.memory.store.MemStore
import org.apache.gora.store.DataStore
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.*

/**
 * Tests basic Gora functionality by writing and reading webpages.
 */
class TestGoraStorageInMemory {

    val LOG = LoggerFactory.getLogger(TestGoraStorage::class.java)
    val crawlId = "test_" + RandomStringUtils.secure().nextAlphabetic(4)

    private val conf = VolatileConfig().apply {
        set(CapabilityTypes.STORAGE_CRAWL_ID, crawlId)
        set(CapabilityTypes.STORAGE_DATA_STORE_CLASS, MEM_STORE_CLASS)
    }

    private lateinit var webDb: WebDb
    private val store: DataStore<String, GWebPage> get() = webDb.dataStorageFactory.getOrCreatePageStore()
    private var exampleUrl = AppConstants.EXAMPLE_URL + "/" + DateTimes.format(Instant.now(), "MMdd")

    @BeforeTest
    fun setup() {
        assertEquals(MEM_STORE_CLASS, conf.get(CapabilityTypes.STORAGE_DATA_STORE_CLASS))
        assertEquals(MEM_STORE_CLASS, DataStorageFactory.detectDataStoreClassName(conf))
        webDb = WebDb(conf)
//        assertTrue(store.javaClass.name) { store is MemStore }
        if (store is MemStore) {
            assertEquals("MemStore", store.schemaName)
        } else {
            assertTrue(store.schemaName) { store.schemaName.startsWith("test_") }
        }
        assertEquals(crawlId, conf.get(CapabilityTypes.STORAGE_CRAWL_ID))
        webDb.truncate(force = true)
    }

    @AfterTest
    fun teardown() {
        webDb.delete(exampleUrl)
        webDb.flush()
        webDb.close()
        LOG.debug("get '{}', '{}'", store.schemaName, URLUtils.reverseUrlOrEmpty(exampleUrl))
    }

    /**
     * Sequentially read and write pages to a store.
     */
    @Test
    fun testSingleThreadReadWriteGoraWebPage() {
        if (store !is MemStore) {
            return
        }

        val id = "testSingleThreadReadWriteGoraWebPage"
        readWriteGoraWebPage(id, store)
    }

    @Test
    fun testSingleThreadReadWriteWebPage() {
        if (store !is MemStore) {
            return
        }

        val id = "testSingleThreadReadWriteWebPage"
        readWriteWebPage(id, store, conf)
    }

    /**
     * Tests multiple thread reading and writing to the same store, this should be
     * no problem because [DataStore] implementations claim to be thread
     * safe.
     */
    @Test
    fun testMultithreaded() { // create a fixed thread pool
        if (store !is MemStore) {
            return
        }

        val numThreads = 8
        val pool = Executors.newFixedThreadPool(numThreads)
        // define a list of tasks
        val tasks: ArrayList<Callable<Int>> = ArrayList()
        for (i in 0 until numThreads) {
            tasks.add(Callable task@{
                try { // run a sequence
                    readWriteGoraWebPage(Thread.currentThread().name, store)
                    return@task 0
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@task 1
                }
            })
        }
        // submit them at once
        val results = pool.invokeAll(tasks)
        // check results
        for (result in results) {
            assertEquals(0, result.get())
        }
    }

    companion object {

        private fun readWriteGoraWebPage(id: String, store: DataStore<String, GWebPage>) {
            var page = GWebPage.newBuilder().build()
            val max = 100
            for (i in 0 until max) {
                // store a page with title
                val key = "key-$id-$i"
                val title = "title-$i"
                page.pageTitle = Utf8(title)
                store.put(key, page)
                store.flush()
                // retrieve page and check title
                page = store.get(key)
                assertNotNull(page)
                assertEquals(title, page.pageTitle.toString())
            }

            // scan over the rows
            val result = store.execute(store.newQuery())
            var count = 0
            while (result.next()) {
                if (result.key.contains(id)) {
                    ++count
                }
            }

            // check amount
            assertEquals(max, count)
        }

        private fun readWriteWebPage(id: String, store: DataStore<String, GWebPage>, conf: VolatileConfig) {
            val max = 100
            for (i in 0 until max) {
                val url = AppConstants.SHORTEST_VALID_URL + "/" + id + "/" + i
                var page = GoraWebPage.newWebPage(url, conf)
                page.location = url
                page.pageText = "text"
                page.distance = 0
                page.headers.put("header1", "header1")
                page.inlinks["http://www.a.com/1"] = ""
                page.inlinks["http://www.a.com/2"] = ""
                store.put(url, page.unbox())
                store.flush()

                // retrieve page and check title
                val goraPage = store.get(url)
                assertNotNull(goraPage)
                page = GoraWebPage.box(url, goraPage, conf)
                assertEquals("text", page.pageText)
                assertEquals(0, page.distance.toLong())
                assertEquals("header1", page.headers["header1"])
                assertEquals(2, page.inlinks.size.toLong())
            }

            // scan over the rows
            val result = store.execute(store.newQuery())
            var count = 0
            while (result.next()) {
                if (result.key.contains(id)) {
                    ++count
                }
            }
            assertEquals(max, count)
        }
    }
}
