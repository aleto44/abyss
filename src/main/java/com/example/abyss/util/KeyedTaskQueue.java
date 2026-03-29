package com.example.abyss.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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

    /**
     * Holds both the future and the queued task reference together so
     * {@link #reprioritise} can attempt to remove the task from the pool queue.
     */
    private record PendingEntry<V>(CompletableFuture<V> future, PrioritizedTask<V> task) {}

    /** Tasks currently in flight. */
    private final Map<K, PendingEntry<V>> pending = new ConcurrentHashMap<>();

    /** Keys that have been submitted or already completed — prevents duplicates. */
    private final Set<K> knownKeys = ConcurrentHashMap.newKeySet();

    /** The thread pool that executes tasks in priority order. */
    private final ThreadPoolExecutor pool;

    private KeyedTaskQueue(Builder<K, V> builder) {
        int threadCount = resolveThreadCount(builder);
        String threadNamePrefix = builder.threadNamePrefix;
        this.pool = new ThreadPoolExecutor(
                threadCount, threadCount,
                0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, threadNamePrefix);
                    thread.setDaemon(true);
                    return thread;
                }
        );
    }

    private int resolveThreadCount(Builder<K, V> builder) {
        if (builder.threadCount > 0) {
            return builder.threadCount;
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    }

    /**
     * A task wrapper that carries a priority score so the backing
     * {@link PriorityBlockingQueue} executes lower-numbered (closer) tasks first.
     */
    private static final class PrioritizedTask<V> implements Runnable, Comparable<PrioritizedTask<V>> {

        private final int priority;
        private final Supplier<V> work;
        private final CompletableFuture<V> future;

        PrioritizedTask(int priority, Supplier<V> work, CompletableFuture<V> future) {
            this.priority = priority;
            this.work     = work;
            this.future   = future;
        }

        @Override
        public void run() {
            if (future.isCancelled()) {
                return;
            }
            try {
                future.complete(work.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }

        @Override
        public int compareTo(PrioritizedTask<V> other) {
            return Integer.compare(this.priority, other.priority);
        }
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
     * Submits work for the given key at the lowest priority.
     * No-op if the key has already been submitted or completed.
     *
     * @return {@code true} if a new generation task was submitted; {@code false} if already known
     */
    public boolean submit(K key, Supplier<V> work) {
        return submit(key, work, Integer.MAX_VALUE);
    }

    /**
     * Submits work for the given key at the specified priority.
     * Lower values execute first. No-op if already known.
     *
     * @param priority execution order — lower = sooner (0 is closest/highest priority)
     * @return {@code true} if a new generation task was submitted; {@code false} if already known
     */
    public boolean submit(K key, Supplier<V> work, int priority) {
        if (!knownKeys.add(key)) {
            return false;
        }
        CompletableFuture<V> future = new CompletableFuture<>();
        PendingEntry<V> entry = new PendingEntry<>(future, new PrioritizedTask<>(priority, work, future));
        pending.put(key, entry);
        pool.execute(entry.task());
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
            Map.Entry<K, PendingEntry<V>> entry = iterator.next();
            tryCollectCompletedResult(entry, iterator, results);
        }
        return results;
    }

    private void tryCollectCompletedResult(Map.Entry<K, PendingEntry<V>> entry,
                                           java.util.Iterator<Map.Entry<K, PendingEntry<V>>> iterator,
                                           List<Result<K, V>> results) {
        CompletableFuture<V> future = entry.getValue().future;
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
        PendingEntry<V> entry = pending.remove(key);
        if (entry == null) {
            return null;
        }
        return joinPendingFuture(key, entry.future());
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
        PendingEntry<V> entry = pending.remove(key);
        if (entry != null) {
            entry.future().cancel(false);
        }
    }

    /**
     * Attempts to raise the priority of a pending task that has not yet started.
     * <p>
     * If the task is still sitting in the queue, it is removed and resubmitted with
     * the new priority so it moves ahead of lower-priority work. If the task has
     * already been picked up by a worker thread, this is a no-op — the work is
     * already in progress and cannot be reordered.
     * </p>
     * Has no effect if the key is not known or if {@code newPriority} is not
     * lower than the current priority.
     *
     * @param newPriority the new priority — must be lower (higher urgency) than the existing one to have any effect
     */
    public void reprioritise(K key, Supplier<V> work, int newPriority) {
        PendingEntry<V> existing = pending.get(key);
        if (existing == null) {
            return;
        }
        boolean isStillQueued = existing.task().priority > newPriority
                && pool.remove(existing.task());
        if (!isStillQueued) {
            return;
        }
        PrioritizedTask<V> newTask = new PrioritizedTask<>(newPriority, work, existing.future());
        pending.put(key, new PendingEntry<>(existing.future(), newTask));
        pool.execute(newTask);
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