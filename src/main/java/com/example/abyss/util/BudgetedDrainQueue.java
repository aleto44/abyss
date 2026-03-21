package com.example.abyss.util;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * A thread-safe queue that drains items on the calling thread up to a
 * nanosecond time budget per call.
 * <p>
 * Items are enqueued from any thread via {@link #enqueue(Object)} and consumed
 * on the calling thread via {@link #drain(long, Consumer)}.  The budget is
 * passed per-call so that multiple queues can share a single externally
 * computed deadline — for example, an upload queue and a promotion queue both
 * respecting the same frame deadline.
 * </p>
 *
 * <pre>{@code
 * BudgetedDrainQueue<Mesh> disposeQueue = new BudgetedDrainQueue<>();
 *
 * // Any thread — enqueue work
 * disposeQueue.enqueue(oldMesh);
 *
 * // Main thread — drain up to 2 ms worth of disposals
 * disposeQueue.drain(2_000_000L, Mesh::dispose);
 *
 * // On shutdown — drain everything regardless of time
 * disposeQueue.drainAll(Mesh::dispose);
 * }</pre>
 *
 * @param <T> the type of item held in the queue
 */
public class BudgetedDrainQueue<T> {

    private final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<>();

    public BudgetedDrainQueue() {
    }

    /** Adds {@code item} to the queue. Safe to call from any thread. */
    public void enqueue(T item) {
        queue.add(item);
    }

    /**
     * Drains items from the queue, applying {@code action} to each, until
     * {@code budgetNanos} of wall-clock time has elapsed or the queue is empty.
     *
     * @param budgetNanos nanoseconds available for this drain call
     * @param action      applied to each item as it is removed
     */
    public void drain(long budgetNanos, Consumer<T> action) {
        long deadlineNanos = System.nanoTime() + budgetNanos;
        while (System.nanoTime() < deadlineNanos) {
            T item = queue.poll();
            boolean queueEmpty = item == null;
            if (queueEmpty) {
                break;
            }
            action.accept(item);
        }
    }

    /**
     * Drains the entire queue, applying {@code action} to each item, with no
     * time limit.  Intended for use during shutdown.
     *
     * @param action applied to each item as it is removed
     */
    public void drainAll(Consumer<T> action) {
        T item;
        while ((item = queue.poll()) != null) {
            action.accept(item);
        }
    }
}
