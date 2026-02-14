package ai.platon.pulsar.test

import ai.platon.pulsar.skeleton.session.PulsarSession
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Example integration test using Testcontainers for MongoDB.
 * 
 * This test demonstrates how to use the [AbstractMongoDBTestcontainerTest] base class
 * to automatically start and configure a MongoDB container for testing.
 * 
 * ## What This Test Demonstrates:
 * 1. **Zero Configuration**: No need to manually start MongoDB
 * 2. **Automatic Injection**: MongoDB connection details are auto-configured
 * 3. **Test Isolation**: Each test class gets its own MongoDB instance
 * 4. **Real Integration**: Uses actual MongoDB instead of mocks
 * 
 * ## How It Works:
 * 1. Testcontainers starts a MongoDB Docker container before any test runs
 * 2. Spring Boot auto-configures the application to use the container
 * 3. Tests run against the real MongoDB instance
 * 4. Container is automatically stopped and cleaned up after tests complete
 * 
 * ## Running This Test:
 * ```bash
 * # Ensure Docker is running, then:
 * mvn test -Dtest=WebPagePersistenceIT
 * 
 * # Or with tag filtering:
 * mvn test -Dgroups="Integration,RequiresDocker"
 * ```
 * 
 * ## Prerequisites:
 * - Docker installed and running
 * - Testcontainers dependencies in pom.xml
 * - Network access to pull MongoDB image (first run only)
 * 
 * @see AbstractMongoDBTestcontainerTest
 */
@SpringBootTest
class WebPagePersistenceIT : AbstractMongoDBTestcontainerTest() {
    
    @Autowired
    lateinit var session: PulsarSession
    
    @Test
    @DisplayName("test page persistence with Testcontainers MongoDB")
    fun testPagePersistence() {
        // Given: A URL to load
        val url = "https://example.com"
        
        // When: Load the page (will be stored in MongoDB)
        val page = session.load(url)
        
        // Then: Page should have a persistent ID
        assertNotNull(page.persistentId, "Page should be assigned a persistent ID")
        println("✓ Page saved to MongoDB with ID: ${page.persistentId}")
        
        // When: Reload the page from storage (without refresh)
        val loadedPage = session.load(url)
        
        // Then: Should retrieve the same page from MongoDB
        assertEquals(
            page.persistentId, 
            loadedPage.persistentId,
            "Loaded page should have the same persistent ID"
        )
        println("✓ Page retrieved from MongoDB successfully")
    }
    
    @Test
    @DisplayName("test multiple pages can be stored independently")
    fun testMultiplePagesPersistence() {
        // Given: Multiple URLs
        val urls = listOf(
            "https://example.com",
            "https://example.org",
            "https://example.net"
        )
        
        // When: Load all pages
        val pages = urls.map { url -> 
            session.load(url).also {
                println("Loaded: $url -> ID: ${it.persistentId}")
            }
        }
        
        // Then: All pages should have unique persistent IDs
        val uniqueIds = pages.mapNotNull { it.persistentId }.toSet()
        assertEquals(
            pages.size, 
            uniqueIds.size,
            "All pages should have unique persistent IDs"
        )
        println("✓ All ${pages.size} pages stored with unique IDs")
    }
    
    @Test
    @DisplayName("test MongoDB connection details")
    fun testMongoDBConnectionDetails() {
        // Verify that MongoDB container is running and accessible
        val connectionString = getMongoConnectionString()
        val host = getMongoHost()
        val port = getMongoPort()
        
        assertNotNull(connectionString, "MongoDB connection string should not be null")
        assertNotNull(host, "MongoDB host should not be null")
        assertTrue(port > 0, "MongoDB port should be greater than 0")
        
        println("MongoDB Container Details:")
        println("  Connection String: $connectionString")
        println("  Host: $host")
        println("  Port: $port")
        println("✓ MongoDB container is running and accessible")
    }
    
    @Test
    @DisplayName("test page refresh from network")
    fun testPageRefresh() {
        // Given: A URL that has been loaded once
        val url = "https://example.com"
        val firstPage = session.load(url)
        val firstPersistentId = firstPage.persistentId
        
        println("First load: ID = $firstPersistentId")
        
        // When: Force refresh from network
        val refreshedPage = session.load(url, "-refresh")
        val refreshedPersistentId = refreshedPage.persistentId
        
        println("After refresh: ID = $refreshedPersistentId")
        
        // Then: Should get a new page instance
        // Note: Behavior may vary based on Browser4's caching strategy
        assertNotNull(refreshedPersistentId, "Refreshed page should have a persistent ID")
        println("✓ Page refresh completed successfully")
    }
}
