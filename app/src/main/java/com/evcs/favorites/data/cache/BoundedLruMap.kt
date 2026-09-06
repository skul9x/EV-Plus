package com.evcs.favorites.data.cache

/**
 * Thread-safe bounded Least-Recently-Used (LRU) [MutableMap] implementation.
 * Evicts eldest entries when [maxCapacity] is exceeded upon insertion.
 */
class BoundedLruMap<K, V>(
    val maxCapacity: Int
) : MutableMap<K, V> {
    init {
        require(maxCapacity > 0) { "maxCapacity must be greater than 0" }
    }

    private val lock = Any()
    private val map = object : LinkedHashMap<K, V>(maxCapacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxCapacity
        }
    }

    // Thread-safe delegations with synchronized(lock)
    override val size: Int get() = synchronized(lock) { map.size }
    override fun isEmpty(): Boolean = synchronized(lock) { map.isEmpty() }
    override fun containsKey(key: K): Boolean = synchronized(lock) { map.containsKey(key) }
    override fun containsValue(value: V): Boolean = synchronized(lock) { map.containsValue(value) }
    override fun get(key: K): V? = synchronized(lock) { map[key] }
    override fun put(key: K, value: V): V? = synchronized(lock) { map.put(key, value) }
    override fun remove(key: K): V? = synchronized(lock) { map.remove(key) }
    override fun putAll(from: Map<out K, V>): Unit = synchronized(lock) { map.putAll(from) }
    override fun clear(): Unit = synchronized(lock) { map.clear() }

    // Snapshot-based accessors to prevent ConcurrentModificationException
    override val keys: MutableSet<K> get() = synchronized(lock) { LinkedHashSet(map.keys) }
    override val values: MutableCollection<V> get() = synchronized(lock) { ArrayList(map.values) }
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = synchronized(lock) { LinkedHashMap(map).entries }

    fun snapshot(): Map<K, V> = synchronized(lock) { LinkedHashMap(map) }

    /**
     * Executes the given [action] on each entry while holding the internal lock.
     * Prevents [ConcurrentModificationException] and eliminates intermediate defensive copies.
     */
    fun forEachThreadSafe(action: (K, V) -> Unit) {
        synchronized(lock) {
            for ((k, v) in map) {
                action(k, v)
            }
        }
    }

    /**
     * Transforms the entries in this map while holding the internal lock,
     * directly constructing the result map without intermediate defensive cloning.
     *
     * @param transform Function mapping each [Map.Entry] to a new value of type [R].
     * @return A map with the same keys and transformed values.
     */
    fun <R> mapValuesThreadSafe(transform: (Map.Entry<K, V>) -> R): Map<K, R> {
        synchronized(lock) {
            val destination = LinkedHashMap<K, R>(map.size)
            for (entry in map.entries) {
                destination[entry.key] = transform(entry)
            }
            return destination
        }
    }
}

/**
 * Thread-safe forEach delegation for [MutableMap] instances, delegating to [BoundedLruMap.forEachThreadSafe]
 * when available, or synchronizing on the instance.
 */
fun <K, V> MutableMap<K, V>.forEachThreadSafe(action: (K, V) -> Unit) {
    if (this is BoundedLruMap<K, V>) {
        this.forEachThreadSafe(action)
    } else {
        synchronized(this) {
            for ((k, v) in this) {
                action(k, v)
            }
        }
    }
}

/**
 * Thread-safe mapValues delegation for [MutableMap] instances, delegating to [BoundedLruMap.mapValuesThreadSafe]
 * when available, or synchronizing on the instance without intermediate defensive cloning.
 */
fun <K, V, R> MutableMap<K, V>.mapValuesThreadSafe(transform: (Map.Entry<K, V>) -> R): Map<K, R> {
    return if (this is BoundedLruMap<K, V>) {
        this.mapValuesThreadSafe(transform)
    } else {
        synchronized(this) {
            val destination = LinkedHashMap<K, R>(this.size)
            for (entry in this.entries) {
                destination[entry.key] = transform(entry)
            }
            destination
        }
    }
}
