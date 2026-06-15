package ai.platon.pulsar.browser.common

import kotlinx.serialization.json.*
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaType
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight reflective JSON deserializer for external classes (CDT event/type classes
 * from cdt-kotlin-client, ErrorObject, BrowserTab) that cannot be annotated with
 * [kotlinx.serialization.Serializable].
 *
 * Uses kotlinx.serialization's [JsonElement] tree model instead of Jackson's [JsonNode],
 * and Kotlin reflection instead of Jackson's annotation-driven mapping.
 *
 * ## Supported patterns:
 * - **Data classes** with primary constructors: JSON keys are matched to constructor
 *   parameter names (CDT events like [ResponseReceived], [FrameNavigated], etc.)
 * - **Mutable classes** with var properties: JSON keys are matched to settable
 *   properties (e.g., [ErrorObject], [BrowserTab])
 * - **Parameterized types**: [List], [Map], arrays via recursive type resolution
 *
 * ## NOT for use with @Serializable classes — those should use
 * [kotlinx.serialization.json.Json.decodeFromString] directly.
 */
object CDTReflectiveMapper {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // --- Cache structures ---

    /** Cached constructor info for data-class-style deserialization. */
    private data class CtorInfo(
        val kClass: KClass<*>,
        val params: List<KParameter>,
        /** JSON key name → KParameter index for O(1) lookup. */
        val keyToParam: Map<String, KParameter>
    )

    /** Cached property info for mutable-class-style deserialization. */
    private data class PropInfo(
        val factory: () -> Any,
        val setters: Map<String, KMutableProperty1<Any, Any?>>
    )

    private val ctorCache = ConcurrentHashMap<Class<*>, CtorInfo?>()
    private val propCache = ConcurrentHashMap<Class<*>, PropInfo?>()

    // --- Public API ---

    /**
     * Serialize an object to a YAML string using SnakeYAML.
     */
    fun serializeToYaml(obj: Any?): String {
        val jsonElement = serializeToJsonElement(obj)
        val map = jsonElementToObject(jsonElement)
        return yaml.dump(map)
    }

    private val yaml by lazy {
        org.yaml.snakeyaml.Yaml(org.yaml.snakeyaml.DumperOptions().apply {
            defaultFlowStyle = org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
        })
    }

    private fun jsonElementToObject(element: JsonElement): Any? {
        return when (element) {
            is JsonObject -> element.entries.associate { it.key to jsonElementToObject(it.value) }
            is JsonArray -> element.map { jsonElementToObject(it) }
            is JsonPrimitive -> {
                when {
                    element.isString -> element.content
                    element.content == "true" -> true
                    element.content == "false" -> false
                    element.content == "null" -> null
                    else -> element.content.toDoubleOrNull() ?: element.content
                }
            }
            is JsonNull -> null
        }
    }

    /** Parse a raw JSON string into a [JsonElement] tree. */
    fun parseJson(jsonStr: String): JsonElement = json.parseToJsonElement(jsonStr)

    /**
     * Serialize an object to a JSON string using reflection.
     * Supports data classes, lists, maps, primitives, and enums.
     * Skips properties annotated with [kotlinx.serialization.Transient].
     */
    fun serialize(obj: Any?): String = serializeToJsonElement(obj).toString()

    private fun serializeToJsonElement(obj: Any?): JsonElement {
        return when (obj) {
            null -> JsonNull
            is String -> JsonPrimitive(obj)
            is Boolean -> JsonPrimitive(obj)
            is Number -> JsonPrimitive(obj)
            is Enum<*> -> JsonPrimitive(obj.name)
            is List<*> -> buildJsonArray { obj.forEach { add(serializeToJsonElement(it)) } }
            is Array<*> -> buildJsonArray { obj.forEach { add(serializeToJsonElement(it)) } }
            is Map<*, *> -> buildJsonObject {
                obj.forEach { (k, v) -> put(k.toString(), serializeToJsonElement(v)) }
            }
            is JsonElement -> obj
            else -> serializeComplex(obj)
        }
    }

    private fun serializeComplex(obj: Any): JsonObject {
        val kClass = obj::class
        return buildJsonObject {
            // Primary constructor parameters first (in order)
            val ctor = kClass.primaryConstructor
            val includedParams = mutableSetOf<String>()
            if (ctor != null) {
                for (param in ctor.parameters) {
                    val name = param.name ?: continue
                    val prop = kClass.memberProperties.find { it.name == name }
                    if (prop != null && !isTransient(prop)) {
                        val value = safeCall(prop, obj)
                        if (value != null) {
                            put(name, serializeToJsonElement(value))
                            includedParams.add(name)
                        }
                    }
                }
            }
            // Remaining non-constructor properties
            for (prop in kClass.memberProperties) {
                if (prop.name !in includedParams && !isTransient(prop)) {
                    val value = safeCall(prop, obj)
                    if (value != null) {
                        put(prop.name, serializeToJsonElement(value))
                    }
                }
            }
        }
    }

