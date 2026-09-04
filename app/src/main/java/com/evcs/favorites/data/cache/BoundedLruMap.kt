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
}
