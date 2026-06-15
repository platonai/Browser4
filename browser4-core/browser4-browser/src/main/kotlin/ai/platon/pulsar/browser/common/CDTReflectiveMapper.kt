package ai.platon.pulsar.browser.common

import kotlinx.serialization.json.*
import java.io.DataInputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight reflective JSON serializer/deserializer for external classes
 * (CDT event/type classes from cdt-kotlin-client, ErrorObject, BrowserTab)
 * that cannot be annotated with [kotlinx.serialization.Serializable].
 *
 * Uses kotlinx.serialization's [JsonElement] tree model and **Java reflection**
 * ([java.lang.reflect.Constructor], [java.lang.reflect.Field]) instead of
 * `kotlin-reflect`, saving ~8 MB in the shaded JAR.
 *
 * **Parameter name resolution**: Since Kotlin does not emit the JVM
 * `MethodParameters` attribute, parameter names are read from the constructor's
 * `LocalVariableTable` attribute by parsing the class file bytes directly.
 *
 * ## Supported patterns:
 * - **Data classes** with primary constructors: JSON keys matched to
 *   constructor parameter names extracted from bytecode.
 * - **Mutable classes** with var properties: JSON keys matched to settable
 *   fields (e.g., [ErrorObject], [BrowserTab]).
 * - **Parameterized types**: [List], [Map], arrays via recursive type resolution.
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

    /**
     * Cached constructor metadata for data-class-style deserialization.
     * Parameter names are extracted from the class file's LocalVariableTable.
     */
    private data class CtorInfo(
        val constructor: Constructor<*>,
        /** Parameter names in constructor declaration order. */
        val paramNames: List<String>,
        /** JSON key name → positional index for O(1) lookup. */
        val keyToIndex: Map<String, Int>,
        /** Whether the class has Kotlin default parameter values. */
        val hasDefaults: Boolean
    )

    /** Cached field info for mutable-class-style deserialization. */
    private data class PropInfo(
        val factory: () -> Any,
        val setters: Map<String, Field>
    )

    private val ctorCache = ConcurrentHashMap<Class<*>, CtorInfo?>()
    private val propCache = ConcurrentHashMap<Class<*>, PropInfo?>()

    // --- Public API ---

    /** Serialize an object to a YAML string using SnakeYAML. */
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
     * Skips properties annotated with [kotlinx.serialization.Transient]
     * or `com.fasterxml.jackson.annotation.JsonIgnore`.
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

    // Thread-local set to detect circular references during serialization.
    // Uses IdentityHashMap-backed set for correct identity comparison,
    // since different objects may be structurally equal but a true cycle
    // means the exact same object instance is encountered again.
    private val serializingObjects = ThreadLocal.withInitial {
        java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    }

    private fun serializeComplex(obj: Any): JsonObject {
        val clazz = obj.javaClass
        val visited = serializingObjects.get()

        // Cycle detection: if we encounter the same object again, emit a reference
        // marker instead of recursing infinitely.
        if (!visited.add(obj)) {
            return buildJsonObject {
                put("\$ref", JsonPrimitive("cycle-${obj.javaClass.simpleName}@${System.identityHashCode(obj)}"))
            }
        }

        try {
            return buildJsonObject {
                for (field in clazz.declaredFields) {
                    if (Modifier.isStatic(field.modifiers)) continue
                    if (Modifier.isTransient(field.modifiers)) continue
                    if (isTransient(field)) continue
                    field.isAccessible = true
                    val value = try {
                        field.get(obj)
                    } catch (_: Exception) {
                        null
                    }
                    if (value != null) {
                        put(field.name, serializeToJsonElement(value))
                    }
                }
            }
        } finally {
            visited.remove(obj)
        }
    }

    private fun isTransient(field: Field): Boolean {
        return field.annotations.any {
            it.annotationClass.qualifiedName == "kotlinx.serialization.Transient"
                    || it.annotationClass.qualifiedName == "com.fasterxml.jackson.annotation.JsonIgnore"
        }
    }

    /**
     * Deserialize a [JsonElement] into an instance of [targetClass].
     * Tries constructor-based deserialization first (for data classes),
     * then falls back to field-based (for mutable classes).
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> deserialize(element: JsonElement, targetClass: Class<T>): T {
        val jsonObject = requireJsonObject(element, targetClass)

        // Try constructor-based first
        deserializeCtor(jsonObject, targetClass)?.let { return it }

        // Fall back to field-based
        deserializeProps(jsonObject, targetClass)?.let { return it }

        throw IllegalArgumentException(
            "Cannot deserialize ${targetClass.name}: no suitable constructor and no settable fields found"
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
                    classParameters[1]
                }
                val jsonObject = element as? JsonObject
                    ?: throw IllegalArgumentException(
                        "Expected JsonObject for Map type, got ${element::class.simpleName}"
                    )
                for ((key, value) in jsonObject) {
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

        // Extract generic type arguments from constructor for correct
        // List<Foo> / Map<K,V> element deserialization.
        val genericParamTypes = info.constructor.genericParameterTypes
        val paramTypeArgs = Array<Class<*>?>(info.paramNames.size) { null }
        for (i in genericParamTypes.indices) {
            if (genericParamTypes[i] is java.lang.reflect.ParameterizedType) {
                val pt = genericParamTypes[i] as java.lang.reflect.ParameterizedType
                val typeArgs = pt.actualTypeArguments
                if (typeArgs.isNotEmpty() && typeArgs[0] is Class<*>) {
                    paramTypeArgs[i] = typeArgs[0] as Class<*>
                }
            }
        }

        // Try name-based matching first, then positional matching.
        // Use whichever fills more constructor arguments.
        val attempts = listOf(
            buildArgsByName(jsonObject, info, paramTypeArgs),
            buildArgsByPosition(jsonObject, info, paramTypeArgs)
        )

        var bestArgs: Array<Any?>? = null
        var bestProvided: BooleanArray? = null
        var bestCount = -1

        for ((args, provided) in attempts) {
            val count = provided.count { it }
            if (count > bestCount) {
                bestCount = count
                bestArgs = args
                bestProvided = provided
            }
        }

        if (bestArgs == null || bestCount == 0) return null

        return try {
            if (info.hasDefaults) {
                var mask = 0
                for (i in bestArgs.indices) {
                    if (!bestProvided!![i]) {
                        mask = mask or (1 shl i)
                    }
                }
                if (mask == 0) {
                    info.constructor.newInstance(*bestArgs) as T
                } else {
                    callWithDefaults(info, bestArgs, mask) as T
                }
            } else {
                info.constructor.newInstance(*bestArgs) as T
            }
        } catch (e: Exception) {
            null
        }
    }

    private data class ArgsResult(val args: Array<Any?>, val provided: BooleanArray)

    private fun buildArgsByName(
        jsonObject: JsonObject,
        info: CtorInfo,
        paramTypeArgs: Array<Class<*>?>
    ): ArgsResult {
        val args = arrayOfNulls<Any?>(info.paramNames.size)
        val provided = BooleanArray(info.paramNames.size)

        for ((jsonKey, jsonValue) in jsonObject) {
            if (jsonValue is JsonNull) continue
            val idx = info.keyToIndex[jsonKey] ?: continue
            try {
                args[idx] = convertToTarget(jsonValue, info.constructor.parameterTypes[idx], paramTypeArgs[idx])
                provided[idx] = true
            } catch (_: Exception) { }
        }

        return ArgsResult(args, provided)
    }

    private fun buildArgsByPosition(
        jsonObject: JsonObject,
        info: CtorInfo,
        paramTypeArgs: Array<Class<*>?>
    ): ArgsResult {
        val args = arrayOfNulls<Any?>(info.paramNames.size)
        val provided = BooleanArray(info.paramNames.size)

        var pos = 0
        for ((_, jsonValue) in jsonObject) {
            if (pos >= args.size) break
            if (jsonValue !is JsonNull) {
                try {
                    args[pos] = convertToTarget(jsonValue, info.constructor.parameterTypes[pos], paramTypeArgs[pos])
                    provided[pos] = true
                } catch (_: Exception) { }
            }
            pos++
        }

        return ArgsResult(args, provided)
    }

    /**
     * Call a Kotlin constructor with default parameters via its synthetic `$default` method.
     */
    @Suppress("UNCHECKED_CAST")
    private fun callWithDefaults(info: CtorInfo, args: Array<Any?>, mask: Int): Any {
        val clazz = info.constructor.declaringClass
        // Kotlin $default method signature: (params..., int mask, Object marker)
        val paramTypes = info.constructor.parameterTypes.toList() +
                Int::class.javaPrimitiveType!! +
                Any::class.java
        val defaultMethod = clazz.getDeclaredMethod(
            "${'$'}default",
            *paramTypes.toTypedArray()
        )
        defaultMethod.isAccessible = true
        val allArgs = args.toList() + mask + null
        return defaultMethod.invoke(null, *allArgs.toTypedArray())
    }

    private fun getCtorInfo(targetClass: Class<*>): CtorInfo? {
        return ctorCache.computeIfAbsent(targetClass) { clazz ->
            // Pick the real primary constructor (not the synthetic $default one).
            // The synthetic one has DefaultConstructorMarker as its last parameter.
            val ctor = clazz.declaredConstructors
                .filter { it.parameterCount > 0 }
                .filter { c ->
                    val lastParam = c.parameterTypes.lastOrNull()
                    lastParam?.name != "kotlin.jvm.internal.DefaultConstructorMarker"
                }
                .maxByOrNull { it.parameterCount }
                ?: return@computeIfAbsent null

            ctor.isAccessible = true

            // Try extracting parameter names from the class file's LocalVariableTable.
            // Fall back to java.lang.reflect.Parameter.getName() if LVT parsing fails
            // (e.g. for classes in external JARs with different bytecode layout).
            val paramNames: List<String> = extractParameterNames(clazz, ctor.parameterCount)
                .ifEmpty {
                    ctor.parameters.map { p -> p.name ?: "arg" }
                }

            val keyToIndex = mutableMapOf<String, Int>()
            for ((i, name) in paramNames.withIndex()) {
                keyToIndex[name] = i
            }

            val hasDefaults = hasDefaultMethod(clazz, ctor)

            CtorInfo(ctor, paramNames, keyToIndex, hasDefaults)
        }
    }

    /**
     * Check if the class has a Kotlin synthetic `$default` method for this constructor.
     */
    private fun hasDefaultMethod(clazz: Class<*>, ctor: Constructor<*>): Boolean {
        return clazz.declaredMethods.any { m ->
            m.name == "${'$'}default" &&
                    m.parameterCount == ctor.parameterCount + 2
        }
    }

    // --- Internal: field-based deserialization (mutable classes) ---

    @Suppress("UNCHECKED_CAST")
    private fun <T> deserializeProps(jsonObject: JsonObject, targetClass: Class<T>): T? {
        val info = getPropInfo(targetClass) ?: return null
        val instance = info.factory() as Any

        for ((jsonKey, value) in jsonObject) {
            if (value is JsonNull) continue
            val field = info.setters[jsonKey] ?: continue
            val converted = convertToTarget(value, field.type)
            try {
                field.set(instance, converted)
            } catch (_: Exception) {
                // Skip fields that can't be set
            }
        }

        return instance as T
    }

    @Suppress("UNCHECKED_CAST")
    private fun getPropInfo(targetClass: Class<*>): PropInfo? {
        return propCache.computeIfAbsent(targetClass) { clazz ->
            val ctor = try {
                clazz.getDeclaredConstructor()
            } catch (_: NoSuchMethodException) {
                return@computeIfAbsent null
            }
            ctor.isAccessible = true
            val factory = { ctor.newInstance() }

            val setters = mutableMapOf<String, Field>()
            for (field in clazz.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                if (Modifier.isTransient(field.modifiers)) continue
                field.isAccessible = true
                setters[field.name] = field
            }

            if (setters.isEmpty()) return@computeIfAbsent null
            PropInfo(factory, setters)
        }
    }

    // --- Internal: value conversion ---

    private fun convertToTarget(element: JsonElement, targetClass: Class<*>): Any? {
        return convertToTarget(element, targetClass, null)
    }

    /**
     * Convert with optional generic type argument (e.g., for List<Foo> we need
     * to know `Foo` so inner elements can be deserialized correctly).
     */
    private fun convertToTarget(
        element: JsonElement,
        targetClass: Class<*>,
        typeArg: Class<*>?
    ): Any? {
        return when (element) {
            is JsonNull -> null
            is JsonPrimitive -> convertPrimitive(element.content, targetClass)
            is JsonArray -> {
                val componentType = typeArg
                    ?: targetClass.componentType
                    ?: Any::class.java
                element.map { convertToTarget(it, componentType) }
            }
            is JsonObject -> {
                if (targetClass.isInterface
                    || java.lang.reflect.Modifier.isAbstract(targetClass.modifiers)
                    || targetClass == Any::class.java
                    || targetClass == java.lang.Object::class.java) {
                    // Can't instantiate interfaces/abstract/Object — return raw element
                    element
                } else {
                    deserialize(element, targetClass as Class<Any>)
                }
            }
        }
    }

    internal fun convertPrimitive(content: String, targetClass: Class<*>): Any? {
        return when (targetClass) {
            String::class.java -> content
            Int::class.java -> content.toIntOrNull() ?: content
            Long::class.java -> content.toLongOrNull() ?: content
            Double::class.java -> content.toDoubleOrNull() ?: content
            Float::class.java -> content.toFloatOrNull() ?: content
            Boolean::class.java -> content.toBooleanStrictOrNull() ?: content
            Short::class.java -> content.toShortOrNull() ?: content
            Byte::class.java -> content.toByteOrNull() ?: content
            Char::class.java -> content.firstOrNull() ?: content
            else -> {
                if (targetClass.isEnum) {
                    try {
                        java.lang.Enum.valueOf(targetClass as Class<out Enum<*>>, content)
                    } catch (_: IllegalArgumentException) {
                        content
                    }
                } else if (targetClass == java.time.Duration::class.java) {
                    // Jackson's JavaTimeModule writes Duration as decimal seconds
                    content.toDoubleOrNull()?.let {
                        java.time.Duration.ofNanos((it * 1_000_000_000).toLong())
                    } ?: content
                } else {
                    content
                }
            }
        }
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

    // ========================================================================
    //  Minimal JVM Class File Parser — extracts constructor parameter names
    //  from the LocalVariableTable attribute, avoiding kotlin-reflect (~8 MB).
    // ========================================================================

    /**
     * Extract constructor parameter names from the class file's
     * `LocalVariableTable` attribute for the constructor with the most
     * parameters (which is the primary constructor for Kotlin data classes).
     *
     * Returns parameter names in declaration order, or an empty list if
     * extraction fails for any reason.
     */
    private fun extractParameterNames(clazz: Class<*>, expectedParamCount: Int): List<String> {
        return try {
            val resourcePath = "/${clazz.name.replace('.', '/')}.class"
            val bytes = clazz.getResourceAsStream(resourcePath)?.readBytes()
                ?: return emptyList()
            parseConstructorParameterNames(bytes, expectedParamCount)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Minimal JVM class file parser that reads constructor parameter names
     * from the `LocalVariableTable` attribute.
     *
     * JVM class file format (simplified):
     * ```
     * ClassFile {
     *     u4 magic;                // 0xCAFEBABE
     *     u2 minor_version;
     *     u2 major_version;
     *     u2 constant_pool_count;
     *     cp_info constant_pool[...];
     *     u2 access_flags;
     *     u2 this_class;
     *     u2 super_class;
     *     u2 interfaces_count;
     *     u2 interfaces[...];
     *     u2 fields_count;
     *     field_info fields[...];
     *     u2 methods_count;
     *     method_info methods[...];
     *     u2 attributes_count;
     *     attribute_info attributes[...];
     * }
     * ```
     *
     * We need to:
     * 1. Parse the constant pool to resolve UTF-8 strings
     * 2. Find the constructor method (`<init>`) with the most parameters
     * 3. Navigate: method → Code attribute → LocalVariableTable attribute
     * 4. Extract parameter names from local variable slots (skip slot 0 = `this`)
     */
    private fun parseConstructorParameterNames(bytes: ByteArray, expectedParamCount: Int): List<String> {
        val reader = ClassFileReader(bytes)

        // 1. Parse constant pool
        val cp = reader.parseConstantPool()
        val utf8 = object {
            operator fun get(index: Int): String = cp[index] as? String ?: "<unknown>"
        }

        // 2. Skip: access_flags, this_class, super_class, interfaces
        reader.skip(6)
        val interfaceCount = reader.readU2()
        reader.skip(interfaceCount * 2)

        // 3. Skip fields
        val fieldCount = reader.readU2()
        reader.skipFields(fieldCount)

        // 4. Find the real constructor (not the synthetic $default one).
        // The synthetic one has DefaultConstructorMarker in its descriptor.
        val methodCount = reader.readU2()
        var bestConstructorOff: Int = -1
        var bestConstructorParams = -1

        for (i in 0 until methodCount) {
            val startOff = reader.offset
            reader.skip(2) // access_flags
            val nameIdx = reader.readU2()
            val descIdx = reader.readU2()
            val attrCount = reader.readU2()

            val name = utf8[nameIdx]
            if (name == "<init>") {
                val desc = utf8[descIdx]  // e.g. "(Ljava/lang/String;I)V"
                // Skip synthetic $default constructors that include
                // DefaultConstructorMarker as the last parameter.
                if (desc.contains("DefaultConstructorMarker")) {
                    reader.skipAttributes(attrCount)
                    continue
                }
                val paramCount = countParams(desc)
                if (paramCount > bestConstructorParams) {
                    bestConstructorParams = paramCount
                    bestConstructorOff = startOff
                }
            }
            reader.skipAttributes(attrCount)
        }

        if (bestConstructorOff < 0 || bestConstructorParams != expectedParamCount) {
            return emptyList()
        }

        // 5. Re-parse the best constructor to extract LocalVariableTable
        reader.offset = bestConstructorOff
        reader.skip(2) // access_flags
        reader.skip(2) // name_index
        reader.skip(2) // descriptor_index
        val attrCount = reader.readU2()

        return reader.extractLocalVariableNames(attrCount, bestConstructorParams, { utf8[it] })
    }

    /** Count parameters from a JVM method descriptor like "(Ljava/lang/String;I)V". */
    private fun countParams(descriptor: String): Int {
        var count = 0
        var i = 1 // skip '('
        while (i < descriptor.length && descriptor[i] != ')') {
            when (descriptor[i]) {
                'L' -> { i = descriptor.indexOf(';', i) + 1; count++ }
                '[' -> { i++; continue }
                'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> { i++; count++ }
                else -> i++
            }
        }
        return count
    }

    /**
     * Lightweight sequential reader for class file bytes.
     * Tracks an [offset] and provides methods to read u2, u4, and skip
     * over attributes.
     */
    private class ClassFileReader(val bytes: ByteArray) {
        var offset: Int = 0

        fun readU2(): Int {
            val v = ((bytes[offset].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 1].toInt() and 0xFF)
            offset += 2
            return v
        }

        fun readU4(): Int {
            val v = ((bytes[offset].toInt() and 0xFF) shl 24) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 3].toInt() and 0xFF)
            offset += 4
            return v
        }

        fun skip(n: Int) { offset += n }

        // ==================== Constant Pool ====================

        fun parseConstantPool(): Array<Any?> {
            val count = readU2()
            val cp = arrayOfNulls<Any>(count)

            var i = 1
            while (i < count) {
                val tag = bytes[offset++].toInt() and 0xFF
                when (tag) {
                    1 -> { // CONSTANT_Utf8
                        val len = readU2()
                        val str = String(bytes, offset, len, Charsets.UTF_8)
                        offset += len
                        cp[i] = str
                    }
                    3 -> { // CONSTANT_Integer
                        cp[i] = readU4()
                        i++ // int consumes one slot (actually no, but we skip it)
                    }
                    4 -> { cp[i] = readU4(); i++ } // Float
                    5 -> { // Long
                        cp[i] = readU4().toLong() shl 32 or readU4().toLong()
                        i++ // long consumes two slots
                    }
                    6 -> { cp[i] = readU4(); i++ } // Double
                    7 -> { offset += 2 } // Class
                    8 -> { offset += 2 } // String
                    9 -> { offset += 4 } // Fieldref
                    10 -> { offset += 4 } // Methodref
                    11 -> { offset += 4 } // InterfaceMethodref
                    12 -> { offset += 4 } // NameAndType
                    15 -> { offset += 3 } // MethodHandle
                    16 -> { offset += 2 } // MethodType
                    17 -> { offset += 4 } // Dynamic
                    18 -> { offset += 4 } // InvokeDynamic
                    19 -> { offset += 2 } // Module
                    20 -> { offset += 2 } // Package
                    else -> { /* skip */ }
                }
                i++
            }
            return cp
        }

        // ==================== Fields & Attributes ====================

        fun skipFields(count: Int) {
            for (i in 0 until count) {
                skip(6) // access_flags(2) + name_idx(2) + descriptor_idx(2)
                val attrCount = readU2()
                skipAttributes(attrCount)
            }
        }

        fun skipAttributes(count: Int) {
            for (i in 0 until count) {
                skip(2) // attribute_name_index
                val len = readU4()
                skip(len)
            }
        }

        // ==================== LocalVariableTable Extraction ====================

        /**
         * Navigate the attributes of a constructor to find the Code attribute,
         * then within it the LocalVariableTable attribute, and extract
         * parameter names (slot 1..paramCount; slot 0 is `this`).
         */
        fun extractLocalVariableNames(
            attrCount: Int,
            paramCount: Int,
            utf8: (Int) -> String
        ): List<String> {
            // First pass: find Code attribute
            var codeAttrData: ByteArray? = null

            for (i in 0 until attrCount) {
                val attrNameIdx = readU2()
                val attrLen = readU4()
                val attrName = utf8(attrNameIdx)

                if (attrName == "Code") {
                    codeAttrData = bytes.copyOfRange(offset, offset + attrLen)
                }
                offset += attrLen
            }

            if (codeAttrData == null) return emptyList()

            // Parse Code attribute to find LocalVariableTable
            val codeReader = ClassFileReader(codeAttrData)
            codeReader.skip(4) // max_stack(2) + max_locals(2)
            val codeLen = codeReader.readU4()
            codeReader.skip(codeLen) // bytecode
            val exceptionTableLen = codeReader.readU2()
            codeReader.skip(exceptionTableLen * 8) // exception_table
            val codeAttrCount = codeReader.readU2()

            for (i in 0 until codeAttrCount) {
                val nameIdx = codeReader.readU2()
                val len = codeReader.readU4()
                val name = utf8(nameIdx)

                if (name == "LocalVariableTable") {
                    val tableLen = codeReader.readU2()
                    val names = mutableMapOf<Int, String>() // slot → name

                    for (j in 0 until tableLen) {
                        codeReader.skip(2) // start_pc
                        codeReader.skip(2) // length
                        val varNameIdx = codeReader.readU2()
                        codeReader.skip(2) // descriptor_index
                        val slot = codeReader.readU2()

                        if (names[slot] == null) {
                            names[slot] = utf8(varNameIdx)
                        }
                    }

                    // Slots: 0=this, 1..paramCount=parameters
                    val result = mutableListOf<String>()
                    for (slot in 1..paramCount) {
                        result.add(names[slot] ?: "arg$slot")
                    }
                    return result
                } else {
                    codeReader.skip(len)
                }
            }

            return emptyList()
        }
    }
}
