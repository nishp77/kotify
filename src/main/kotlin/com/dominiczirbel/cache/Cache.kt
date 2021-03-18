package com.dominiczirbel.cache

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dominiczirbel.cache.Cache.TTLStrategy
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import java.io.FileReader
import java.nio.file.Files
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.measureTime

/**
 * An object which can be added to a [Cache] which provides its own [id] and an optional set of additional
 * [cacheableObjects] which are associated with it and should also be cached.
 *
 * This allows for recursive addition of objects attached to a single object, for example if fetching an album also
 * returns a list of its tracks, the track objects can be individually cached as well.
 *
 * TODO don't save associated objects as part of the cache? (but we need to re-associate them when loading)
 */
interface CacheableObject {
    /**
     * The ID of the cached object; if null it will not be cached.
     */
    val id: String?

    /**
     * An optional collection of associated objects which should also be cached alongside this [CacheableObject].
     *
     * Should NOT include this [CacheableObject].
     */
    val cacheableObjects: Collection<CacheableObject>
        get() = emptySet()

    /**
     * Recursively finds all associated [CacheableObject] from this [CacheableObject] and its [cacheableObjects].
     *
     * Note that this will loop infinitely if there is a cycle of [CacheableObject]s, so associations must be acyclic.
     */
    val recursiveCacheableObjects: Collection<CacheableObject>
        get() = cacheableObjects.flatMap { it.recursiveCacheableObjects }.plus(this)
}

/**
 * A wrapper class around a cached object [obj], with caching metadata and a custom [CacheObject.Serializer] to
 * serialize arbitrary values and store their [type] for deserialization.
 *
 * Cached objects must themselves be [Serializable].
 */
