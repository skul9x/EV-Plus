package com.evcs.favorites.util

import java.util.LinkedHashMap

/**
 * Thread-safe, pure-Kotlin Bounded LRU Cache.
 * Backed by a synchronized [LinkedHashMap] configured with access-order eviction.
 *
 * Runs seamlessly in both JVM unit tests and Android runtime without
 * requiring Android framework dependencies (such as android.util.LruCache).
 *
 * @param maxEntries Maximum number of entries before eldest entries are evicted. Defaults to 256.
 */
class BoundedLruCache<K, V>(
    val maxEntries: Int = 256
) {
    init {
        require(maxEntries > 0) { "maxEntries must be greater than 0" }
    }

    private val map = object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxEntries
        }
    }

    /**
     * Returns the value corresponding to [key], or `null` if not present.
     * Accessing the element moves it to the most-recently-used position.
     */
    @Synchronized
    fun get(key: K): V? = map[key]

    /**
     * Stores [key] with [value] in the cache.
     * Evicts the eldest entry if cache capacity is exceeded.
     */
    @Synchronized
    fun put(key: K, value: V): V? = map.put(key, value)

    /**
     * Returns the existing value for [key] if present, or computes and caches
     * the result of [compute] before returning it.
     */
    @Synchronized
    fun computeIfAbsent(key: K, compute: (K) -> V): V {
        val existing = map[key]
        if (existing != null) {
            return existing
        }
        val computed = compute(key)
        map[key] = computed
        return computed
    }

    /**
     * Removes the mapping for [key] from the cache.
     */
    @Synchronized
    fun remove(key: K): V? = map.remove(key)

    /**
     * Clears all entries from the cache.
     */
    @Synchronized
    fun clear() {
        map.clear()
    }

    /**
     * Returns the current number of entries in the cache.
     */
    @Synchronized
    fun size(): Int = map.size

    /**
     * Checks if the cache contains [key].
     */
    @Synchronized
    fun containsKey(key: K): Boolean = map.containsKey(key)

    /**
     * Returns a snapshot of current keys in access order (eldest to newest).
     */
    @Synchronized
    fun keys(): List<K> = map.keys.toList()

    /**
     * Returns a snapshot of current values in access order (eldest to newest).
     */
    @Synchronized
    fun values(): List<V> = map.values.toList()
}