    private fun isTransient(prop: kotlin.reflect.KProperty<*>): Boolean {
        return prop.annotations.any {
            it.annotationClass.qualifiedName == "kotlinx.serialization.Transient"
                    || it.annotationClass.qualifiedName == "com.fasterxml.jackson.annotation.JsonIgnore"
        }
    }

    private fun safeCall(prop: kotlin.reflect.KProperty<*>, obj: Any): Any? {
        return try {
            prop.call(obj)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Deserialize a [JsonElement] into an instance of [targetClass].
     * Tries constructor-based deserialization first (for data classes),
     * then falls back to property-based (for mutable classes).
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> deserialize(element: JsonElement, targetClass: Class<T>): T {
        val jsonObject = requireJsonObject(element, targetClass)

        // Try constructor-based first
        deserializeCtor(jsonObject, targetClass)?.let { return it }

        // Fall back to property-based
        deserializeProps(jsonObject, targetClass)?.let { return it }

        throw IllegalArgumentException(
            "Cannot deserialize ${targetClass.name}: no primary constructor and no settable properties found"
        )
    }

    /**
     * Deserialize into a parameterized type (e.g., [List]<[String]>, [Map]<[String], [Int]>).
     *
     * @param classParameters element type classes, from outermost to innermost.
     *        For `List<String>`: `[String::class.java]`.
     *        For `Map<String, List<Double>>`: `[String::class.java, List::class.java, Double::class.java]`.
     * @param parameterizedClazz the container class (e.g., [List], [Map])
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> deserialize(
        classParameters: Array<Class<*>>,
        parameterizedClazz: Class<T>,
        element: JsonElement
    ): T {
        val jsonArray = element as? JsonArray
            ?: throw IllegalArgumentException(
                "Expected JsonArray for parameterized type ${parameterizedClazz.name}, got ${element::class.simpleName}"
            )

        val typeParamCount = parameterizedClazz.typeParameters.size

        return when {
            // List-like (1 type param)
            typeParamCount <= 1 -> {
                val list = mutableListOf<Any?>()
                for (item in jsonArray) {
                    list.add(convertToTarget(item, classParameters.last()))
                }
                list as T
            }
            // Map-like (2 type params): classParameters[0]=key, rest = value
            typeParamCount == 2 -> {
                val map = mutableMapOf<Any?, Any?>()
                val keyClass = classParameters[0]
                val valueClass: Class<*> = if (classParameters.size == 2) {
                    classParameters[1]
                } else {
                    // Composite value type — wrap remaining as parameterized
                    buildParameterized(classParameters, 1)
                }
                for ((key, value) in jsonArray as JsonObject) {
                    map[convertPrimitive(key, keyClass)] = convertToTarget(value, valueClass)
                }
                map as T
            }
            // 3+ type params — best effort
            else -> {
                val list = mutableListOf<Any?>()
                for (item in jsonArray) {
                    list.add(convertToTarget(item, classParameters.last()))
                }
                list as T
            }
        }
    }

    /**
     * Deserialize a raw JSON string into [targetClass].
     */
    fun <T> deserializeFromString(jsonStr: String, targetClass: Class<T>): T =
        deserialize(parseJson(jsonStr), targetClass)

    // --- Internal: constructor-based deserialization (data classes) ---

    @Suppress("UNCHECKED_CAST")
    private fun <T> deserializeCtor(jsonObject: JsonObject, targetClass: Class<T>): T? {
        val info = getCtorInfo(targetClass) ?: return null

        val args = mutableMapOf<KParameter, Any?>()
        for (param in info.params) {
            val jsonKey = param.name ?: continue
            val jsonValue = jsonObject[jsonKey]
            if (jsonValue == null || jsonValue is JsonNull) {
                if (param.isOptional) continue
                // Skip missing required — caller reports error
                continue
            }
            args[param] = convertValue(jsonValue, param.type)
        }

        return info.kClass.primaryConstructor!!.callBy(args) as T
    }

    private fun getCtorInfo(targetClass: Class<*>): CtorInfo? {
        return ctorCache.computeIfAbsent(targetClass) { clazz ->
            val kClass = clazz.kotlin
            val ctor = kClass.primaryConstructor ?: return@computeIfAbsent null
            val params = ctor.parameters
            val keyToParam = mutableMapOf<String, KParameter>()
            for (p in params) {
                val name = p.name ?: continue
                keyToParam[name] = p
            }
            CtorInfo(kClass, params, keyToParam)
        }
    }

    // --- Internal: property-based deserialization (mutable classes) ---

    @Suppress("UNCHECKED_CAST")
    private fun <T> deserializeProps(jsonObject: JsonObject, targetClass: Class<T>): T? {
        val info = getPropInfo(targetClass) ?: return null
        val instance = info.factory() as Any

        for ((jsonKey, value) in jsonObject) {
            if (value is JsonNull) continue
            val setter = info.setters[jsonKey] ?: continue
            val converted = convertValue(value, setter.returnType)
            setter.set(instance, converted)
        }

        return instance as T
    }

    @Suppress("UNCHECKED_CAST")
    private fun getPropInfo(targetClass: Class<*>): PropInfo? {
        return propCache.computeIfAbsent(targetClass) { clazz ->
            val kClass = clazz.kotlin
            // Need a no-arg constructor
            val ctor = kClass.constructors.firstOrNull { it.parameters.isEmpty() }
                ?: return@computeIfAbsent null

            val factory = { ctor.call() }

            val setters = mutableMapOf<String, KMutableProperty1<Any, Any?>>()
            for (prop in kClass.memberProperties) {
                if (prop is KMutableProperty1<*, *>) {
                    val name = prop.name
                    @Suppress("UNCHECKED_CAST")
                    setters[name] = prop as KMutableProperty1<Any, Any?>
                }
            }

            if (setters.isEmpty()) return@computeIfAbsent null
            PropInfo(factory, setters)
        }
    }

    // --- Internal: value conversion ---

    @Suppress("UNCHECKED_CAST")
    private fun convertValue(element: JsonElement, targetType: KType): Any? {
        return when (element) {
            is JsonNull -> null
            is JsonPrimitive -> convertPrimitiveValue(element, targetType)
            is JsonArray -> {
                val itemClass = resolveElementClass(targetType)
                element.map { convertToTarget(it, itemClass) }
            }
            is JsonObject -> {
                val clazz = (targetType.javaType as? Class<*>) ?: Any::class.java
                deserialize(element, clazz as Class<Any>)
            }
        }
    }

    private fun convertPrimitiveValue(element: JsonPrimitive, targetType: KType): Any? {
        val clazz = targetType.javaType as? Class<*> ?: return element.content
        return convertPrimitive(element.content, clazz)
    }

    @Suppress("UNCHECKED_CAST")
    private fun convertToTarget(element: JsonElement, targetClass: Class<*>): Any? {
        return when (element) {
            is JsonNull -> null
            is JsonPrimitive -> convertPrimitive(element.content, targetClass)
            is JsonArray -> element.map { convertToTarget(it, targetClass.componentType ?: Any::class.java) }
            is JsonObject -> deserialize(element, targetClass as Class<Any>)
        }
    }

    private fun convertPrimitive(content: String, targetClass: Class<*>): Any? {
        return when (targetClass) {
            String::class.java -> content
            java.lang.String::class.java -> content
            Int::class.java, java.lang.Integer::class.java -> content.toIntOrNull() ?: content
            Long::class.java, java.lang.Long::class.java -> content.toLongOrNull() ?: content
            Double::class.java, java.lang.Double::class.java -> content.toDoubleOrNull() ?: content
            Float::class.java, java.lang.Float::class.java -> content.toFloatOrNull() ?: content
            Boolean::class.java, java.lang.Boolean::class.java -> content.toBooleanStrictOrNull() ?: content
            Short::class.java, java.lang.Short::class.java -> content.toShortOrNull() ?: content
            Byte::class.java, java.lang.Byte::class.java -> content.toByteOrNull() ?: content
            Char::class.java, java.lang.Character::class.java -> content.firstOrNull() ?: content
            // Enums: try valueOf
            else -> {
                if (targetClass.isEnum) {
                    try {
                        java.lang.Enum.valueOf(targetClass as Class<out Enum<*>>, content)
                    } catch (_: IllegalArgumentException) {
                        content
                    }
                } else if (targetClass == java.time.Duration::class.java) {
                    // Jackson's JavaTimeModule writes Duration as decimal seconds
                    content.toDoubleOrNull()?.let { java.time.Duration.ofNanos((it * 1_000_000_000).toLong()) } ?: content
                } else {
                    content
                }
            }
        }
    }

    private fun resolveElementClass(targetType: KType): Class<*> {
        val javaType = targetType.javaType
        return if (javaType is java.lang.reflect.ParameterizedType) {
            val args = javaType.actualTypeArguments
            if (args.isNotEmpty()) {
                (args[0] as? Class<*>) ?: Any::class.java
            } else Any::class.java
        } else Any::class.java
    }

    /**
     * Build a parameterized type descriptor from class parameters for nested generic resolution.
     */
    private fun buildParameterized(classParameters: Array<Class<*>>, startIndex: Int): Class<*> {
        if (classParameters.size - startIndex == 1) {
            return classParameters[startIndex]
        }
        // For nested: return the outermost container
        return classParameters[startIndex]
    }

    // --- Helpers ---

    private fun requireJsonObject(element: JsonElement, targetClass: Class<*>): JsonObject {
        return when (element) {
            is JsonObject -> element
            is JsonNull -> throw IllegalArgumentException(
                "Cannot deserialize null into ${targetClass.name}"
            )
            else -> throw IllegalArgumentException(
                "Expected JsonObject for ${targetClass.name}, got ${element::class.simpleName}"
            )
        }
    }
}