@Serializable(with = CacheObject.Serializer::class)
data class CacheObject(
    /**
     * The cached object's ID, unique among objects in the [Cache].
     *
     * The object ID is used for arbitrary lookup, i.e. [Cache.get].
     */
    val id: String,

    /**
     * The time the object was cached.
     */
    val cacheTime: Long = System.currentTimeMillis(),

    /**
     * The type of the cached [obj], i.e. the [java.lang.Class.getTypeName] of its class.
     *
     * This is used to determine the class to be deserialized at runtime.
     */
    val type: String,

    /**
     * A hash of the [obj]'s class, i.e. [java.lang.Class.hashCode].
     *
     * This is used to verify that the underlying class is the same at deserialization time as it was at serialization
     * time; if they conflict a [CacheObject.Serializer.ClassHashChangedException] will be thrown.
     */
    val classHash: Int,

    /**
     * The data being cached.
     */
    val obj: Any
) {
    constructor(id: String, obj: Any, cacheTime: Long = System.currentTimeMillis()) : this(
        id = id,
        cacheTime = cacheTime,
        type = obj::class.java.typeName,
        classHash = obj::class.hashFields(),
        obj = obj
    )

    companion object {
        private val hashes: MutableMap<KClass<*>, Int> = mutableMapOf()

        /**
         * Creates a hash of the fields and methods of this [KClass], to verify that the fields are the same when
         * deserializing as when they were serialized.
         *
         * Note that neither [KClass] nor [Class] provides a [hashCode] with these semantics, so this custom
         * implementation is necessary.
         */
        private fun KClass<*>.hashFields(): Int {
            return hashes.getOrPut(this) {
                val fields = java.fields
                    .map { field -> field.name + field.type.canonicalName }
                    .sorted()
                val methods = java.methods
                    .map { method -> method.name + method.parameters.joinToString { it.name + it.type.canonicalName } }
                    .sorted()

                @Suppress("UnnecessaryParentheses", "MagicNumber")
                fields.hashCode() + (13 * methods.hashCode())
            }
        }
    }

    /**
     * A custom [KSerializer] which uses [type] to deserialize [obj] to the appropriate class.
     */
    @Suppress("MagicNumber")
    class Serializer : KSerializer<CacheObject> {
        class ClassHashChangedException(originalHash: Int, deserializedHash: Int, type: String) : Throwable(
            "Found conflicting class hashes for $type : " +
                "cached as $originalHash but attempting to deserialize with $deserializedHash"
        )

        @InternalSerializationApi
        @ExperimentalSerializationApi
        override val descriptor = buildClassSerialDescriptor("CacheObject") {
            element("id", PrimitiveSerialDescriptor("id", PrimitiveKind.STRING))
            element("cacheTime", PrimitiveSerialDescriptor("cacheInt", PrimitiveKind.LONG))
            element("type", PrimitiveSerialDescriptor("type", PrimitiveKind.STRING))
            element("classHash", PrimitiveSerialDescriptor("classHash", PrimitiveKind.INT))

            // this doesn't seem quite accurate (it's not actually a ContextualSerializer, just whatever runtime
            // serializer is available for the class), but seems to work - possibly because it's never really used
            element(
                "obj",
                buildSerialDescriptor("kotlinx.serialization.ContextualSerializer", SerialKind.CONTEXTUAL)
            )
        }

        @InternalSerializationApi
        @ExperimentalSerializationApi
        override fun serialize(encoder: Encoder, value: CacheObject) {
            val objClass = value.obj::class
            require(objClass.java.typeName == value.type)
            val objSerializer = objClass.serializer()

            encoder.encode {
                encodeStringElement(descriptor, 0, value.id)
                encodeLongElement(descriptor, 1, value.cacheTime)
                encodeStringElement(descriptor, 2, value.type)
                encodeIntElement(descriptor, 3, value.classHash)

                @Suppress("UNCHECKED_CAST")
                encodeSerializableElement(descriptor, 4, objSerializer as SerializationStrategy<Any>, value.obj)
            }
        }

        @ExperimentalSerializationApi
        @InternalSerializationApi
        override fun deserialize(decoder: Decoder): CacheObject {
            return decoder.decode {
                var id: String? = null
                var cacheTime: Long? = null
                var type: String? = null
                var classHash: Int? = null
                var obj: Any? = null

                while (true) {
                    when (val index = decodeElementIndex(descriptor)) {
                        0 -> id = decodeStringElement(descriptor, index)
                        1 -> cacheTime = decodeLongElement(descriptor, index)
                        2 -> type = decodeStringElement(descriptor, index)
                        3 -> classHash = decodeIntElement(descriptor, index)
                        4 -> {
                            requireNotNull(type) { "attempting to deserialize obj before type" }
                            requireNotNull(classHash) { "attempting to deserialize obj before classHash" }

                            val cls = Class.forName(type).kotlin
                            if (cls.hashFields() != classHash) {
                                // TODO find a way to catch this and remove it from the cache instead of failing to
                                //  deserialize
                                throw ClassHashChangedException(
                                    originalHash = classHash,
                                    deserializedHash = cls.hashCode(),
                                    type = type
                                )
                            }
                            val serializer = cls.serializer()
                            obj = decodeSerializableElement(descriptor, index, serializer)
                        }
                        CompositeDecoder.DECODE_DONE -> break
                        else -> error("Unexpected index: $index")
                    }
                }

                CacheObject(
                    id = requireNotNull(id) { "never deserialized id" },
                    cacheTime = requireNotNull(cacheTime) { "never deserialized cacheTime" },
                    type = requireNotNull(type) { "never deserialized type" },
                    classHash = requireNotNull(classHash) { "never deserialized classHash" },
                    obj = requireNotNull(obj) { "never deserialized obj" }
                )
            }
        }

        /**
         * Encodes the structure described by [descriptor].
         *
         * Avoids using [kotlinx.serialization.encoding.encodeStructure] since it swallows exceptions in [block] if the
         * [CompositeEncoder.endStructure] calls also throws an error, which it typically does if [block] fails.
         */
        @ExperimentalSerializationApi
        @InternalSerializationApi
        private fun Encoder.encode(block: CompositeEncoder.() -> Unit) {
            val composite = beginStructure(descriptor)
            composite.block()
            composite.endStructure(descriptor)
        }

        /**
         * Decodes the structure described by [descriptor].
         *
         * Avoids using [kotlinx.serialization.encoding.decodeStructure] since it swallows exceptions in [block] if the
         * [CompositeDecoder.endStructure] calls also throws an error, which it typically does if [block] fails.
         */
        @ExperimentalSerializationApi
        @InternalSerializationApi
        private fun <T> Decoder.decode(block: CompositeDecoder.() -> T): T {
            val composite = beginStructure(descriptor)
            return composite.block().also { composite.endStructure(descriptor) }
        }
    }
}

// TODO add batched get and put events
sealed class CacheEvent {
    abstract val cache: Cache

