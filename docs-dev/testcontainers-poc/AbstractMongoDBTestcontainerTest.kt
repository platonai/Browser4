package ai.platon.pulsar.test

import org.junit.jupiter.api.Tag
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Abstract base class for integration tests that require MongoDB.
 * 
 * This class uses Testcontainers to automatically start and manage a MongoDB container
 * for each test class. The container is shared across all tests in the class to minimize
 * startup overhead.
 * 
 * ## Benefits:
 * - **Zero Configuration**: MongoDB is automatically started before tests
 * - **Test Isolation**: Each test class gets its own MongoDB instance
 * - **Consistent Environment**: Same MongoDB version across all developer machines
 * - **CI/CD Ready**: Works in any environment with Docker installed
 * 
 * ## Usage:
 * ```kotlin
 * class MyIntegrationTest : AbstractMongoDBTestcontainerTest() {
 *     
 *     @Autowired
 *     lateinit var session: PulsarSession
 *     
 *     @Test
 *     fun testMongoDBIntegration() {
 *         val page = session.load("https://example.com")
 *         assertNotNull(page.persistentId)
 *     }
 * }
 * ```
 * 
 * ## Configuration:
 * The MongoDB container properties are automatically injected into Spring's environment:
 * - `spring.data.mongodb.uri` — MongoDB connection URI
 * - `gora.mongodb.servers` — MongoDB server address for Gora
 * 
 * ## Performance:
 * - Container startup: ~5-10 seconds per test class
 * - Container reuse: Set `withReuse(true)` to share across test classes
 * 
 * @see Testcontainers
 * @see MongoDBContainer
 */
@SpringBootTest
@Testcontainers
@Tag("Integration")
@Tag("RequiresDocker")
@Tag("Slow") // Container startup adds 5-10 seconds
abstract class AbstractMongoDBTestcontainerTest {
    
    companion object {
        private const val MONGODB_VERSION = "mongo:7.0"
        
        /**
         * MongoDB container instance shared across all tests in this class.
         * 
         * The container is started once before the first test and stopped after
         * the last test completes.
         * 
         * **Container Reuse**: 
         * Set `withReuse(true)` to share the container across test classes. This
         * significantly improves test execution speed but requires Testcontainers
         * Ryuk to be enabled.
         */
        @Container
        @JvmStatic
        val mongodb: MongoDBContainer = MongoDBContainer(MONGODB_VERSION)
            .withExposedPorts(27017)
            // Uncomment to enable container reuse across test classes:
            // .withReuse(true)
        
        /**
         * Dynamically configures Spring properties with MongoDB connection details.
         * 
         * This method is called by Spring before the ApplicationContext is created,
         * allowing us to inject the container's dynamically assigned host and port.
         * 
         * @param registry Spring's dynamic property registry
         */
        @JvmStatic
        @DynamicPropertySource
        fun mongoProperties(registry: DynamicPropertyRegistry) {
            // Spring Data MongoDB URI
            registry.add("spring.data.mongodb.uri") { 
                mongodb.replicaSetUrl 
            }
            
            // Apache Gora MongoDB server configuration
            registry.add("gora.mongodb.servers") { 
                "${mongodb.host}:${mongodb.firstMappedPort}" 
            }
            
            // Optional: Configure database name
            registry.add("gora.mongodb.db") { 
                "testdb" 
            }
        }
        
        /**
         * Gets the MongoDB connection string for manual client creation.
         * 
         * @return MongoDB connection URI (e.g., "mongodb://localhost:54321/test")
         */
        @JvmStatic
        fun getMongoConnectionString(): String = mongodb.replicaSetUrl
        
        /**
         * Gets the MongoDB host address.
         * 
         * @return MongoDB host (e.g., "localhost")
         */
        @JvmStatic
        fun getMongoHost(): String = mongodb.host
        
        /**
         * Gets the MongoDB mapped port.
         * 
         * @return MongoDB port (e.g., 54321)
         */
        @JvmStatic
        fun getMongoPort(): Int = mongodb.firstMappedPort
    }
}
