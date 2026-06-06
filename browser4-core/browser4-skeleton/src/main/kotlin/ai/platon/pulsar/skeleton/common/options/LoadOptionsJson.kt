package ai.platon.pulsar.skeleton.common.options

import ai.platon.pulsar.common.browser.InteractLevel
import ai.platon.pulsar.common.config.VolatileConfig
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.time.Instant

/**
 * Provides JSON serialization and deserialization support for [LoadOptions].
 *
 * This class enables converting LoadOptions to/from JSON format, which is useful for:
 * - REST API payloads
 * - Configuration files
 * - Debugging and logging
 *
 * Example usage:
 * ```kotlin
 * // Convert to JSON
 * val options = LoadOptions.parse("-expires 1d -ignoreFailure")
 * val json = LoadOptionsJson.toJson(options)
 *
 * // Convert from JSON
 * val options2 = LoadOptionsJson.fromJson(json)
 *
 * // Get a JSON template with all fields and their default values
 * val template = LoadOptionsJson.generateJsonTemplate()
 * ```
 */
object LoadOptionsJson {

    /**
     * Custom ObjectMapper configured for LoadOptions serialization.
     */
    private val objectMapper: ObjectMapper by lazy {
        jacksonObjectMapper()
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.INDENT_OUTPUT, true)
            .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true)
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerModule(JavaTimeModule())
            .registerModule(loadOptionsModule())
    }

    /**
     * Custom module for handling LoadOptions-specific types.
     */
    private fun loadOptionsModule(): SimpleModule {
        return SimpleModule().apply {
            // Duration serializer/deserializer
            addSerializer(Duration::class.java, DurationJsonSerializer())
            addDeserializer(Duration::class.java, DurationJsonDeserializer())

            // Enum serializers/deserializers
            addSerializer(InteractLevel::class.java, EnumNameSerializer(InteractLevel::class.java))
            addDeserializer(InteractLevel::class.java, InteractLevelDeserializer())
            addSerializer(Condition::class.java, EnumNameSerializer(Condition::class.java))
            addDeserializer(Condition::class.java, ConditionDeserializer())
        }
    }

    /**
     * Converts a LoadOptions instance to a JSON string.
     *
     * Only includes fields that differ from their default values to keep the output concise.
     *
     * @param options the LoadOptions to serialize
     * @param includeDefaults if true, includes all fields; if false, only non-default values
     * @return JSON string representation
     */
    @JvmStatic
    @JvmOverloads
    fun toJson(options: LoadOptions, includeDefaults: Boolean = false): String {
        val map = if (includeDefaults) {
            toMap(options)
        } else {
            toModifiedMap(options)
        }
        return objectMapper.writeValueAsString(map)
    }

    /**
     * Converts a LoadOptions instance to a JSON string with pretty printing.
     *
     * @param options the LoadOptions to serialize
     * @param includeDefaults if true, includes all fields; if false, only non-default values
     * @return formatted JSON string representation
     */
    @JvmStatic
    @JvmOverloads
    fun toPrettyJson(options: LoadOptions, includeDefaults: Boolean = false): String {
        return toJson(options, includeDefaults)
    }

    /**
     * Creates a LoadOptions instance from a JSON string.
     *
     * @param json the JSON string to parse
     * @param conf the VolatileConfig to use for the created LoadOptions
     * @return a new LoadOptions instance with values from the JSON
     */
    @JvmStatic
    @JvmOverloads
    fun fromJson(json: String, conf: VolatileConfig = VolatileConfig.UNSAFE): LoadOptions {
        @Suppress("UNCHECKED_CAST")
        val jsonMap = objectMapper.readValue(json, Map::class.java) as Map<String, Any?>
        return fromMap(jsonMap, conf)
    }

    /**
     * Converts a map of options to a LoadOptions instance.
     *
     * @param map the map containing option names and values
     * @param conf the VolatileConfig to use for the created LoadOptions
     * @return a new LoadOptions instance
     */
    @JvmStatic
    @JvmOverloads
    fun fromMap(map: Map<String, Any?>, conf: VolatileConfig = VolatileConfig.UNSAFE): LoadOptions {
        val args = mapToArgs(map)
        return LoadOptions.parse(args, conf)
    }

    /**
     * Converts a LoadOptions instance to a map of all option values.
     *
     * @param options the LoadOptions to convert
     * @return a map of field names to their values
     */
    @JvmStatic
    fun toMap(options: LoadOptions): Map<String, Any?> {
        return LoadOptions.optionDescriptors
            .mapNotNull { desc ->
                val value = desc.get(options)
                if (value != null) desc.fieldName to convertValue(value) else null
            }
            .toMap()
    }

    /**
     * Converts a LoadOptions instance to a map of only modified (non-default) values.
     *
     * @param options the LoadOptions to convert
     * @return a map of field names to their modified values
     */
    @JvmStatic
    fun toModifiedMap(options: LoadOptions): Map<String, Any?> {
        return LoadOptions.optionDescriptors
            .filter { !options.isDefault(it.fieldName) }
            .mapNotNull { desc ->
                val value = desc.get(options)
                if (value != null) desc.fieldName to convertValue(value) else null
            }
            .toMap()
    }

    /**
     * Generates a JSON template showing all LoadOptions fields with their default values.
     *
     * This is useful for documentation and as a starting point for configuration files.
     *
     * @return a formatted JSON string containing all fields and their default values
     */
    @JvmStatic
    fun generateJsonTemplate(): String {
        return toJson(LoadOptions.DEFAULT, includeDefaults = true)
    }

    /**
     * Generates a JSON template with descriptions for each field.
     *
     * @return a formatted JSON string with field descriptions as comments
     */
    @JvmStatic
    fun generateJsonTemplateWithDescriptions(): String {
        val node = objectMapper.createObjectNode()

        LoadOptions.optionDescriptors.forEach { desc ->
            val value = desc.get(LoadOptions.DEFAULT)
            val converted = convertValue(value)
            val valueNode = objectMapper.valueToTree<JsonNode>(converted)
            node.set<JsonNode>(desc.fieldName, valueNode)
        }

        return objectMapper.writeValueAsString(node)
    }

    /**
     * Converts a map to command-line arguments string.
     */
    private fun mapToArgs(map: Map<String, Any?>): String {
        return map.entries
            .filter { it.value != null }
            .joinToString(" ") { (key, value) ->
                val argName = "-$key"
                when (value) {
                    is Boolean -> if (value) argName else "$argName false"
                    is Duration -> "$argName ${formatDurationToString(value)}"
                    is Instant -> "$argName $value"
                    is Enum<*> -> "$argName ${value.name}"
                    else -> "$argName $value"
                }
            }
    }

    /**
     * Converts a value to a JSON-friendly format.
     */
    private fun convertValue(value: Any?): Any? {
        return when (value) {
            null -> null
            is Duration -> formatDurationToString(value)
            is Instant -> value.toString()
            is Enum<*> -> value.name
            else -> value
        }
    }
}

