package ai.platon.pulsar.rest.api.config

import ai.platon.pulsar.test.server.MockSiteApplication
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.SpringApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ConfigurableApplicationContext

/**
 * Test configuration that automatically starts and stops the mock EC server for tests.
 *
 * The mock server binds to a random OS-assigned port (port 0). The actual port is
 * communicated to test code via the `mock.server.port` system property, which can be
 * read through [ai.platon.pulsar.test.server.MockServerPorts].
 */
@TestConfiguration
class MockEcServerConfiguration : InitializingBean, DisposableBean {

    private val log = LoggerFactory.getLogger(javaClass)
    private var mockServerContext: ConfigurableApplicationContext? = null
    private var isServerStarted = false

    companion object {
        /** Port 0 = OS-assigned random port for parallel test safety. */
        const val MOCK_SERVER_PORT = 0
        const val MOCK_SERVER_STARTUP_TIMEOUT_MS = 60000L // 60 seconds
        const val MOCK_SERVER_PORT_PROPERTY = "mock.server.port"
    }

    private var serverThread: Thread? = null

    override fun afterPropertiesSet() {
        startMockEcServer()
    }

    override fun destroy() {
        stopMockEcServer()
    }

    private fun startMockEcServer() {
        // If mock.server.port is already set, assume a server is already running
        val existingPort = System.getProperty(MOCK_SERVER_PORT_PROPERTY)
        if (existingPort != null) {
            log.info("Mock server port already set to $existingPort, assuming mock EC server is running")
            isServerStarted = true
            return
        }

        try {
            log.info("Starting embedded mock EC server on a random port...")

            // Create a new Spring application context for the mock server
            val app = SpringApplication(MockSiteApplication::class.java)

            // Set the properties BEFORE calling run() to ensure they override any defaults
            app.setDefaultProperties(
                mapOf(
                    "server.port" to MOCK_SERVER_PORT.toString(),
                    "spring.main.banner-mode" to "off",
                    "logging.level.root" to "WARN",
                    "logging.level.ai.platon.pulsar.test.server" to "INFO",
                    "spring.main.allow-bean-definition-overriding" to "true",
                    "spring.main.web-application-type" to "servlet"
                )
            )

            // Start the application in a separate daemon thread to avoid blocking
            serverThread = Thread {
                try {
                    log.info("Starting MockSiteApplication with port 0 (random)...")
                    mockServerContext = app.run()

                    // Read the actual bound port and communicate it to test code
                    val environment = mockServerContext?.environment
                    val resolvedPort = environment?.getProperty("local.server.port")?.toIntOrNull()
                        ?: environment?.getProperty("server.port")?.toIntOrNull()
                        ?: error("Could not determine mock EC server port after startup")
                    System.setProperty(MOCK_SERVER_PORT_PROPERTY, resolvedPort.toString())
                    log.info("Mock EC server started on port $resolvedPort")
                } catch (e: Exception) {
                    log.error("Error starting mock EC server application", e)
                    // Set a sentinel value so the main thread doesn't wait forever
                    System.setProperty(MOCK_SERVER_PORT_PROPERTY, "-1")
                }
            }
            serverThread?.name = "mock-ec-server"
            serverThread?.isDaemon = true
            serverThread?.start()

            // Wait for the server to bind (poll for the system property)
            val deadline = System.currentTimeMillis() + MOCK_SERVER_STARTUP_TIMEOUT_MS
            var attempts = 0
            while (System.currentTimeMillis() < deadline) {
                val portStr = System.getProperty(MOCK_SERVER_PORT_PROPERTY)
                if (portStr != null) {
                    val port = portStr.toIntOrNull() ?: -1
                    if (port > 0) {
                        isServerStarted = true
                        log.info("Mock EC server started successfully on port $port after ${attempts + 1} polls")
                        return
                    } else {
                        // Sentinel -1 = daemon thread failed
                        log.error("Mock EC server daemon thread reported failure (port=$port)")
                        stopMockEcServer()
                        return
                    }
                }
                Thread.sleep(500)
                attempts++
                if (attempts % 10 == 0) {
                    log.info("Still waiting for mock EC server to start... ({}s elapsed)", (attempts * 500) / 1000)
                }
            }

            log.error("Mock EC server failed to start within timeout (${MOCK_SERVER_STARTUP_TIMEOUT_MS}ms)")
            stopMockEcServer()
        } catch (e: Exception) {
            log.error("Failed to start mock EC server", e)
            stopMockEcServer()
        }
    }

    private fun stopMockEcServer() {
        mockServerContext?.let { context ->
            try {
                log.info("Stopping embedded mock EC server...")
                context.close()
                log.info("Embedded mock EC server stopped")
            } catch (e: Exception) {
                log.error("Error stopping embedded mock EC server", e)
            }
        }

        serverThread?.let { thread ->
            try {
                if (thread.isAlive) {
                    log.info("Interrupting mock EC server thread...")
                    thread.interrupt()
                }
            } catch (e: Exception) {
                log.error("Error interrupting mock EC server thread", e)
            }
        }

        mockServerContext = null
        serverThread = null
        isServerStarted = false
    }
}
