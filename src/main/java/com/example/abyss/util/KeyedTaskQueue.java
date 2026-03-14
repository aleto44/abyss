package com.example.abyss.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * A generic, keyed asynchronous task queue backed by a fixed thread pool.
 * <p>
 * Works like a normal work queue — call {@link #submit(Object, Supplier)} with
 * a key and a unit of work. Duplicate submissions for the same key are silently
 * ignored until that key is forgotten or cancelled. Completed results are
 * collected on the main thread via {@link #pollCompleted()}.
 * </p>
 *
 * <pre>{@code
 * // Setup (once)
 * KeyedTaskQueue<ChunkKey, VoxelChunk> queue = KeyedTaskQueue
 *     .<ChunkKey, VoxelChunk>builder()
 *     .threadNamePrefix("ChunkGen")
 *     .build();
 *
 * // Per-frame
 * queue.submit(key, () -> generateChunk(key));   // non-blocking
 * for (var result : queue.pollCompleted()) {      // drain results
 *     loaded.put(result.key(), result.value());
 * }
 * }</pre>
 *
 * @param <K> the key type used to identify and deduplicate tasks
 * @param <V> the result type produced by each task
 */
public class KeyedTaskQueue<K, V> {

    /** Tasks currently in flight. */
    private final Map<K, CompletableFuture<V>> pending = new ConcurrentHashMap<>();

    /** Keys that have been submitted or already completed — prevents duplicates. */
    private final Set<K> knownKeys = ConcurrentHashMap.newKeySet();

    /** The thread pool that executes tasks. */
    private final ExecutorService pool;

    private KeyedTaskQueue(Builder<K, V> builder) {
        this.pool = Executors.newFixedThreadPool(resolveThreadCount(builder), runnable -> {
            Thread thread = new Thread(runnable, builder.threadNamePrefix);
            thread.setDaemon(true);
            return thread;
        });
    }

    private int resolveThreadCount(Builder<K, V> builder) {
        if (builder.threadCount > 0) {
            return builder.threadCount;
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    // ----------------------------------------------------------------
    // Builder
    // ----------------------------------------------------------------

    /** Returns a new builder for constructing a {@link KeyedTaskQueue}. */
    public static <K, V> Builder<K, V> builder() {
        return new Builder<>();
    }

    public static final class Builder<K, V> {

        private String threadNamePrefix = "KeyedTaskQueue";
        private int threadCount = 0; // 0 = auto (cores - 1)

        private Builder() {
        }

        /** Prefix applied to worker thread names. Defaults to {@code "KeyedTaskQueue"}. */
        public Builder<K, V> threadNamePrefix(String prefix) {
            this.threadNamePrefix = prefix;
            return this;
        }

        /**
         * Fixed number of worker threads. Pass {@code 0} (or omit) to
         * auto-size to {@code max(1, availableProcessors − 1)}.
         */
        public Builder<K, V> threadCount(int count) {
            this.threadCount = count;
            return this;
        }

        /** Builds the {@link KeyedTaskQueue}. */
        public KeyedTaskQueue<K, V> build() {
            return new KeyedTaskQueue<>(this);
        }
    }

    // ----------------------------------------------------------------
    // Submission
    // ----------------------------------------------------------------

    /**
     * Submits work for the given key if that key has not already been submitted
     * or completed. Non-blocking.
     *
     * @param key  identifies this unit of work for deduplication purposes
     * @param work the computation to run on a worker thread
     * @return {@code true} if the work was enqueued; {@code false} if already known
     */
    public boolean submit(K key, Supplier<V> work) {
        if (!knownKeys.add(key)) {
            return false;
        }

        pending.put(key, CompletableFuture.supplyAsync(work, pool));
        return true;
    }

    // ----------------------------------------------------------------
    // Polling
    // ----------------------------------------------------------------

    /** A completed result paired with its key. */
    public record Result<K, V>(K key, V value) {
    }

    /**
     * Drains all completed tasks and returns them.
     * Call once per frame on the main thread.
     *
     * @return list of results that finished since the last poll
     */
    public List<Result<K, V>> pollCompleted() {
        if (pending.isEmpty()) {
            return Collections.emptyList();
        }

        List<Result<K, V>> results = new ArrayList<>();
        var iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<K, CompletableFuture<V>> entry = iterator.next();
            tryCollectCompletedResult(entry, iterator, results);
        }
        return results;
    }

    private void tryCollectCompletedResult(Map.Entry<K, CompletableFuture<V>> entry,
                                           java.util.Iterator<Map.Entry<K, CompletableFuture<V>>> iterator,
                                           List<Result<K, V>> results) {
        CompletableFuture<V> future = entry.getValue();
        if (!future.isDone()) {
            return;
        }

        iterator.remove();
        if (future.isCancelled()) {
            handleCancelledTask(entry.getKey());
            return;
        }
        if (future.isCompletedExceptionally()) {
            handleFailedTask(entry.getKey(), future);
            return;
        }

        results.add(new Result<>(entry.getKey(), future.join()));
    }

    private void handleCancelledTask(K key) {
        knownKeys.remove(key);
    }

    private void handleFailedTask(K key, CompletableFuture<V> future) {
        future.exceptionally(ex -> {
            System.err.println("KeyedTaskQueue task failed for " + key + ": " + ex.getMessage());
            return null;
        });
        knownKeys.remove(key);
    }

    // ----------------------------------------------------------------
    // Synchronous fallback
    // ----------------------------------------------------------------

    /**
     * If a task for {@code key} is in flight, blocks until it completes and
     * returns the result. Returns {@code null} if no task is pending for that key.
     */
    public V awaitPending(K key) {
        CompletableFuture<V> future = pending.remove(key);
        if (future == null) {
            return null;
        }
        return joinPendingFuture(key, future);
    }

    private V joinPendingFuture(K key, CompletableFuture<V> future) {
        try {
            return future.join();
        } catch (CancellationException e) {
            return null;
        } catch (Exception e) {
            throw new RuntimeException("KeyedTaskQueue task failed for " + key, e);
        }
    }

    /**
     * Runs {@code work} synchronously on the calling thread, bypassing the pool,
     * and marks the key as known so future {@link #submit} calls are deduplicated.
     */
    public V computeNow(K key, Supplier<V> work) {
        knownKeys.add(key);
        return work.get();
    }

    // ----------------------------------------------------------------
    // Cancellation / cleanup
    // ----------------------------------------------------------------

    /**
     * Forgets a completed key so it can be re-submitted.
     * Use when unloading a previously-completed result.
     */
    public void forget(K key) {
        knownKeys.remove(key);
        CompletableFuture<V> future = pending.remove(key);
        if (future != null) {
            future.cancel(false);
        }
    }

    // ----------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------

    /** Number of tasks currently in flight. */
    public int pendingCount() {
        return pending.size();
    }

    /** Whether a task for this key has been submitted (pending or completed). */
    public boolean isKnown(K key) {
        return knownKeys.contains(key);
    }

    /** Returns a snapshot of all keys currently in flight (submitted but not yet drained). */
    public Set<K> getPendingKeys() {
        return Set.copyOf(pending.keySet());
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    /** Shuts down the thread pool immediately. Call on game exit. */
    public void shutdown() {
        pool.shutdownNow();
    }
}