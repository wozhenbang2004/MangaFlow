package dev.mangaflow.platform

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Small shared caches retain their original atomic lookup/update semantics on every target. */
class ConcurrentMap<K, V>() : MutableMap<K, V> {
    private val lock = SynchronizedObject()
    private val contents = mutableMapOf<K, V>()
    constructor(initialCapacity: Int) : this() { require(initialCapacity >= 0) }
    override val size: Int get() = synchronized(lock) { contents.size }
    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = synchronized(lock) { contents.toMutableMap().entries }
    override val keys: MutableSet<K> get() = synchronized(lock) { contents.keys.toMutableSet() }
    override val values: MutableCollection<V> get() = synchronized(lock) { contents.values.toMutableList() }
    override fun containsKey(key: K): Boolean = synchronized(lock) { contents.containsKey(key) }
    override fun containsValue(value: V): Boolean = synchronized(lock) { contents.containsValue(value) }
    override fun get(key: K): V? = synchronized(lock) { contents[key] }
    override fun isEmpty(): Boolean = synchronized(lock) { contents.isEmpty() }
    override fun clear() = synchronized(lock) { contents.clear() }
    override fun put(key: K, value: V): V? = synchronized(lock) { contents.put(key, value) }
    override fun putAll(from: Map<out K, V>) = synchronized(lock) { contents.putAll(from) }
    override fun remove(key: K): V? = synchronized(lock) { contents.remove(key) }
    fun remove(key: K, value: V): Boolean = synchronized(lock) {
        if (!contents.containsKey(key) || contents[key] != value) false
        else { contents.remove(key); true }
    }
    fun putIfAbsent(key: K, value: V): V? = synchronized(lock) {
        val previous = contents[key]
        if (!contents.containsKey(key)) contents[key] = value
        previous
    }
    fun computeIfAbsent(key: K, factory: (K) -> V): V = synchronized(lock) {
        @Suppress("UNCHECKED_CAST")
        if (contents.containsKey(key)) contents[key] as V else factory(key).also { contents[key] = it }
    }
    fun getOrPut(key: K, factory: () -> V): V = computeIfAbsent(key) { factory() }
    fun merge(key: K, value: V, merge: (V, V) -> V): V = synchronized(lock) {
        @Suppress("UNCHECKED_CAST")
        val updated = if (contents.containsKey(key)) merge(contents[key] as V, value) else value
        contents[key] = updated
        updated
    }
}

class ConcurrentSet<E> : AbstractMutableSet<E>() {
    private val lock = SynchronizedObject()
    private val contents = mutableSetOf<E>()
    override val size get() = synchronized(lock) { contents.size }
    override fun add(element: E): Boolean = synchronized(lock) { contents.add(element) }
    override fun contains(element: E): Boolean = synchronized(lock) { element in contents }
    override fun remove(element: E): Boolean = synchronized(lock) { contents.remove(element) }
    override fun clear() = synchronized(lock) { contents.clear() }
    override fun iterator(): MutableIterator<E> {
        val snapshot = synchronized(lock) { contents.toList() }.iterator()
        var previous: E? = null
        var removable = false
        return object : MutableIterator<E> {
            override fun hasNext() = snapshot.hasNext()
            override fun next(): E = snapshot.next().also { previous = it; removable = true }
            override fun remove() {
                check(removable)
                @Suppress("UNCHECKED_CAST")
                this@ConcurrentSet.remove(previous as E)
                removable = false
            }
        }
    }
}

class AtomicLong(initial: Long = 0L) {
    private val value = atomic(initial)
    fun get(): Long = value.value
    fun set(next: Long) { value.value = next }
    fun getAndSet(next: Long): Long = value.getAndSet(next)
    fun compareAndSet(expect: Long, update: Long): Boolean = value.compareAndSet(expect, update)
    fun incrementAndGet(): Long = value.incrementAndGet()
    fun getAndIncrement(): Long = value.getAndIncrement()
    fun addAndGet(delta: Long): Long = value.addAndGet(delta)
}

class AtomicBoolean(initial: Boolean = false) {
    private val value = atomic(initial)
    fun get(): Boolean = value.value
    fun set(next: Boolean) { value.value = next }
    fun getAndSet(next: Boolean): Boolean = value.getAndSet(next)
    fun compareAndSet(expect: Boolean, update: Boolean): Boolean = value.compareAndSet(expect, update)
}
