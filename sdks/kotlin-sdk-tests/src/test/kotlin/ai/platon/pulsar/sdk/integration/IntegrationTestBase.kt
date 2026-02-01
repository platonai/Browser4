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

import ai.platon.pulsar.boot.autoconfigure.PulsarContextConfiguration
import ai.platon.pulsar.sdk.PulsarClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import

/**
 * Base class for Kotlin SDK integration tests.
 * Starts a Spring Boot server and provides a PulsarClient for testing.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.main.allow-bean-definition-overriding=true"
    ]
)
@Import(PulsarContextConfiguration::class)
@Tag("IntegrationTest")
abstract class IntegrationTestBase {

    @LocalServerPort
    protected var serverPort: Int = 0

    protected lateinit var client: PulsarClient
    protected var sessionId: String? = null

    protected val baseUrl: String
        get() = "http://127.0.0.1:$serverPort"

    @BeforeEach
    fun setupClient() {
        client = PulsarClient(baseUrl = baseUrl)
        sessionId = client.createSession()
    }

    @AfterEach
    fun tearDownClient() {
        try {
            sessionId?.let { client.deleteSession(it) }
        } catch (e: Exception) {
            // Ignore cleanup errors
        } finally {
            client.close()
        }
    }
}