    data class Load(override val cache: Cache, val duration: Duration, val file: File) : CacheEvent()
    data class Save(override val cache: Cache, val duration: Duration, val file: File) : CacheEvent()
    data class Dump(override val cache: Cache) : CacheEvent()
    data class Clear(override val cache: Cache) : CacheEvent()
    data class Hit(override val cache: Cache, val id: String, val value: CacheObject) : CacheEvent()
    data class Miss(override val cache: Cache, val id: String) : CacheEvent()
    data class Update(override val cache: Cache, val id: String, val previous: CacheObject?, val new: CacheObject) :
        CacheEvent()

    data class Invalidate(override val cache: Cache, val id: String, val value: CacheObject) : CacheEvent()
}

/**
 * A generic, JSON-based local disk cache for arbitrary objects.
 *
 * Objects are wrapped as [CacheObject]s, which provides some metadata and a way to serialize/deserialize the object as
 * JSON without losing its JVM class.
 *
 * Objects are cached in-memory with no limit, and can be saved to [file] with [save] or loaded from disk (replacing the
 * in-memory cache) with [load].
 *
 * An optional [ttlStrategy] can limit the values in the cache to observe an arbitrary [TTLStrategy]. Once the
 * [ttlStrategy] marks an object as invalid, it will no longer appear in any of the cache accessors, e.g. [cache],
 * [get], etc., but may only be removed from memory once it is attempted to be accessed.
 *
 * [eventHandler] will be invoked whenever this [Cache] processes a [CacheEvent].
 */
