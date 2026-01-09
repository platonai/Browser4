package ai.platon.pulsar.persist.mongo

import ai.platon.pulsar.common.NetUtil
import ai.platon.pulsar.common.config.AppConstants
import ai.platon.pulsar.common.config.CapabilityTypes
import ai.platon.pulsar.common.config.MutableConfig
import ai.platon.pulsar.persist.DataStorageFactory
import ai.platon.pulsar.persist.gora.generated.GWebPage
import shaded.com.mongodb.MongoClient
import org.apache.commons.lang3.RandomStringUtils
import org.apache.gora.mongodb.store.MongoStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import kotlin.test.*

/**
 * The driver com.mongodb.MongoClient is a Legacy Driver.
 * Introduced In: MongoDB Java driver 3.x and earlier.
 *
 * The driver com.mongodb.client.MongoClient is a New Driver.
 * Introduced in: MongoDB Java Driver 3.7 and later.
 * */
// @Ignore("Only test when MongoDB is started")
class MongoClientLegacyTest {
    companion object {
        private val crawlId = RandomStringUtils.secure().nextAlphanumeric(18)
        private val conf = MutableConfig()

        private lateinit var mongoClient: MongoClient

        @BeforeAll
        @JvmStatic
        fun setupClass() {
            Assumptions.assumeTrue { NetUtil.testNetwork("localhost", 27017) }

            conf.set(CapabilityTypes.STORAGE_DATA_STORE_CLASS, AppConstants.MONGO_STORE_CLASS)
            conf.set(CapabilityTypes.STORAGE_CRAWL_ID, crawlId)
            mongoClient = MongoClient("localhost", 27017)
        }
    }

    val database get() = mongoClient.getDatabase(MongoTestBase.databaseName)

    val collection get() = database.getCollection(MongoTestBase.collectionName)

    @BeforeEach
    fun checkMongoDBConnection() {
        // test if MongoDB is available, if not, skip the test
        try {
            mongoClient.getDatabase("test").listCollectionNames().first()
        } catch (e: Exception) {
            // e.printStackTrace()
            Assumptions.assumeTrue(false, "MongoDB is not available: skip tests")
        }
    }

    @BeforeEach
    fun ensureCollectionDoesNotExist() {
        val collections = database.listCollectionNames()
        assertFalse { collections.contains(MongoTestBase.collectionName) }
    }

    @AfterEach
    fun dropCollection() {
        collection.drop()
    }

    @Test
    fun testRealSchema() {
        val store = MongoStore<String, GWebPage>()
        assertNull(store.schemaName)

        val provider = DataStorageFactory(conf)
        val store2 = provider.getOrCreatePageStore()
        assertEquals(AppConstants.MONGO_STORE_CLASS, provider.storeClassName)
        assertTrue("Actual schema name: ${store2.schemaName}") { crawlId in store2.schemaName }
    }
}
