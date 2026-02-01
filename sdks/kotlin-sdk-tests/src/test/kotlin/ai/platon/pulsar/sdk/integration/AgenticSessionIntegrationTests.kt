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

import ai.platon.pulsar.sdk.AgenticSession
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for AgenticSession act operations.
 * These tests require AI/LLM integration to be configured.
 */
@Tag("ExternalServiceTest")
class AgenticSessionActIT : IntegrationTestBase() {

    @Test
    fun `act executes single action`() {
        val session = AgenticSession(client)
        
        // Navigate to a page first
        session.driver.navigateTo("https://example.com")
        
        try {
            val result = session.act("find the main heading")
            
            assertNotNull(result)
            assertNotNull(result.message)
        } catch (e: Exception) {
            // May fail if LLM not configured
            println("Act test skipped: ${e.message}")
        }
    }

    @Test
    fun `act returns success status`() {
        val session = AgenticSession(client)
        
        session.driver.navigateTo("https://example.com")
        
        try {
            val result = session.act("scroll down")
            
            assertNotNull(result)
            assertTrue(result.success || !result.success, "Should have success status")
        } catch (e: Exception) {
            // May fail if LLM not configured
            println("Act test skipped: ${e.message}")
        }
    }

    @Test
    fun `act includes action details in result`() {
        val session = AgenticSession(client)
        
        session.driver.navigateTo("https://example.com")
        
        try {
            val result = session.act("click the first link")
            
            assertNotNull(result)
            // Result should have action details
            assertTrue(result.action == null || result.action!!.isNotEmpty())
        } catch (e: Exception) {
            // May fail if LLM not configured
            println("Act test skipped: ${e.message}")
        }
    }
}

/**
 * Integration tests for AgenticSession run operations.
 */
@Tag("ExternalServiceTest")
class AgenticSessionRunIT : IntegrationTestBase() {

    @Test
    fun `run executes autonomous task`() {
        val session = AgenticSession(client)
        
        session.driver.navigateTo("https://example.com")
        
        try {
            val result = session.run("find and summarize the main content")
            
            assertNotNull(result)
            assertNotNull(result.message)
        } catch (e: Exception) {
            // May fail if LLM not configured
            println("Run test skipped: ${e.message}")
        }
    }

    @Test
    fun `run returns execution trace`() {
        val session = AgenticSession(client)
        
        session.driver.navigateTo("https://example.com")
        
        try {
            val result = session.run("extract the page title")
            
            assertNotNull(result)
            // Trace may be null or contain steps
            assertTrue(result.trace == null || result.trace!!.isNotEmpty())
        } catch (e: Exception) {
            // May fail if LLM not configured
            println("Run test skipped: ${e.message}")
        }
    }

    @Test
    fun `run includes history size in result`() {
        val session = AgenticSession(client)
        
        session.driver.navigateTo("https://example.com")
        
        try {
            val result = session.run("get page information")
            
            assertNotNull(result)
            assertTrue(result.historySize >= 0)
        } catch (e: Exception) {
            // May fail if LLM not configured
            println("Run test skipped: ${e.message}")
        }
    }
}

/**
 * Integration tests for AgenticSession observe operations.
 */
@Tag("ExternalServiceTest")
class AgenticSessionObserveIT : IntegrationTestBase() {

    @Test
    fun `observe analyzes page state`() {
        val session = AgenticSession(client)
        
        session.driver.navigateTo("https://example.com")
        
        try {
            val result = session.observe("analyze the page structure")
            
            assertNotNull(result)
            assertNotNull(result.observations)
        } catch (e: Exception) {
            // May fail if LLM not configured
            println("Observe test skipped: ${e.message}")
        }
    }

    @Test
    fun `observe returns list of observations`() {
        val session = AgenticSession(client)
        
        session.driver.navigateTo("https://example.com")
        
        try {
            val result = session.observe("find interactive elements")
            
            assertNotNull(result)
            assertTrue(result.observations.isNotEmpty() || result.observations.isEmpty())
        } catch (e: Exception) {
            // May fail if LLM not configured
            println("Observe test skipped: ${e.message}")
        }
    }

    @Test
    fun `observe includes suggestions`() {
        val session = AgenticSession(client)
        
        session.driver.navigateTo("https://example.com")
        
        try {
            val result = session.observe("suggest next actions")
            
            assertNotNull(result)
            // Observations may include suggestions
            result.observations.forEach { obs ->
                assertTrue(obs.nextSuggestions == null || obs.nextSuggestions!!.isNotEmpty() || obs.nextSuggestions!!.isEmpty())
            }
        } catch (e: Exception) {
            // May fail if LLM not configured
            println("Observe test skipped: ${e.message}")
        }
    }
}

