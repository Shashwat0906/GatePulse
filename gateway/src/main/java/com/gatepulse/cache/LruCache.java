package com.gatepulse.cache;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * A least-recently-used cache with per-entry time-to-live, built from scratch.
 *
 * <h2>Data structure</h2>
 * <pre>
 *   HashMap: key -> node            (O(1) lookup)
 *   doubly linked list of nodes     (O(1) move-to-front and remove)
 *
 *   head &lt;-&gt; [most recent] &lt;-&gt; ... &lt;-&gt; [least recent] &lt;-&gt; tail
 * </pre>
 * {@code head} and {@code tail} are sentinel nodes, so insert/unlink never need null checks.
 * Every hit moves the node to the front; when the cache is over capacity the node just before
 * {@code tail} (least recently used) is evicted. All operations are O(1).
 *
 * <h2>TTL</h2>
 * Each entry stores an absolute expiry time. Expiry is lazy: an expired entry is removed when
 * it is next looked up (counted as a miss), or pushed out by LRU eviction. No background sweeper
 * is needed for correctness.
 *
 * <h2>Thread-safety</h2>
 * One {@link ReentrantLock} guards the map and the list together. A lock-free design is hard
 * here because even a read ({@code get}) mutates the list order. The critical sections are a
 * handful of pointer updates (~100ns), which is far below the cost of the backend call the
 * cache saves. Lock striping (N independent LRU segments) is the next step if it ever became
 * a bottleneck; it trades exact global LRU order for less contention.
 *
 * @param <K> key type (must have proper equals/hashCode)
 * @param <V> value type
 */
public final class LruCache<K, V> {

    private static final class Node<K, V> {
        K key;
        V value;
        long expiresAtNanos;
        Node<K, V> prev;
        Node<K, V> next;
    }

    /** Immutable statistics snapshot. */
    public record Stats(int size, int capacity, long hits, long misses, long evictions, long expirations) {
        public double hitRatio() {
            long total = hits + misses;
            return total == 0 ? 0 : (double) hits / total;
        }
    }

    private final Map<K, Node<K, V>> map = new HashMap<>();
    private final Node<K, V> head = new Node<>();
    private final Node<K, V> tail = new Node<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final LongSupplier nanoClock;

    private int capacity;
    private long hits;
    private long misses;
    private long evictions;
    private long expirations;

    public LruCache(int capacity) {
        this(capacity, System::nanoTime);
    }

    public LruCache(int capacity, LongSupplier nanoClock) {
        requireValidCapacity(capacity);
        this.capacity = capacity;
        this.nanoClock = nanoClock;
        head.next = tail;
        tail.prev = head;
    }

    /** Returns the value if present and not expired; marks it most recently used. */
    public Optional<V> get(K key) {
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) {
                misses++;
                return Optional.empty();
            }
            if (nanoClock.getAsLong() - node.expiresAtNanos >= 0) {
                unlink(node);
                map.remove(key);
                expirations++;
                misses++;
                return Optional.empty();
            }
            moveToFront(node);
            hits++;
            return Optional.of(node.value);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inserts or replaces {@code key}, valid for {@code ttlNanos}. Evicts the least recently used
     * entry if this pushes the cache over capacity.
     */
    public void put(K key, V value, long ttlNanos) {
        if (ttlNanos <= 0) {
            return; // would be expired immediately; storing it would only evict something useful
        }
        long expiresAt = nanoClock.getAsLong() + ttlNanos;
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node != null) {
                node.value = value;
                node.expiresAtNanos = expiresAt;
                moveToFront(node);
                return;
            }
            node = new Node<>();
            node.key = key;
            node.value = value;
            node.expiresAtNanos = expiresAt;
            map.put(key, node);
            addFirst(node);
            evictOverflow();
        } finally {
            lock.unlock();
        }
    }

    public boolean remove(K key) {
        lock.lock();
        try {
            Node<K, V> node = map.remove(key);
            if (node == null) {
                return false;
            }
            unlink(node);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            map.clear();
            head.next = tail;
            tail.prev = head;
        } finally {
            lock.unlock();
        }
    }

    /** Changes the capacity at runtime, evicting least recently used entries if it shrinks. */
    public void resize(int newCapacity) {
        requireValidCapacity(newCapacity);
        lock.lock();
        try {
            capacity = newCapacity;
            evictOverflow();
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    public Stats stats() {
        lock.lock();
        try {
            return new Stats(map.size(), capacity, hits, misses, evictions, expirations);
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- list operations (lock held)

    private void evictOverflow() {
        while (map.size() > capacity) {
            Node<K, V> lru = tail.prev;
            unlink(lru);
            map.remove(lru.key);
            evictions++;
        }
    }

    private void addFirst(Node<K, V> node) {
        node.prev = head;
        node.next = head.next;
        head.next.prev = node;
        head.next = node;
    }

    private void unlink(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        node.prev = null;
        node.next = null;
    }

    private void moveToFront(Node<K, V> node) {
        if (head.next != node) {
            unlink(node);
            addFirst(node);
        }
    }

    /**
     * Test hook: verifies the map and the list describe exactly the same entries, in a
     * consistent doubly linked order. Used by the concurrency tests.
     */
    void checkInvariants() {
        lock.lock();
        try {
            int count = 0;
            Node<K, V> prev = head;
            for (Node<K, V> n = head.next; n != tail; n = n.next) {
                if (n.prev != prev) {
                    throw new IllegalStateException("broken back-link at " + n.key);
                }
                if (map.get(n.key) != n) {
                    throw new IllegalStateException("list node not in map: " + n.key);
                }
                prev = n;
                if (++count > map.size()) {
                    throw new IllegalStateException("list longer than map (cycle?)");
                }
            }
            if (tail.prev != prev) {
                throw new IllegalStateException("tail back-link broken");
            }
            if (count != map.size()) {
                throw new IllegalStateException("list has " + count + " nodes but map has " + map.size());
            }
            if (count > capacity) {
                throw new IllegalStateException("size " + count + " exceeds capacity " + capacity);
            }
        } finally {
            lock.unlock();
        }
    }

    private static void requireValidCapacity(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1 but was " + capacity);
        }
    }
}
