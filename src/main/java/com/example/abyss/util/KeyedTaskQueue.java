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
import java.util.function.Function;

/**
 * A generic, keyed asynchronous task queue backed by a fixed thread pool.
 * <p>
 * Callers submit keyed tasks via {@link #submit(Object)}.  Completed
 * results are collected on the main thread via {@link #pollCompleted()}.
 * Duplicate submissions (same key) are silently ignored.
 * </p>
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

    /** Factory that turns a key into the actual work to perform. */
    private final Function<K, V> taskFactory;

    /**
     * @param threadNamePrefix prefix for worker thread names (e.g. "ChunkGen")
     * @param threadCount      number of worker threads ({@code <= 0} defaults to cores − 1, min 1)
     * @param taskFactory      function that produces a result for a given key;
     *                         called on a worker thread
     */
    public KeyedTaskQueue(String threadNamePrefix, int threadCount, Function<K, V> taskFactory) {
        this.taskFactory = taskFactory;
        int threads = threadCount > 0
                ? threadCount
                : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, threadNamePrefix);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Convenience constructor that auto-sizes the pool to (cores − 1).
     */
    public KeyedTaskQueue(String threadNamePrefix, Function<K, V> taskFactory) {
        this(threadNamePrefix, 0, taskFactory);
    }

    // ----------------------------------------------------------------
    // Submission
    // ----------------------------------------------------------------

    /**
     * Submits a task for the given key if it has not already been submitted
     * or completed.  Non-blocking.
     *
     * @return {@code true} if a new task was enqueued
     */
    public boolean submit(K key) {
        if (!knownKeys.add(key)) return false;
        CompletableFuture<V> future = CompletableFuture.supplyAsync(
                () -> taskFactory.apply(key), pool);
        pending.put(key, future);
        return true;
    }

    // ----------------------------------------------------------------
    // Polling
    // ----------------------------------------------------------------

    /**
     * A completed result paired with its key.
     */
    public record Result<K, V>(K key, V value) {}

    /**
     * Drains all completed tasks and returns them.
     * Call once per frame on the main thread.
     *
     * @return list of results that finished since the last poll
     */
    public List<Result<K, V>> pollCompleted() {
        if (pending.isEmpty()) return Collections.emptyList();

        List<Result<K, V>> results = new ArrayList<>();
        var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            CompletableFuture<V> future = entry.getValue();
            if (future.isDone()) {
                it.remove();
                if (future.isCancelled()) {
                    knownKeys.remove(entry.getKey());
                    continue;
                }
                if (future.isCompletedExceptionally()) {
                    future.exceptionally(ex -> {
                        System.err.println("KeyedTaskQueue task failed for " + entry.getKey()
                                + ": " + ex.getMessage());
                        return null;
                    });
                    knownKeys.remove(entry.getKey());
                    continue;
                }
                results.add(new Result<>(entry.getKey(), future.join()));
            }
        }
        return results;
    }

    // ----------------------------------------------------------------
    // Synchronous fallback
    // ----------------------------------------------------------------

    /**
     * If a task for {@code key} is pending, blocks until it completes and
     * returns the result.  If no task is pending, returns {@code null}.
     * <p>
     * Does <b>not</b> submit a new task — use {@link #submit(Object)} first,
     * or call {@link #computeNow(Object)} for an immediate synchronous run.
     */
    public V awaitPending(K key) {
        CompletableFuture<V> future = pending.remove(key);
        if (future == null) return null;
        try {
            return future.join();
        } catch (CancellationException e) {
            return null;
        } catch (Exception e) {
            throw new RuntimeException("KeyedTaskQueue task failed for " + key, e);
        }
    }

    /**
     * Runs the task synchronously on the calling thread, bypassing the pool.
     * Marks the key as known so future {@link #submit(Object)} calls are ignored.
     */
    public V computeNow(K key) {
        knownKeys.add(key);
        return taskFactory.apply(key);
    }

    // ----------------------------------------------------------------
    // Cancellation / cleanup
    // ----------------------------------------------------------------

    /**
     * Cancels and forgets a key, allowing it to be re-submitted later.
     */
    public void cancel(K key) {
        knownKeys.remove(key);
        CompletableFuture<V> future = pending.remove(key);
        if (future != null) future.cancel(false);
    }

    /**
     * Removes a key from the known set without cancelling.
     * Use when unloading a previously-completed result.
     */
    public void forget(K key) {
        knownKeys.remove(key);
        CompletableFuture<V> future = pending.remove(key);
        if (future != null) future.cancel(false);
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

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    /** Shuts down the thread pool immediately. Call on game exit. */
    public void shutdown() {
        pool.shutdownNow();
    }
}
