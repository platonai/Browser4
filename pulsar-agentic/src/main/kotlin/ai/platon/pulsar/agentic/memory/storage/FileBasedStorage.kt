package ai.platon.pulsar.agentic.memory.storage

import ai.platon.pulsar.agentic.memory.*
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.*

/**
 * File-based storage implementation.
 *
 * Stores memories as JSON files in a directory structure:
 * - baseDir/episodic/{sessionId}/{episodeId}.json
 * - baseDir/semantic/{category}/{memoryId}.json
 *
 * Provides persistence across sessions with simple file-based organization.
 *
 * @property baseDir Base directory for memory storage
 * @property mapper JSON mapper for serialization
 */
class FileBasedStorage(
    private val baseDir: Path,
    private val mapper: ObjectMapper = pulsarObjectMapper()
) : MemoryStorage {

    init {
        // Ensure directories exist
        baseDir.createDirectories()
        baseDir.resolve("episodic").createDirectories()
        baseDir.resolve("semantic").createDirectories()
    }

    override suspend fun save(memory: Memory): String = withContext(Dispatchers.IO) {
        val path = getMemoryPath(memory)
        path.parent?.createDirectories()

        val json = when (memory) {
            is Memory.Episodic -> mapper.writeValueAsString(memory.episode)
            is Memory.Semantic -> mapper.writeValueAsString(memory.knowledge)
        }

        Files.writeString(
            path,
            json,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        )

        memory.id
    }

    override suspend fun load(id: String): Memory? = withContext(Dispatchers.IO) {
        // Search in both episodic and semantic directories
        val episodicFile = findFileById(baseDir.resolve("episodic"), id)
        if (episodicFile != null) {
            val episode: EpisodicMemory = mapper.readValue(episodicFile.readText())
            return@withContext Memory.Episodic(
                id = episode.id,
                timestamp = episode.endTime,
                episode = episode
            )
        }

        val semanticFile = findFileById(baseDir.resolve("semantic"), id)
        if (semanticFile != null) {
            val knowledge: SemanticMemory = mapper.readValue(semanticFile.readText())
            return@withContext Memory.Semantic(
                id = knowledge.id,
                timestamp = knowledge.created,
                knowledge = knowledge
            )
        }

        null
    }

    override suspend fun query(query: MemoryQuery): List<Memory> = withContext(Dispatchers.IO) {
        val allMemories = loadAllMemories()

        when (query) {
            is MemoryQuery.All -> allMemories

            is MemoryQuery.Recency -> {
                val filtered = if (query.category != null) {
                    allMemories.filter { memory ->
                        memory is Memory.Semantic && memory.knowledge.category == query.category
                    }
                } else {
                    allMemories
                }

                val tagFiltered = if (query.tags.isNotEmpty()) {
                    filtered.filter { memory ->
                        val tags = when (memory) {
                            is Memory.Episodic -> memory.episode.tags
                            is Memory.Semantic -> memory.knowledge.tags
                        }
                        query.tags.any { it in tags }
                    }
                } else {
                    filtered
                }

                tagFiltered
                    .sortedByDescending { it.timestamp }
                    .take(query.limit)
            }

            is MemoryQuery.Tag -> {
                val filtered = allMemories.filter { memory ->
                    val tags = when (memory) {
                        is Memory.Episodic -> memory.episode.tags
                        is Memory.Semantic -> memory.knowledge.tags
                    }
                    if (query.matchAll) {
                        query.tags.all { it in tags }
                    } else {
                        query.tags.any { it in tags }
                    }
                }
                filtered
                    .sortedByDescending { it.timestamp }
                    .take(query.limit)
            }

            is MemoryQuery.Similarity -> {
                // Basic text similarity
                val semanticMemories = allMemories.filterIsInstance<Memory.Semantic>()
                val filtered = if (query.category != null) {
                    semanticMemories.filter { it.knowledge.category == query.category }
                } else {
                    semanticMemories
                }

                // Simple keyword matching
                val queryWords = query.queryText.lowercase().split("\\s+".toRegex()).toSet()
                val scored = filtered.map { memory ->
                    val contentWords = memory.knowledge.content.lowercase().split("\\s+".toRegex()).toSet()
                    val similarity = queryWords.intersect(contentWords).size.toDouble() / 
                                   (queryWords.size + contentWords.size - queryWords.intersect(contentWords).size)
                    memory to similarity
                }

                scored
                    .filter { it.second >= query.minSimilarity }
                    .sortedByDescending { it.second }
                    .take(query.limit)
                    .map { it.first }
            }

            is MemoryQuery.Hybrid -> {
                // Simple hybrid: combine recency and similarity
                val recent = query(MemoryQuery.Recency(limit = query.limit * 2))
                val similar = query(MemoryQuery.Similarity(
                    queryText = query.context,
                    limit = query.limit * 2
                ))

                (recent + similar)
                    .distinct()
                    .take(query.limit)
            }
        }
    }

    override suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val episodicFile = findFileById(baseDir.resolve("episodic"), id)
        if (episodicFile != null) {
            episodicFile.deleteIfExists()
            return@withContext true
        }

        val semanticFile = findFileById(baseDir.resolve("semantic"), id)
        if (semanticFile != null) {
            semanticFile.deleteIfExists()
            return@withContext true
        }

        false
    }

    override suspend fun update(id: String, updates: Map<String, Any>): Boolean = withContext(Dispatchers.IO) {
        val memory = load(id) ?: return@withContext false

        // For semantic memory, update usage tracking
        if (memory is Memory.Semantic && updates.containsKey("timesUsed")) {
            val updated = memory.knowledge.copy(
                timesUsed = updates["timesUsed"] as? Int ?: memory.knowledge.timesUsed,
                lastUsed = updates["lastUsed"] as? java.time.Instant ?: memory.knowledge.lastUsed
            )
            save(Memory.Semantic(
                id = memory.id,
                timestamp = memory.timestamp,
                knowledge = updated
            ))
            return@withContext true
        }

        false
    }

    override suspend fun count(): Long = withContext(Dispatchers.IO) {
        val episodicCount = countFilesRecursively(baseDir.resolve("episodic"))
        val semanticCount = countFilesRecursively(baseDir.resolve("semantic"))
        episodicCount + semanticCount
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        baseDir.resolve("episodic").deleteRecursively()
        baseDir.resolve("semantic").deleteRecursively()
        baseDir.resolve("episodic").createDirectories()
        baseDir.resolve("semantic").createDirectories()
    }

    private fun getMemoryPath(memory: Memory): Path {
        return when (memory) {
            is Memory.Episodic -> {
                baseDir.resolve("episodic")
                    .resolve(memory.episode.sessionId)
                    .resolve("${memory.episode.id}.json")
            }
            is Memory.Semantic -> {
                baseDir.resolve("semantic")
                    .resolve(memory.knowledge.category.name)
                    .resolve("${memory.knowledge.id}.json")
            }
        }
    }

    private fun findFileById(dir: Path, id: String): Path? {
        if (!dir.exists()) return null
        
        return Files.walk(dir)
            .filter { it.isRegularFile() && it.name.startsWith(id) }
            .findFirst()
            .orElse(null)
    }

    private fun loadAllMemories(): List<Memory> {
        val memories = mutableListOf<Memory>()

        // Load episodic memories
        val episodicDir = baseDir.resolve("episodic")
        if (episodicDir.exists()) {
            Files.walk(episodicDir)
                .filter { it.isRegularFile() && it.extension == "json" }
                .forEach { file ->
                    try {
                        val episode: EpisodicMemory = mapper.readValue(file.readText())
                        memories.add(Memory.Episodic(
                            id = episode.id,
                            timestamp = episode.endTime,
                            episode = episode
                        ))
                    } catch (e: Exception) {
                        // Skip malformed files
                    }
                }
        }

        // Load semantic memories
        val semanticDir = baseDir.resolve("semantic")
        if (semanticDir.exists()) {
            Files.walk(semanticDir)
                .filter { it.isRegularFile() && it.extension == "json" }
                .forEach { file ->
                    try {
                        val knowledge: SemanticMemory = mapper.readValue(file.readText())
                        memories.add(Memory.Semantic(
                            id = knowledge.id,
                            timestamp = knowledge.created,
                            knowledge = knowledge
                        ))
                    } catch (e: Exception) {
                        // Skip malformed files
                    }
                }
        }

        return memories
    }

    private fun countFilesRecursively(dir: Path): Long {
        if (!dir.exists()) return 0
        return Files.walk(dir)
            .filter { it.isRegularFile() && it.extension == "json" }
            .count()
    }
}
