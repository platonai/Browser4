package ai.platon.pulsar.persist

import ai.platon.pulsar.common.AppContext
import ai.platon.pulsar.common.IllegalApplicationStateException
import ai.platon.pulsar.common.ResourceLoader
import ai.platon.pulsar.common.Runtimes
import ai.platon.pulsar.common.config.AppConstants.*
import ai.platon.pulsar.common.config.CapabilityTypes.STORAGE_DATA_STORE_CLASS
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.persist.gora.GoraStorage
import ai.platon.pulsar.persist.gora.generated.GWebPage
import ai.platon.pulsar.persist.mongo.MongoDBUtils
import org.apache.commons.lang3.SystemUtils
import org.apache.gora.mongodb.store.MongoStoreParameters.PROP_MONGO_SERVERS
import org.apache.gora.persistency.Persistent
import org.apache.gora.store.DataStore
import org.apache.gora.util.GoraException
import org.apache.hadoop.conf.Configuration
import org.slf4j.LoggerFactory

/**
 * Created by vincent on 19-1-19.
 * Copyright @ 2013-2019 Platon AI. All rights reserved
 */
class DataStorageFactory(conf: ImmutableConfig) {
    private val hadoopConf = HadoopUtils.toHadoopConfiguration(conf)
    private val pageStoreClass: Class<out DataStore<String, GWebPage>> get() = detectDataStoreClass(hadoopConf)

    private var _dataStore: DataStore<String, GWebPage>? = null

    val storeClassName: String get() = detectDataStoreClassName(hadoopConf)

    @get:Synchronized
    val schemaName: String get() = _dataStore?.schemaName ?: "(unknown, not initialized)"

    @Synchronized
    fun isInitialized() = _dataStore != null

    @Synchronized
    fun canConnect() = runCatching { _dataStore?.schemaExists() == true }.getOrNull() ?: false

    @Synchronized
    fun getOrCreatePageStore(): DataStore<String, GWebPage> {
        if (_dataStore == null) {
            _dataStore = createPageStore0()
        }
        return _dataStore!!
    }

    private fun createPageStore0(): DataStore<String, GWebPage> {
        if (!AppContext.isActive) {
            throw IllegalApplicationStateException("Inactive application context")
        }

        val pageStore = GoraStorage.createDataStore(hadoopConf, String::class.java, GWebPage::class.java, pageStoreClass)
        logger.debug("Backend data store is created: {}, realSchema: {}", pageStoreClass.name, pageStore.schemaName)
        return pageStore
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DataStorageFactory::class.java)

        fun detectDataStoreClassName(conf: ImmutableConfig): String {
            return detectDataStoreClassName(HadoopUtils.toHadoopConfiguration(conf))
        }

        /**
         * Return the DataStore persistent class used to persist WebPage.
         *
         * @param conf AppConstants configuration
         * @return the DataStore persistent class
         */
        fun detectDataStoreClassName(conf: HadoopConfiguration): String {
            if (!AppContext.isActive) {
                throw IllegalApplicationStateException("Inactive application context")
            }

            val specified = conf.get(STORAGE_DATA_STORE_CLASS)
            if (specified != null) {
                return specified
            }

            patchGoraMongoServersConfig(conf)
            val mongoServers = conf.get(PROP_MONGO_SERVERS)
            if (mongoServers != null) {
                return MONGO_STORE_CLASS
            }

            var dataStoreClass = when {
                SystemUtils.IS_OS_WINDOWS -> when {
                    Runtimes.checkIfProcessRunning(".*mongod.exe .+") -> MONGO_STORE_CLASS
                    else -> FILE_BACKEND_STORE_CLASS
                }
                SystemUtils.IS_OS_LINUX -> when {
                    Runtimes.checkIfProcessRunning(".+/usr/bin/mongod .+") -> MONGO_STORE_CLASS
                    else -> FILE_BACKEND_STORE_CLASS
                }
                else -> FILE_BACKEND_STORE_CLASS
            }

            /**
             * Sometimes MongoClient is not available or not configured
             * */
            if (MONGO_STORE_CLASS == dataStoreClass && !checkIfMongoClientAvailable(conf)) {
                logger.info("MongoDB is running but mongo client is not available, fallback to FileBackendPageStore")
                dataStoreClass = FILE_BACKEND_STORE_CLASS
            }

            return dataStoreClass
        }

        /**
         * Return the DataStore persistent class used to persist webpages.
         *
         * @param conf AppConstants configuration
         * @return the DataStore persistent class
         */
        @Suppress("UNCHECKED_CAST")
        @Throws(ClassNotFoundException::class)
        fun <K, V : Persistent> detectDataStoreClass(conf: HadoopConfiguration): Class<out DataStore<K, V>> {
            return Class.forName(detectDataStoreClassName(conf)) as Class<out DataStore<K, V>>
        }

        /**
         * Patches the MongoDB servers configuration for Gora.
         * Enable environment variable or system property `GORA_MONGODB_SERVERS`
         */
        @Throws(GoraException::class)
        fun patchGoraMongoServersConfig(conf: Configuration) {
            // Keep this assertion to remind us the real property name
            require("gora.mongodb.servers" == PROP_MONGO_SERVERS)

            var servers = System.getProperty("GORA_MONGODB_SERVERS")
            if (servers == null) {
                servers = System.getenv("GORA_MONGODB_SERVERS")
            }
            if (servers == null) {
                servers = System.getProperty(PROP_MONGO_SERVERS)
            }
            if (servers == null) {
                servers = System.getenv(PROP_MONGO_SERVERS)
            }
            if (servers == null) {
                servers = conf[PROP_MONGO_SERVERS]
            }

            if (servers != null) {
                GoraStorage.goraProperties.setProperty(PROP_MONGO_SERVERS, servers)
                conf[PROP_MONGO_SERVERS] = servers
            }
        }

        private fun checkIfMongoClientAvailable(conf: HadoopConfiguration): Boolean {
            val mongoServers = conf.get(PROP_MONGO_SERVERS)
            if (mongoServers != null) {
                return MongoDBUtils.isMongoReachable(mongoServers)
            }
            return ResourceLoader.exists("gora-mongodb-mapping.xml")
        }
    }
}
