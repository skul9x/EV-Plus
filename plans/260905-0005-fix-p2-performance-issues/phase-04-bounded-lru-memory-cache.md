# Phase 04: Bounded LRU Memory Caching
Status: ✅ Completed  
Dependencies: `phase-03-public-cache-storage-decoupling.md`  
Issue IDs: `PERF-CACHE-01`

## Objective
Prevent creeping in-memory leaks and unbounded cache growth by introducing thread-safe bounded Least-Recently-Used (LRU) eviction for in-memory collections:
1. Wrap or replace unbounded in-memory maps (`coordinateCache` in `EvcsRepository`, `routingCache` in `FavoritesViewModel`) with bounded capacity structures.
2. Evict eldest entries when maximum capacity is exceeded, capping memory consumption regardless of how long the user browses the app.

## Requirements
### Functional
- Caches retain most recently queried coordinates and routing metrics up to their configured capacity.
- Thread-safe concurrent access from multiple coroutines without race conditions or deadlocks.
- Clear operations (`clearCoordinateCache()`, `invalidateRoutingCache()`) continue to wipe caches immediately.

### Non-Functional
- Strictly bounded memory footprint (e.g. maximum 500 coordinates in `EvcsRepository` and 100 routes in `FavoritesViewModel`).
- Least-recently-accessed entries are evicted first upon reaching capacity.

## Implementation Steps
1. [x] Create thread-safe utility `app/src/main/java/com/evcs/favorites/data/cache/BoundedLruMap.kt`:
   - Extend or wrap `LinkedHashMap<K, V>` with `accessOrder = true`:
     ```kotlin
     class BoundedLruMap<K, V>(
         val maxCapacity: Int
     ) : MutableMap<K, V> {
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
         override fun putAll(from: Map<out K, V>) = synchronized(lock) { map.putAll(from) }
         override fun clear() = synchronized(lock) { map.clear() }

         // Snapshot-based accessors to prevent ConcurrentModificationException
         override val keys: MutableSet<K> get() = synchronized(lock) { LinkedHashSet(map.keys) }
         override val values: MutableCollection<V> get() = synchronized(lock) { ArrayList(map.values) }
         override val entries: MutableSet<MutableMap.MutableEntry<K, V>> 
             get() = synchronized(lock) { LinkedHashMap(map).entries }

         fun snapshot(): Map<K, V> = synchronized(lock) { LinkedHashMap(map) }
     }
     ```
2. [x] In `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt`:
   - Change `coordinateCache` parameter in primary constructor from `ConcurrentHashMap` to `MutableMap<String, Pair<Double, Double>> = BoundedLruMap(maxCapacity = 500)`.
   - Remove obsolete `DelegatingConcurrentMap` inner class since any `MutableMap` (including `BoundedLruMap` and test doubles) is natively supported.
3. [x] In `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt`:
   - Change `routingCache` from unbounded `mutableMapOf()` to `BoundedLruMap<String, DrivingMetrics>(maxCapacity = 100)`.
4. [x] Create single comprehensive test `app/src/test/java/com/evcs/favorites/data/cache/BoundedLruMemoryCacheTest.kt`:
   - Verifies `BoundedLruMap` strictly enforces maximum capacity and evicts the eldest element when new elements are inserted.
   - Verifies accessing an entry moves it to the most-recently-used position.
   - Verifies thread safety under concurrent coroutine reads and writes without `ConcurrentModificationException`.
   - Verifies eviction behavior in `EvcsRepository` coordinate cache.
5. [x] Run single test:
   ```bash
   ./gradlew testDebugUnitTest --tests com.evcs.favorites.data.cache.BoundedLruMemoryCacheTest
   ```

## Files to Create/Modify
- `app/src/main/java/com/evcs/favorites/data/cache/BoundedLruMap.kt` - [NEW] Bounded thread-safe LRU map implementation with snapshot accessors
- `app/src/main/java/com/evcs/favorites/data/repository/EvcsRepository.kt` - [MODIFY] Use BoundedLruMap for coordinates and remove DelegatingConcurrentMap
- `app/src/main/java/com/evcs/favorites/ui/viewmodel/FavoritesViewModel.kt` - [MODIFY] Use bounded cache for driving metrics
- `app/src/test/java/com/evcs/favorites/data/cache/BoundedLruMemoryCacheTest.kt` - [NEW] Single comprehensive verification test

## Test Criteria
- [x] Bounded map does not exceed `maxCapacity`.
- [x] Eldest items are evicted while recently accessed items are retained.
- [x] Concurrent reads and writes execute without deadlocks or ConcurrentModificationException.
- [x] Exactly one test file is executed and passes cleanly.

---
Next Phase: `phase-05-location-jitter-and-async-compute.md`