/**
 * Formats a Duration to a human-readable string.
 * This is a private utility function shared by LoadOptionsJson and DurationJsonSerializer.
 */
private fun formatDurationToString(duration: Duration): String {
    return when {
        duration.isNegative -> "0s"
        duration.toDays() > 0 && duration.toHours() % 24 == 0L -> "${duration.toDays()}d"
        duration.toHours() > 0 && duration.toMinutes() % 60 == 0L -> "${duration.toHours()}h"
        duration.toMinutes() > 0 && duration.toSeconds() % 60 == 0L -> "${duration.toMinutes()}m"
        duration.toMillis() % 1000 == 0L -> "${duration.toSeconds()}s"
        else -> "${duration.toMillis()}ms"
    }
}

/**
 * JSON serializer for Duration that outputs human-readable format.
 */
private class DurationJsonSerializer : JsonSerializer<Duration>() {
    override fun serialize(value: Duration?, gen: JsonGenerator, serializers: SerializerProvider) {
        if (value == null) {
            gen.writeNull()
        } else {
            gen.writeString(formatDurationToString(value))
        }
    }
}

/**
 * JSON deserializer for Duration that accepts human-readable format.
 */
private class DurationJsonDeserializer : JsonDeserializer<Duration>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Duration {
        val text = p.text.trim().lowercase()
        return when {
            text.endsWith("ms") -> Duration.ofMillis(text.dropLast(2).toLong())
            text.endsWith("s") -> Duration.ofSeconds(text.dropLast(1).toLong())
            text.endsWith("m") -> Duration.ofMinutes(text.dropLast(1).toLong())
            text.endsWith("h") -> Duration.ofHours(text.dropLast(1).toLong())
            text.endsWith("d") -> Duration.ofDays(text.dropLast(1).toLong())
            else -> Duration.parse(text)
        }
    }
}

/**
 * Generic enum serializer that outputs the name.
 */
private class EnumNameSerializer<E : Enum<E>>(private val enumClass: Class<E>) : JsonSerializer<E>() {
    override fun serialize(value: E?, gen: JsonGenerator, serializers: SerializerProvider) {
        if (value == null) {
            gen.writeNull()
        } else {
            gen.writeString(value.name)
        }
    }
}

/**
 * JSON deserializer for InteractLevel.
 */
private class InteractLevelDeserializer : JsonDeserializer<InteractLevel>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): InteractLevel {
        return InteractLevel.from(p.text)
    }
}

/**
 * JSON deserializer for Condition.
 */
private class ConditionDeserializer : JsonDeserializer<Condition>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Condition {
        return Condition.valueOfOrDefault(p.text)
    }
}
