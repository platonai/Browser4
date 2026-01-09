package ai.platon.pulsar.persist.gora;

import ai.platon.pulsar.common.config.ImmutableConfig;
import ai.platon.pulsar.persist.HadoopUtils;
import ai.platon.pulsar.persist.gora.generated.GWebPage;
import org.apache.gora.persistency.Persistent;
import org.apache.gora.store.DataStore;
import org.apache.gora.store.DataStoreFactory;
import org.apache.gora.util.GoraException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static ai.platon.pulsar.common.LogsKt.warnForClose;
import static ai.platon.pulsar.common.config.AppConstants.MONGO_STORE_CLASS;
import static ai.platon.pulsar.common.config.AppConstants.WEBPAGE_SCHEMA;
import static ai.platon.pulsar.common.config.CapabilityTypes.*;

public class GoraStorage {
    public static final Logger logger = LoggerFactory.getLogger(GoraStorage.class);

    /**
     * Loads properties from the `gora.properties` file.
     * See org.apache.gora.mongodb.store.MongoStoreParameters#load for property loading details:
     * 1. Loads from the `gora.properties` file.
     * 2. If `gora.mongodb.override_hadoop_configuration` is false, uses properties from the Hadoop configuration.
     * 3. In Browser4, the `gora.properties` file is located at `pulsar-persist/src/main/resources/gora.properties`
     */
    public static Properties goraProperties = DataStoreFactory.createProps();
    /**
     * The dataStores map is used to cache DataStore instances
     *
     */
    private static Map<String, Object> dataStores = new HashMap<>();

    public synchronized static <K, V extends Persistent> DataStore<K, V>
    createDataStore(ImmutableConfig conf, Class<K> keyClass, Class<V> persistentClass)
            throws GoraException, ClassNotFoundException {
        return createDataStore(HadoopUtils.INSTANCE.toHadoopConfiguration(conf), keyClass, persistentClass);
    }

    @SuppressWarnings("unchecked")
    public synchronized static <K, V extends Persistent> DataStore<K, V>
    createDataStore(org.apache.hadoop.conf.Configuration conf, Class<K> keyClass, Class<V> persistentClass)
            throws GoraException, ClassNotFoundException {
        String className = conf.get(STORAGE_DATA_STORE_CLASS, MONGO_STORE_CLASS);
        Class<? extends DataStore<K, V>> dataStoreClass = (Class<? extends DataStore<K, V>>) Class.forName(className);
        return createDataStore(conf, keyClass, persistentClass, dataStoreClass);
    }

    @SuppressWarnings("unchecked")
    public synchronized static <K, V extends Persistent> DataStore<K, V>
    createDataStore(org.apache.hadoop.conf.Configuration conf,
                    Class<K> keyClass, Class<V> persistentClass, Class<? extends DataStore<K, V>> dataStoreClass
    ) throws GoraException {
        String crawlId = conf.get(STORAGE_CRAWL_ID, "");
        String schemaPrefix = "";
        if (!crawlId.isEmpty()) {
            schemaPrefix = crawlId + "_";
        }

        String schema;
        if (GWebPage.class.equals(persistentClass)) {
            schema = conf.get(STORAGE_SCHEMA_WEBPAGE, WEBPAGE_SCHEMA);
        } else {
            throw new UnsupportedOperationException("Unable to create storage for class " + persistentClass);
        }

        Object o = dataStores.get(schema);
        if (o == null) {
            String realSchema = schemaPrefix + schema;
            conf.set(STORAGE_PREFERRED_SCHEMA_NAME, realSchema);
            DataStore<K, V> dataStore = DataStoreFactory.createDataStore(dataStoreClass,
                    keyClass, persistentClass, conf, goraProperties, schema);

            dataStores.put(realSchema, dataStore);

            String className = dataStore.getClass().getName();
            if (className.contains("FileBackendPageStore")) {
                logger.info("Backend data store: {}, real schema: {}", className, dataStore.getSchemaName());
                logger.info("FileBackendPageStore is only for development and testing, " +
                        "it is not suitable for production environment");
            } else {
                logger.info("Backend data store: {}, real schema: {}, storage id: <{}>, " +
                                "set config `storage.crawl.id` to define the real schema",
                        className, dataStore.getSchemaName(), schemaPrefix);
            }

            return dataStore;
        }

        return (DataStore<K, V>) o;
    }

    public synchronized static void close() {
        dataStores.forEach((schema, store) -> {
            if (store instanceof DataStore) {
                logger.info("Closing data store <{}>", schema);
                try {
                    ((DataStore<?, ?>) store).close();
                } catch (Exception e) {
                    warnForClose(store, e);
                }
            }
        });
        dataStores.clear();
    }
}
