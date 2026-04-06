package com.example.abyss.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A generic, keyed asynchronous task queue backed by a {@link ThreadPoolExecutor}.
 * <p>
 * Call {@link #submit(Object, Supplier)} with a key and a unit of work.
 * Duplicate submissions for the same key are silently ignored until that key is
 * forgotten or cancelled. Completed results are collected on the main thread via
 * {@link #pollCompleted()}.
 * </p>
 * <p>
 * Use {@link Builder#executor(ThreadPoolExecutor)} to supply a shared pool (preferred),
 * or omit it to let {@link Builder#build()} create a private bounded pool sized to
 * {@link com.example.abyss.WorldConstants#BACKGROUND_THREAD_BUDGET}.
 * </p>
 *
 * @param <K> the key type used to identify and deduplicate tasks
 * @param <V> the result type produced by each task
 */
public class KeyedTaskQueue<K, V> {

    /**
     * Holds the future and submission timestamp for an in-flight task.
     */
    private record PendingEntry<V>(CompletableFuture<V> future, long submittedAtNanos) {}

    /** Tasks currently in flight (submitted but not yet drained). */
    private final Map<K, PendingEntry<V>> pending = new ConcurrentHashMap<>();

    /** The thread pool that executes tasks in priority order. */
    private final ThreadPoolExecutor pool;

    /**
     * When {@code true} the pool was provided externally and its lifecycle is
     * owned by the caller — {@link #shutdown()} is a no-op.
     */
    private final boolean externalPool;

    private KeyedTaskQueue(ThreadPoolExecutor pool, boolean externalPool) {
        this.pool = pool;
        this.externalPool = externalPool;
    }

    private static final class Task<V> implements Runnable {

        private final Supplier<V> work;
        private final CompletableFuture<V> future;

        Task(Supplier<V> work, CompletableFuture<V> future) {
            this.work   = work;
            this.future = future;
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
    }

    // ----------------------------------------------------------------
    // Builder
    // ----------------------------------------------------------------

    /** Returns a new builder for constructing a {@link KeyedTaskQueue}. */
    public static <K, V> Builder<K, V> builder() {
        return new Builder<>();
    }

    public static final class Builder<K, V> {

        private ThreadPoolExecutor executor = null;

        private Builder() {}

        /**
         * Provides an externally-owned {@link ThreadPoolExecutor}.
         * The caller retains full ownership of the pool's lifecycle —
         * {@link KeyedTaskQueue#shutdown()} will be a no-op.
         */
        public Builder<K, V> executor(ThreadPoolExecutor sharedExecutor) {
            this.executor = sharedExecutor;
            return this;
        }

        /**
         * Builds the {@link KeyedTaskQueue}. If no executor was provided, creates a
         * private daemon pool sized to
         * {@link com.example.abyss.WorldConstants#BACKGROUND_THREAD_BUDGET}.
         */
        public KeyedTaskQueue<K, V> build() {
            if (executor != null) {
                return new KeyedTaskQueue<>(executor, true);
            }
            return new KeyedTaskQueue<>(createDefaultPool(), false);
        }

        private static ThreadPoolExecutor createDefaultPool() {
            int threadCount = com.example.abyss.WorldConstants.BACKGROUND_THREAD_BUDGET;
            AtomicInteger counter = new AtomicInteger();
            return new ThreadPoolExecutor(
                    threadCount, threadCount,
                    0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "KeyedTaskQueue-" + counter.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
            );
        }
    }

    // ----------------------------------------------------------------
    // Submission
    // ----------------------------------------------------------------

    /**
     * Submits work for the given key. No-op if the key has already been submitted or completed.
     *
     * @return {@code true} if a new task was submitted; {@code false} if already known
     */
    public boolean submit(K key, Supplier<V> work) {
        CompletableFuture<V> future = new CompletableFuture<>();
        Task<V> task = new Task<>(work, future);
        boolean alreadyKnown = pending.putIfAbsent(key, new PendingEntry<>(future, System.nanoTime())) != null;
        if (alreadyKnown) {
            return false;
        }
        pool.execute(task);
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
            CompletableFuture<V> future = entry.getValue().future();
            if (!future.isDone()) {
                continue;
            }
            iterator.remove();
            if (future.isCancelled()) {
                continue;
            }
            if (future.isCompletedExceptionally()) {
                future.exceptionally(ex -> {
                    System.err.println("KeyedTaskQueue task failed for " + entry.getKey() + ": " + ex.getMessage());
                    return null;
                });
                continue;
            }
            results.add(new Result<>(entry.getKey(), future.join()));
        }
        return results;
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
     * Runs {@code work} synchronously on the calling thread, bypassing the pool.
     * Does not mark the key as known — the caller is responsible for tracking that
     * the result has been received (e.g. inserting into their own loaded map).
     */
    public V computeNow(Supplier<V> work) {
        return work.get();
    }

    // ----------------------------------------------------------------
    // Cancellation / cleanup
    // ----------------------------------------------------------------

    /** Cancels and forgets a key so it can be re-submitted. Use when unloading. */
    public void forget(K key) {
        PendingEntry<V> entry = pending.remove(key);
        if (entry != null) {
            entry.future().cancel(false);
        }
    }

    // ----------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------

    /** Number of tasks currently in flight. */
    public int pendingCount() {
        return pending.size();
    }

    /** Whether a task for this key is currently in flight (submitted but not yet drained). */
    public boolean isKnown(K key) {
        return pending.containsKey(key);
    }

    /** Returns a snapshot of all keys currently in flight (submitted but not yet drained). */
    public Set<K> getPendingKeys() {
        return Set.copyOf(pending.keySet());
    }

    /**
     * Counts tasks that have completed on a worker thread but have not yet been
     * drained by {@link #pollCompleted()}. A persistently non-zero value means
     * the caller is not draining frequently enough.
     */
    public int countDone() {
        int count = 0;
        for (PendingEntry<V> entry : pending.values()) {
            boolean isDoneNotDrained = entry.future().isDone();
            if (isDoneNotDrained) {
                count++;
            }
        }
        return count;
    }

    /**
     * Counts tasks whose future is <em>not</em> done and whose age exceeds
     * {@code thresholdNanos}. A non-zero value strongly suggests a worker thread
     * is hung or deadlocked on those tasks.
     */
    public int countStuck(long thresholdNanos) {
        long now = System.nanoTime();
        int count = 0;
        for (PendingEntry<V> entry : pending.values()) {
            boolean isStuck = !entry.future().isDone()
                    && (now - entry.submittedAtNanos()) > thresholdNanos;
            if (isStuck) {
                count++;
            }
        }
        return count;
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    /** Shuts down the thread pool immediately. No-op if the pool is externally owned. Call on game exit. */
    public void shutdown() {
        if (!externalPool) {
            pool.shutdownNow();
        }
    }
}