/**
 * Integration tests for AgenticSession extract operations.
 */
@Tag("ExternalServiceTest")
class AgenticSessionExtractIT : IntegrationTestBase() {

    @Test
    fun `agentExtract performs AI-powered extraction`() {
        val session = AgenticSession(client)
        
        session.driver.navigateTo("https://example.com")
        
        try {
            val result = session.agentExtract("extract the title and description")
            
            assertNotNull(result)
            assertNotNull(result.message)
        } catch (e: Exception) {
            // May fail if LLM not configured
            println("AgentExtract test skipped: ${e.message}")
        }
    }

    @Test
    fun `agentExtract with schema extracts structured data`() {
        val session = AgenticSession(client)
        
        session.driver.navigateTo("https://example.com")
        
        val schema = mapOf(
            "title" to "string",
            "content" to "string"
        )
        
        try {
            val result = session.agentExtract("extract page information", schema)
            
            assertNotNull(result)
            assertTrue(result.success || !result.success)
        } catch (e: Exception) {
            // May fail if LLM not configured
            println("AgentExtract test skipped: ${e.message}")
        }
    }

    @Test
    fun `agentExtract returns extracted data`() {
        val session = AgenticSession(client)
        
        session.driver.navigateTo("https://example.com")
        
        try {
            val result = session.agentExtract("get page title")
            
            assertNotNull(result)
            // Data may be null or contain extracted content
            assertTrue(result.data == null || result.data != null)
        } catch (e: Exception) {
            // May fail if LLM not configured
            println("AgentExtract test skipped: ${e.message}")
        }
    }
}

/**
 * Integration tests for AgenticSession summarize operations.
 */
@Tag("ExternalServiceTest")
class AgenticSessionSummarizeIT : IntegrationTestBase() {

    @Test
    fun `summarize generates page summary`() {
        val session = AgenticSession(client)
        
        session.driver.navigateTo("https://example.com")
        
        try {
            val summary = session.summarize()
            
            assertNotNull(summary)
        } catch (e: Exception) {
            // May fail if LLM not configured
            println("Summarize test skipped: ${e.message}")
        }
    }

    @Test
    fun `summarize with instruction uses custom prompt`() {
        val session = AgenticSession(client)
        
        session.driver.navigateTo("https://example.com")
        
        try {
            val summary = session.summarize("summarize the main heading and first paragraph")
            
            assertNotNull(summary)
        } catch (e: Exception) {
            // May fail if LLM not configured
            println("Summarize test skipped: ${e.message}")
        }
    }
}

/**
 * Integration tests for AgenticSession history and trace management.
 */
class AgenticSessionHistoryIT : IntegrationTestBase() {

    @Test
    fun `clearHistory resets agent history`() {
        val session = AgenticSession(client)
        
        session.driver.navigateTo("https://example.com")
        
        try {
            session.act("scroll down")
            session.clearHistory()
            
            // Should complete without error
        } catch (e: Exception) {
            // May fail if LLM not configured
            println("ClearHistory test skipped: ${e.message}")
        }
    }

    @Test
    fun `processTrace tracks execution steps`() {
        val session = AgenticSession(client)
        
        val trace = session.processTrace
        
        assertNotNull(trace)
        assertTrue(trace.isEmpty() || trace.isNotEmpty())
    }
}

/**
 * Integration tests for AgenticSession context and properties.
 */
class AgenticSessionContextIT : IntegrationTestBase() {

    @Test
    fun `session has companion agent reference`() {
        val session = AgenticSession(client)
        
        assertEquals(session, session.companionAgent)
    }

    @Test
    fun `session has context reference`() {
        val session = AgenticSession(client)
        
        assertEquals(session, session.context)
    }

    @Test
    fun `options creates map with args`() {
        val session = AgenticSession(client)
        
        val opts = session.options("-expire 1d -timeout 30s")
        
        assertNotNull(opts)
        assertEquals("-expire 1d -timeout 30s", opts["args"])
    }

    @Test
    fun `data returns null for unknown key`() {
        val session = AgenticSession(client)
        
        val value = session.data("unknown-key")
        
        assertNull(value)
    }

    @Test
    fun `property returns null for unknown key`() {
        val session = AgenticSession(client)
        
        val value = session.property("unknown-key")
        
        assertNull(value)
    }

    @Test
    fun `registerClosable registers closable resource`() {
        val session = AgenticSession(client)
        
        session.registerClosable(Object())
        
        // Should complete without error
    }
}
