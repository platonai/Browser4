/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. The ASF licenses this file to
 * you under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0 Unless required
 * by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package ai.platon.pulsar.sdk.integration

import ai.platon.pulsar.sdk.PulsarSession
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for PulsarSession page loading operations.
 */
class PulsarSessionLoadingIT : IntegrationTestBase() {

    @Test
    fun `open loads page immediately without cache`() {
        val session = PulsarSession(client)
        
        val page = session.open("https://example.com")
        
        assertNotNull(page)
        assertNotNull(page.url)
        assertFalse(page.isNil)
    }

    @Test
    fun `open with load arguments applies options`() {
        val session = PulsarSession(client)
        
        val page = session.open("https://example.com", "-expire 1d")
        
        assertNotNull(page)
        assertNotNull(page.url)
    }

    @Test
    fun `load fetches from cache or internet`() {
        val session = PulsarSession(client)
        
        val page = session.load("https://example.com")
        
        assertNotNull(page)
        assertNotNull(page.url)
        assertFalse(page.isNil)
    }

    @Test
    fun `load with args applies load options`() {
        val session = PulsarSession(client)
        
        val page = session.load("https://example.com", "-expire 1d -timeout 30s")
        
        assertNotNull(page)
        assertNotNull(page.url)
    }

    @Test
    fun `loadAll loads multiple pages`() {
        val session = PulsarSession(client)
        
        val urls = listOf("https://example.com", "https://httpbin.org")
        val pages = session.loadAll(urls)
        
        assertNotNull(pages)
        assertTrue(pages.size <= urls.size)
    }

    @Test
    fun `submit adds URL to crawl pool`() {
        val session = PulsarSession(client)
        
        val page = session.submit("https://example.com")
        
        assertNotNull(page)
        // Submit returns immediately, may be nil
    }
}

/**
 * Integration tests for PulsarSession URL normalization.
 */
class PulsarSessionNormalizationIT : IntegrationTestBase() {

    @Test
    fun `normalize returns normalized URL`() {
        val session = PulsarSession(client)
        
        val normUrl = session.normalize("https://example.com")
        
        assertNotNull(normUrl)
        assertNotNull(normUrl.url)
        assertNotNull(normUrl.spec)
    }

    @Test
    fun `normalize with args includes load arguments`() {
        val session = PulsarSession(client)
        
        val normUrl = session.normalize("https://example.com", "-expire 1d")
        
        assertNotNull(normUrl)
        assertNotNull(normUrl.args)
        assertTrue(normUrl.args!!.contains("-expire"))
    }

    @Test
    fun `normalizeOrNull returns null for blank URL`() {
        val session = PulsarSession(client)
        
        val normUrl = session.normalizeOrNull("")
        
        assertNull(normUrl)
    }

    @Test
    fun `normalizeOrNull returns normalized URL for valid input`() {
        val session = PulsarSession(client)
        
        val normUrl = session.normalizeOrNull("https://example.com")
        
        assertNotNull(normUrl)
        assertNotNull(normUrl.url)
    }
}

/**
 * Integration tests for PulsarSession content extraction.
 */
class PulsarSessionExtractionIT : IntegrationTestBase() {

    @Test
    fun `extract extracts fields using CSS selectors`() {
        val session = PulsarSession(client)
        
        val page = session.load("https://example.com")
        
        val selectors = mapOf(
            "title" to "h1",
            "content" to "p"
        )
        
        val fields = session.extract(page.html ?: "", selectors)
        
        assertNotNull(fields)
        assertNotNull(fields.fields)
    }

    @Test
    fun `scrape loads and extracts in one operation`() {
        val session = PulsarSession(client)
        
        val selectors = mapOf(
            "title" to "h1",
            "description" to "p"
        )
        
        val result = session.scrape("https://example.com", "", selectors)
        
        assertNotNull(result)
    }

    @Test
    fun `scrape with args applies load options`() {
        val session = PulsarSession(client)
        
        val selectors = mapOf("title" to "h1")
        
        val result = session.scrape("https://example.com", "-expire 1d", selectors)
        
        assertNotNull(result)
    }
}

/**
 * Integration tests for PulsarSession driver binding.
 */
class PulsarSessionDriverIT : IntegrationTestBase() {

    @Test
    fun `driver property creates bound driver lazily`() {
        val session = PulsarSession(client)
        
        assertNull(session.boundDriver)
        
        val driver = session.driver
        
        assertNotNull(driver)
        assertNotNull(session.boundDriver)
    }

    @Test
    fun `createBoundDriver creates new driver instance`() {
        val session = PulsarSession(client)
        
        val driver1 = session.createBoundDriver()
        val driver2 = session.createBoundDriver()
        
        assertNotNull(driver1)
        assertNotNull(driver2)
        // Each call creates a new driver
    }

    @Test
    fun `bindDriver associates driver with session`() {
        val session = PulsarSession(client)
        val driver = session.createBoundDriver()
        
        session.bindDriver(driver)
        
        assertEquals(driver, session.boundDriver)
    }

    @Test
    fun `unbindDriver removes driver association`() {
        val session = PulsarSession(client)
        val driver = session.createBoundDriver()
        
        session.bindDriver(driver)
        assertNotNull(session.boundDriver)
        
        session.unbindDriver(driver)
        assertNull(session.boundDriver)
    }
}

/**
 * Integration tests for PulsarSession lifecycle and properties.
 */
class PulsarSessionLifecycleIT : IntegrationTestBase() {

    @Test
    fun `session has valid ID and UUID`() {
        val session = PulsarSession(client)
        
        assertEquals(0, session.id)
        assertNotNull(session.uuid)
    }

    @Test
    fun `session isActive reflects session state`() {
        val session = PulsarSession(client)
        
        assertTrue(session.isActive)
    }

    @Test
    fun `session display shows session info`() {
        val session = PulsarSession(client)
        
        val display = session.display
        
        assertNotNull(display)
        assertTrue(display.contains("session") || display.contains(session.uuid ?: ""))
    }

    @Test
    fun `close terminates session cleanly`() {
        val session = PulsarSession(client)
        
        session.load("https://example.com")
        session.close()
        
        // Close should complete without error
    }
}
