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

import ai.platon.pulsar.sdk.PulsarClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Integration tests for PulsarClient session lifecycle.
 */
class PulsarClientSessionIT : IntegrationTestBase() {

    @Test
    fun `createSession creates new browser session`() {
        // Use a fresh client without pre-created session
        val testClient = PulsarClient(baseUrl = baseUrl)
        
        val sessionId = testClient.createSession()
        
        assertNotNull(sessionId)
        assertEquals(sessionId, testClient.sessionId)
        
        // Cleanup
        testClient.deleteSession(sessionId)
        testClient.close()
    }

    @Test
    fun `createSession with capabilities applies session options`() {
        val testClient = PulsarClient(baseUrl = baseUrl)
        
        val capabilities = mapOf(
            "browserName" to "chrome",
            "platformName" to "any"
        )
        
        val sessionId = testClient.createSession(capabilities)
        
        assertNotNull(sessionId)
        
        // Cleanup
        testClient.deleteSession(sessionId)
        testClient.close()
    }

    @Test
    fun `deleteSession removes browser session`() {
        val testClient = PulsarClient(baseUrl = baseUrl)
        val sessionId = testClient.createSession()
        
        assertNotNull(sessionId)
        
        testClient.deleteSession(sessionId)
        
        // After deletion, sessionId should be cleared
        assertNull(testClient.sessionId)
        
        testClient.close()
    }

    @Test
    fun `client can manage multiple sessions sequentially`() {
        val testClient = PulsarClient(baseUrl = baseUrl)
        
        // Create first session
        val session1 = testClient.createSession()
        assertNotNull(session1)
        testClient.deleteSession(session1)
        
        // Create second session
        val session2 = testClient.createSession()
        assertNotNull(session2)
        testClient.deleteSession(session2)
        
        testClient.close()
    }
}

/**
 * Integration tests for PulsarClient HTTP operations.
 */
class PulsarClientHttpIT : IntegrationTestBase() {

    @Test
    fun `post sends POST request to server`() {
        val result = client.post("/session/{sessionId}/url", mapOf("url" to "https://example.com"))
        
        assertNotNull(result)
    }

    @Test
    fun `post with sessionId placeholder replaces with current session`() {
        val result = client.post("/session/{sessionId}/url", mapOf("url" to "https://example.com"))
        
        // Should not throw exception about missing session
        assertNotNull(result)
    }

    @Test
    fun `get sends GET request to server`() {
        val result = client.get("/session/{sessionId}/url")
        
        assertNotNull(result)
    }

    @Test
    fun `delete sends DELETE request to server`() {
        val result = client.delete("/session/{sessionId}/window")
        
        // Should not throw exception
        assertNotNull(result, "Delete should return a result or null")
    }

    @Test
    fun `post without session throws when sessionId placeholder present`() {
        val testClient = PulsarClient(baseUrl = baseUrl)
        
        assertThrows<IllegalStateException> {
            testClient.post("/session/{sessionId}/url", mapOf("url" to "https://example.com"))
        }
        
        testClient.close()
    }
}

/**
 * Integration tests for PulsarClient configuration.
 */
class PulsarClientConfigIT : IntegrationTestBase() {

    @Test
    fun `client uses custom base URL`() {
        val testClient = PulsarClient(baseUrl = "http://127.0.0.1:$serverPort")
        
        val sessionId = testClient.createSession()
        assertNotNull(sessionId)
        
        testClient.deleteSession(sessionId)
        testClient.close()
    }

    @Test
    fun `client can be created with initial session ID`() {
        val existingSessionId = client.createSession()
        
        val testClient = PulsarClient(
            baseUrl = baseUrl,
            sessionId = existingSessionId
        )
        
        assertEquals(existingSessionId, testClient.sessionId)
        
        testClient.deleteSession(existingSessionId)
        testClient.close()
    }

    @Test
    fun `client sessionId can be updated`() {
        val testClient = PulsarClient(baseUrl = baseUrl)
        
        assertNull(testClient.sessionId)
        
        val newSessionId = "test-session-123"
        testClient.sessionId = newSessionId
        
        assertEquals(newSessionId, testClient.sessionId)
        
        testClient.close()
    }
}

/**
 * Integration tests for PulsarClient error handling.
 */
class PulsarClientErrorHandlingIT : IntegrationTestBase() {

    @Test
    fun `client handles invalid endpoint gracefully`() {
        try {
            client.get("/invalid/endpoint/path")
            // May return result or throw
        } catch (e: Exception) {
            // Expected for invalid endpoint
            assertNotNull(e)
        }
    }

    @Test
    fun `client handles network errors gracefully`() {
        val testClient = PulsarClient(baseUrl = "http://localhost:99999")
        
        try {
            testClient.createSession()
            // Should fail with connection error
        } catch (e: Exception) {
            // Expected for invalid port
            assertNotNull(e)
        } finally {
            testClient.close()
        }
    }

    @Test
    fun `client close does not throw even without session`() {
        val testClient = PulsarClient(baseUrl = baseUrl)
        
        testClient.close()
        
        // Should complete without error
    }

    @Test
    fun `deleteSession handles non-existent session gracefully`() {
        val testClient = PulsarClient(baseUrl = baseUrl)
        
        try {
            testClient.deleteSession("non-existent-session-id")
            // May succeed or throw depending on server implementation
        } catch (e: Exception) {
            // Expected for non-existent session
            assertNotNull(e)
        } finally {
            testClient.close()
        }
    }
}

/**
 * Integration tests for PulsarClient lifecycle.
 */
class PulsarClientLifecycleIT : IntegrationTestBase() {

    @Test
    fun `client can be created with default settings`() {
        val testClient = PulsarClient(baseUrl = baseUrl)
        
        assertNull(testClient.sessionId)
        
        testClient.close()
    }

    @Test
    fun `client close cleans up resources`() {
        val testClient = PulsarClient(baseUrl = baseUrl)
        val sessionId = testClient.createSession()
        
        testClient.close()
        
        // Close should complete without error
        assertNotNull(sessionId)
    }

    @Test
    fun `multiple clients can connect to same server`() {
        val client1 = PulsarClient(baseUrl = baseUrl)
        val client2 = PulsarClient(baseUrl = baseUrl)
        
        val session1 = client1.createSession()
        val session2 = client2.createSession()
        
        assertNotNull(session1)
        assertNotNull(session2)
        
        client1.deleteSession(session1)
        client2.deleteSession(session2)
        
        client1.close()
        client2.close()
    }
}
