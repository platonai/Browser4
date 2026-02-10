package ai.platon.pulsar.agentic.memory

import ai.platon.pulsar.common.AppPaths
import java.nio.file.Path

/**
 * Storage backend enumeration.
 */
enum class StorageBackend {
    IN_MEMORY,
    FILE_BASED,
    SQLITE,
    VECTOR_DB
}

/**
 * Memory configuration.
 *
 * Configuration can be provided via environment variables or system properties:
 * - MEMORY_ENABLED: Enable/disable memory functionality (default: true)
 * - MEMORY_STORAGE_BACKEND: Storage backend type (default: file_based)
 * - MEMORY_STORAGE_BASE_DIR: Base directory for file storage (default: data/memory)
 * - MEMORY_EPISODIC_MAX_SIZE: Maximum episodic memories (default: 10000)
 * - MEMORY_SEMANTIC_MAX_SIZE: Maximum semantic memories (default: 50000)
 * - MEMORY_CONSOLIDATION_ENABLED: Enable automatic consolidation (default: true)
 * - MEMORY_EMBEDDINGS_ENABLED: Enable vector embeddings (default: false)
 * - MEMORY_EMBEDDINGS_MODEL: Embedding model to use (default: null)
 *
 * @property enabled Whether memory functionality is enabled
 * @property backend Storage backend type
 * @property baseDir Base directory for file-based storage
 * @property maxEpisodicMemories Maximum number of episodic memories
 * @property maxSemanticMemories Maximum number of semantic memories
 * @property consolidationEnabled Whether automatic consolidation is enabled
 * @property embeddingsEnabled Whether vector embeddings are enabled
 * @property embeddingModel Embedding model name
 */
data class MemoryConfig(
    val enabled: Boolean = getEnvOrProperty("MEMORY_ENABLED", "memory.enabled")?.toBoolean() ?: true,
    val backend: StorageBackend = parseBackend(getEnvOrProperty("MEMORY_STORAGE_BACKEND", "memory.storage.backend")),
    val baseDir: Path = Path.of(getEnvOrProperty("MEMORY_STORAGE_BASE_DIR", "memory.storage.baseDir")
        ?: AppPaths.detectAuxiliaryLogDir().resolve("memory").toString()),
    val maxEpisodicMemories: Int = getEnvOrProperty("MEMORY_EPISODIC_MAX_SIZE", "memory.episodic.maxSize")?.toInt() ?: 10_000,
    val maxSemanticMemories: Int = getEnvOrProperty("MEMORY_SEMANTIC_MAX_SIZE", "memory.semantic.maxSize")?.toInt() ?: 50_000,
    val consolidationEnabled: Boolean = getEnvOrProperty("MEMORY_CONSOLIDATION_ENABLED", "memory.consolidation.enabled")?.toBoolean() ?: true,
    val embeddingsEnabled: Boolean = getEnvOrProperty("MEMORY_EMBEDDINGS_ENABLED", "memory.embeddings.enabled")?.toBoolean() ?: false,
    val embeddingModel: String? = getEnvOrProperty("MEMORY_EMBEDDINGS_MODEL", "memory.embeddings.model")
) {
    companion object {
        /**
         * Default configuration instance
         */
        val DEFAULT = MemoryConfig()

        private fun getEnvOrProperty(envVar: String, property: String): String? {
            return System.getenv(envVar) ?: System.getProperty(property)
        }

        private fun parseBackend(value: String?): StorageBackend {
            return when (value?.lowercase()) {
                "in_memory", "inmemory", "memory" -> StorageBackend.IN_MEMORY
                "file_based", "filebased", "file" -> StorageBackend.FILE_BASED
                "sqlite", "sql" -> StorageBackend.SQLITE
                "vector_db", "vectordb", "vector" -> StorageBackend.VECTOR_DB
                else -> StorageBackend.FILE_BASED
            }
        }
    }
}