class Cache(
    private val file: File,
    val saveOnChange: Boolean = false,
    private val ttlStrategy: TTLStrategy = TTLStrategy.AlwaysValid,
    private val replacementStrategy: ReplacementStrategy = ReplacementStrategy.AlwaysReplace,
    private val eventHandler: (List<CacheEvent>) -> Unit = { },
    private val onSave: () -> Unit = { }
) {
    private val json = Json {
        encodeDefaults = true
    }

    private val _cache: MutableMap<String, CacheObject> = mutableMapOf()

    private var lastSaveHash: Int? = null

    private val cacheEventQueue = mutableListOf<CacheEvent>()

    /**
     * The full set of valid, in-memory [CacheObject]s.
     */
    val cache: Map<String, CacheObject>
        get() = synchronized(_cache) { removeExpired() }.also { eventHandler(listOf(CacheEvent.Dump(this))) }

    var size by mutableStateOf(0)
        private set

    /**
     * Gets the [CacheObject] associated with [id], if it exists in the in-memory cache and is still valid according to
     * [ttlStrategy].
     *
     * If the value is expired according to [ttlStrategy] null is returned and it is removed from the in-memory cache,
     * but it is _not_ saved to disk, regardless of [saveOnChange]. Such writes to disk would almost always be
     * unnecessary and too costly.
     */
    fun getCached(id: String): CacheObject? {
        return synchronized(_cache) { getCachedInternal(id) }.also { flushCacheEvents() }
    }

    /**
     * Gets the [CacheObject] associated with each [ids], if it exists in the in-memory cache and is still valid
     * according to [ttlStrategy].
     */
    fun getAllCached(ids: List<String>): List<CacheObject?> {
        return synchronized(_cache) {
            ids.map { id -> getCachedInternal(id) }
        }.also { flushCacheEvents() }
    }

    /**
     * Writes [value] and all its [CacheableObject.recursiveCacheableObjects] to the in-memory cache, using their
     * [CacheableObject.id]s, returning true if any values were added or changed in the cache.
     *
     * If a value is already cached with a certain id, it will be removed as determined by the [replacementStrategy].
     *
     * [cacheTime] is the time the object(s) should be considered cached; by default this is the current system time but
     * may be an arbitrary value, e.g. to reflect a value which was fetched previously and thus may already be
     * out-of-date.
     *
     * If [saveOnChange] is true (defaulting to [Cache.saveOnChange]) and any values were added or changed in the cache,
     * [save] will be called.
     */
    fun put(
        value: CacheableObject,
        cacheTime: Long = System.currentTimeMillis(),
        saveOnChange: Boolean = this.saveOnChange
    ): Boolean {
        return synchronized(_cache) {
            val change = putInternal(value, cacheTime)
            saveInternal(shouldSave = saveOnChange && change)
            change
        }.also { flushCacheEvents() }
    }

    /**
     * Writes all the [values] (and all their recursive [CacheableObject.recursiveCacheableObjects]) to the in-memory
     * cache, using their [CacheableObject.id]s, returning true if any values were added or changed in the cache.
     *
     * If a value is already cached with a certain id, it will be removed as determined by the [replacementStrategy].
     *
     * If [saveOnChange] is true (defaulting to [Cache.saveOnChange]) and any values were added or changed in the cache,
     * [save] will be called.
     */
    fun putAll(values: Iterable<CacheableObject>, saveOnChange: Boolean = this.saveOnChange): Boolean {
        return synchronized(_cache) {
            var change = false
            values.forEach {
                change = putInternal(it) || change
            }
            saveInternal(shouldSave = change && saveOnChange)
            change
        }.also { flushCacheEvents() }
    }

    /**
     * Writes [value] to the in-memory cache, under the given [id].
     *
     * If a value is already cached with the given [id], it will be removed as determined by the [replacementStrategy];
     * if replaced (or if there was no previous cached object), true will be returned; otherwise false will be returned.
     *
     * [cacheTime] is the time the object should be considered cached; by default this is the current system time but
     * may be an arbitrary value, e.g. to reflect a value which was fetched previously and thus may already be
     * out-of-date.
     *
     * If [saveOnChange] is true (defaulting to [Cache.saveOnChange]) and the value was written to the cache, the
     * in-memory cache will be written to disk.
     */
    fun put(
        id: String,
        value: Any,
        cacheTime: Long = System.currentTimeMillis(),
        saveOnChange: Boolean = this.saveOnChange
    ): Boolean {
        return synchronized(_cache) {
            val change = putInternal(id = id, value = value, cacheTime = cacheTime)
            saveInternal(shouldSave = change && saveOnChange)
            change
        }.also { flushCacheEvents() }
    }

    /**
     * Gets the value of type [T] in the cache for [id], or if the value for [id] does not exist or has a type other
     * than [T], fetches a new value from [remote], puts it in the cache, and returns it.
     *
     * If the remotely-fetched value is a [CacheableObject], all of its [CacheableObject.recursiveCacheableObjects] will
     * be added to the cache as well.
     */
    inline fun <reified T : Any> get(id: String, saveOnChange: Boolean = this.saveOnChange, remote: () -> T): T {
        return getCached(id)?.obj as? T
            ?: remote().also {
                if (it is CacheableObject) {
                    put(it, saveOnChange = saveOnChange)
                } else {
                    put(id, it, saveOnChange = saveOnChange)
                }
            }
    }

    /**
     * Gets all the values of type [T] in the cache for each of [ids], or if the value for an ID does not exist or has a
     * type other than [T], fetches a new value for it from [remote], puts it and all its
     * [CacheableObject.recursiveCacheableObjects] in the cache, and returns it.
     */
    inline fun <reified T : CacheableObject> getAll(
        ids: List<String>,
        saveOnChange: Boolean = this.saveOnChange,
        remote: (String) -> Deferred<T>
    ): List<T> {
        val cached: List<CacheObject?> = getAllCached(ids = ids)
        check(cached.size == ids.size)

        val jobs = mutableMapOf<Int, Deferred<T>>()
        cached.forEachIndexed { index, cacheObject ->
            if (cacheObject == null) {
                jobs[index] = remote(ids[index])
            }
        }

        val newObjects = mutableSetOf<T>()
        return cached
            .mapIndexed { index, cacheObject ->
                cacheObject?.obj as? T
                    ?: runBlocking {
                        jobs.getValue(index).await().also { newObjects.add(it) }
                    }
            }
            .also { putAll(newObjects, saveOnChange = saveOnChange) }
    }

    /**
     * Gets all the valid values in the cache of type [T].
     */
    inline fun <reified T : Any> allOfType(): List<T> {
        return cache.values.mapNotNull { it.obj as? T }
    }

    /**
     * Invalidates the cached value with the given [id], removing it from the cache and returning it.
     *
     * If there was a cached value to invalidate and [saveOnChange] is true (defaulting to [Cache.saveOnChange]), the
     * cache will be written to disk.
     */
    fun invalidate(id: String, saveOnChange: Boolean = this.saveOnChange): CacheObject? {
        return synchronized(_cache) {
            _cache.remove(id)
                ?.also { queueCacheEvent(CacheEvent.Invalidate(cache = this, id = id, value = it)) }
                ?.also { saveInternal(shouldSave = saveOnChange) }
        }?.also { flushCacheEvents() }
    }

    /**
     * Clears the cache, both in-memory and on disk.
     */
    fun clear() {
        synchronized(_cache) {
            _cache.clear()
            queueCacheEvent(CacheEvent.Clear(this))
            saveInternal(shouldSave = true)
        }
        flushCacheEvents()
    }

    /**
     * Writes the current in-memory cache to [file] as JSON, removing any values that have expired according to
     * [ttlStrategy].
     */
    fun save() {
        synchronized(_cache) {
            removeExpired()
            saveInternal(shouldSave = true)
        }
        flushCacheEvents()
    }

    /**
     * Loads the saved cache from [file] and replaces all current in-memory values with its contents.
     *
     * Simply clears the cache if the file does not exist.
     */
    fun load() {
        var duration: Duration? = null
        synchronized(_cache) {
            _cache.clear()
            if (file.canRead()) {
                duration = measureTime {
                    _cache.putAll(
                        FileReader(file)
                            .use { it.readLines().joinToString(separator = " ") }
                            .let { json.decodeFromString<Map<String, CacheObject>>(it) }
                            .filterValues { ttlStrategy.isValid(it) }
                    )
                }
            }
            lastSaveHash = _cache.hashCode()
        }

        duration?.let {
            eventHandler(listOf(CacheEvent.Load(cache = this, duration = it, file = file)))
        }
    }

    /**
     * Removes values from [_cache] which are expired according to [ttlStrategy], and returns a copy of [_cache] with
     * the expired values removed.
     *
     * Must be externally synchronized on [_cache].
     */
    private fun removeExpired(): Map<String, CacheObject> {
        val filtered = mutableMapOf<String, CacheObject>()
        var anyFiltered = false
        for (entry in _cache) {
            if (ttlStrategy.isValid(entry.value)) {
                filtered[entry.key] = entry.value
            } else {
                anyFiltered = true
            }
        }

        if (anyFiltered) {
            _cache.clear()
            _cache.putAll(filtered)
        }

        return filtered
    }

    /**
     * Gets the cached value under [id], returning null and removing it from the in-memory cache if it is expired
     * according to [ttlStrategy].
     *
     * Must be externally synchronized on [_cache] and usages should call [flushCacheEvents].
     */
    private fun getCachedInternal(id: String): CacheObject? {
        val value = _cache[id]
        if (value == null) {
            queueCacheEvent(CacheEvent.Miss(this, id))
        }

        return value?.let { cacheObject ->
            cacheObject.takeIf { ttlStrategy.isValid(it) }
                ?.also { queueCacheEvent(CacheEvent.Hit(cache = this, id = id, value = it)) }
                ?: null.also { _cache.remove(id) }
        }
    }

    /**
     * Puts [value] in the in-memory cache under [id] if the [replacementStrategy] allows it, returning true if the
     * value was added or changed.
     *
     * Must be externally synchronized on [_cache] and usages should call [flushCacheEvents].
     */
    private fun putInternal(
        id: String,
        value: Any,
        cacheTime: Long = System.currentTimeMillis()
    ): Boolean {
        val current = getCachedInternal(id)?.obj
        val replace = current?.let { replacementStrategy.replace(it, value) } != false
        if (replace) {
            val previous = _cache[id]
            val new = CacheObject(id = id, obj = value, cacheTime = cacheTime)
            _cache[id] = new

            queueCacheEvent(CacheEvent.Update(this, id, previous = previous, new = new))
        }

        return replace
    }

    /**
     * Puts [value] and all its [CacheableObject.recursiveCacheableObjects] in the in-memory cache if the
     * [replacementStrategy] allows it, returning true if any value was added or changed.
     *
     * Must be externally synchronized on [_cache] and usages should call [flushCacheEvents].
     */
    private fun putInternal(value: CacheableObject, cacheTime: Long = System.currentTimeMillis()): Boolean {
        var change = false
        listOf(value).plus(value.recursiveCacheableObjects).forEach { cacheableObject ->
            cacheableObject.id?.let { id ->
                change = putInternal(id = id, value = cacheableObject, cacheTime = cacheTime) || change
            }
        }
        return change
    }

    /**
     * Writes the current in-memory cache to [file] as JSON if [shouldSave] is true (and it has changed since the last
     * save/load).
     *
     * Must be externally synchronized on [_cache] and usages should call [flushCacheEvents].
     */
    private fun saveInternal(shouldSave: Boolean) {
        if (shouldSave) {
            val currentHash = _cache.hashCode()
            if (currentHash != lastSaveHash) {
                // TODO move out of synchronized block
                val duration = measureTime {
                    val content = json.encodeToString(_cache)
                    Files.writeString(file.toPath(), content)
                }

                queueCacheEvent(CacheEvent.Save(cache = this, duration = duration, file = file))
                lastSaveHash = currentHash
            }
        }
    }

    /**
     * Queues [event] to the [cacheEventQueue], to be later flushed by [flushCacheEvents].
     *
     * This avoids calling the [eventHandler] in performance-critical sections of code (i.e. sections which are
     * synchronized on the cache object) and allows batching events for cases where many operations happen at once.
     */
    private fun queueCacheEvent(event: CacheEvent) {
        synchronized(cacheEventQueue) {
            cacheEventQueue.add(event)
        }
    }

    /**
     * Calls the [eventHandler] for each event in the [cacheEventQueue] and clears it.
     */
    private fun flushCacheEvents() {
        val saved: Boolean
        synchronized(cacheEventQueue) {
            // TODO performance
            saved = cacheEventQueue.any { it is CacheEvent.Save }
            eventHandler(cacheEventQueue.toList())
            cacheEventQueue.clear()
        }

        size = _cache.size

        if (saved) {
            onSave()
        }
    }

    /**
     * A generic strategy for determining whether new values should replace existing cached values.
     */
    interface ReplacementStrategy {
        /**
         * Determines whether the [current] object should be replaced by the [new] value.
         */
        fun replace(current: Any, new: Any): Boolean

        /**
         * A [TTLStrategy] which always replaces cached values.
         */
        object AlwaysReplace : ReplacementStrategy {
            override fun replace(current: Any, new: Any) = true
        }

        /**
         * A [TTLStrategy] which never replaces cached values.
         */
        object NeverReplace : ReplacementStrategy {
            override fun replace(current: Any, new: Any) = false
        }
    }

    /**
     * A generic strategy for determining whether cached values are still valid; typically cached values may become
     * invalid after a certain amount of time in the cache.
     */
    interface TTLStrategy {
        fun isValid(cacheObject: CacheObject): Boolean = isValid(cacheObject.cacheTime, cacheObject.obj)

        /**
         * Determines whether the given [obj], cached at [cacheTime], is still valid.
         */
        fun isValid(cacheTime: Long, obj: Any): Boolean

        /**
         * A [TTLStrategy] which always marks cache elements as valid, so they will never be evicted.
         */
        object AlwaysValid : TTLStrategy {
            override fun isValid(cacheTime: Long, obj: Any) = true
        }

        /**
         * A [TTLStrategy] which never marks cache elements as valid, so they will always be fetched from a remote
         * source. This is equivalent to not having a cache.
         */
        object NeverValid : TTLStrategy {
            override fun isValid(cacheTime: Long, obj: Any) = false
        }

        /**
         * A [TTLStrategy] with a [ttl] applied to all cache elements; elements in teh cache for more that [ttl]
         * milliseconds will be evicted.
         */
        class UniversalTTL(private val ttl: Long) : TTLStrategy {
            override fun isValid(cacheTime: Long, obj: Any): Boolean {
                return cacheTime + ttl >= System.currentTimeMillis()
            }
        }

        /**
         * A [TTLStrategy] with a TTL applied class-by-class to cache elements, so elements of certain classes may
         * persist longer in the cache than elements of other classes.
         *
         * [defaultTTL] is used for elements of classes that are not in [classMap]; if null and a cached element is not
         * in [classMap], an exception will be thrown.
         */
        class TTLByClass(
            private val classMap: Map<KClass<*>, Long>,
            private val defaultTTL: Long? = null
        ) : TTLStrategy {
            override fun isValid(cacheTime: Long, obj: Any): Boolean {
                val ttl = classMap[obj::class] ?: defaultTTL
                requireNotNull(ttl) { "no TTL for class ${obj::class}" }

                return cacheTime + ttl >= System.currentTimeMillis()
            }
        }
    }
}
