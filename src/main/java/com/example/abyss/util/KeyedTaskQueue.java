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
     * Holds the future, the queued task, and the original work supplier together.
     * The supplier is kept so {@link #reprioritise} can rebuild a task without
     * requiring the caller to re-provide it.
     */
    private record PendingEntry<V>(CompletableFuture<V> future, PrioritizedTask<V> task, Supplier<V> work) {}

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

        private ThreadPoolExecutor executor = null;

        private Builder() {}

        /**
         * Provides an externally-owned {@link ThreadPoolExecutor}.
         * The caller retains full ownership of the pool's lifecycle —
         * {@link KeyedTaskQueue#shutdown()} will be a no-op.
         * <p>
         * The executor's work queue must be a {@link PriorityBlockingQueue}
         * for priority ordering to function correctly.
         * </p>
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
                    new PriorityBlockingQueue<>(),
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
        CompletableFuture<V> future = new CompletableFuture<>();
        PrioritizedTask<V> task = new PrioritizedTask<>(priority, work, future);
        boolean alreadyKnown = pending.putIfAbsent(key, new PendingEntry<>(future, task, work)) != null;
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
    public void reprioritise(K key, int newPriority) {
        PendingEntry<V> existing = pending.get(key);
        if (existing == null) {
            return;
        }
        boolean isStillQueued = existing.task().priority > newPriority
                && pool.remove(existing.task());
        if (!isStillQueued) {
            return;
        }
        PrioritizedTask<V> newTask = new PrioritizedTask<>(newPriority, existing.work(), existing.future());
        pending.put(key, new PendingEntry<>(existing.future(), newTask, existing.work()));
        pool.execute(newTask);
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