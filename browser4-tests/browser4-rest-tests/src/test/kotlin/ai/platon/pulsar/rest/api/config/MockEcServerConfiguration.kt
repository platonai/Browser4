package ai.platon.pulsar.rest.api.config

import ai.platon.pulsar.test.server.MockSiteApplication
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.SpringApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.ComponentScan
import java.net.ServerSocket

/**
 * Test configuration that automatically starts and stops the mock EC server for tests.
 * The mock EC server runs on port 8182 and provides mock Amazon-like product pages.
 */
@TestConfiguration
@ComponentScan(basePackages = ["ai.platon.pulsar.test.server"])
class MockEcServerConfiguration : InitializingBean, DisposableBean {

    private val log = LoggerFactory.getLogger(javaClass)
    private var mockServerContext: ConfigurableApplicationContext? = null
    private var isServerStarted = false

    companion object {
        const val MOCK_SERVER_PORT = 18080
        const val MOCK_SERVER_STARTUP_TIMEOUT_MS = 60000L // 60 seconds
    }

    private var serverThread: Thread? = null

    override fun afterPropertiesSet() {
        startMockEcServer()
    }

    override fun destroy() {
        stopMockEcServer()
    }

    private fun startMockEcServer() {
        if (isPortInUse(MOCK_SERVER_PORT)) {
            log.info("Port $MOCK_SERVER_PORT is already in use, assuming mock EC server is running")
            isServerStarted = true
            return
        }

        try {
            log.info("Starting embedded mock EC server on port $MOCK_SERVER_PORT...")

            // Create a new Spring application context for the mock server
            val app = SpringApplication(MockSiteApplication::class.java)

            // Set the properties BEFORE calling run() to ensure they override any defaults
            val properties = mapOf(
                "server.port" to MOCK_SERVER_PORT.toString(),
                "spring.main.banner-mode" to "off",
                "logging.level.root" to "WARN",
                "logging.level.ai.platon.pulsar.test.server" to "INFO",
                "spring.main.allow-bean-definition-overriding" to "true",
                "spring.main.web-application-type" to "servlet"
            )

            // Use setDefaultProperties to override application.properties
            app.setDefaultProperties(properties)

            // Also set as program arguments for extra assurance
            app.setAddCommandLineProperties(true)

            // Set environment properties as well for maximum compatibility
            System.setProperty("server.port", MOCK_SERVER_PORT.toString())

            // Start the application in a separate thread to avoid blocking
            serverThread = Thread {
                try {
                    log.info(
                        "Starting MockSiteApplication with properties: server.port=${System.getProperty("server.port")}, default properties: $properties"
                    )
                    mockServerContext = app.run()
                    log.info("Mock EC server application context created successfully")

                    // Log the actual port being used
                    val environment = mockServerContext?.environment
                    val actualPort = environment?.getProperty("server.port") ?: "unknown"
                    log.info("Mock EC server is running on port: $actualPort")
                } catch (e: Exception) {
                    log.error("Error starting mock EC server application", e)
                }
            }
            serverThread?.name = "mock-ec-server"
            serverThread?.isDaemon = true
            serverThread?.start()

            // Wait for server to start with longer timeout
            val checkInterval = 1000L // Check every second
            var attempts = 0
            val maxAttempts = 60 // 60 seconds total

            while (attempts < maxAttempts) {
                if (isPortInUse(MOCK_SERVER_PORT)) {
                    log.info("Mock EC server started successfully on port $MOCK_SERVER_PORT after ${attempts + 1} attempts")
                    isServerStarted = true
                    return
                }

                Thread.sleep(checkInterval)
                attempts++

                if (attempts % 10 == 0) {
                    log.info("Still waiting for mock EC server to start... (attempt $attempts/$maxAttempts)")
                }
            }

            log.error("Mock EC server failed to start within timeout (${maxAttempts} seconds)")
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

    private fun isPortInUse(port: Int): Boolean {
        return try {
            ServerSocket(port).use { false }
        } catch (e: java.net.BindException) {
            true
        }
    }
}
