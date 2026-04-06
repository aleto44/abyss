package com.example.abyss.util;

import com.example.abyss.WorldConstants;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A single shared {@link ThreadPoolExecutor} intended to be passed into every
 * {@link KeyedTaskQueue} in the world pipeline (chunk gen, chunk meshing, LOD proxy gen).
 * <p>
 * Thread count is driven by {@link WorldConstants#BACKGROUND_THREAD_BUDGET},
 * which is clamped to {@code [4, 16]} and always leaves at least 2 cores free
 * for the GL/main thread and the OS.
 * </p>
 */
public final class SharedWorkerPool {

    /** Shared singleton — call {@link #get()} to access it. */
    private static final SharedWorkerPool INSTANCE = new SharedWorkerPool();

    private final ThreadPoolExecutor executor;

    private SharedWorkerPool() {
        AtomicInteger counter = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
                WorldConstants.BACKGROUND_THREAD_BUDGET,
                WorldConstants.BACKGROUND_THREAD_BUDGET,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "AbyssWorker-" + counter.getAndIncrement());
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                }
        );
    }

    /** Returns the process-wide shared worker pool. */
    public static SharedWorkerPool get() {
        return INSTANCE;
    }

    /** Returns the underlying executor, suitable for passing to {@link KeyedTaskQueue.Builder#executor}. */
    public ThreadPoolExecutor executor() {
        return executor;
    }

    /** Shuts down the shared pool. Call once on game exit via {@link com.example.abyss.world.AbyssWorld#dispose()}. */
    public void shutdown() {
        executor.shutdownNow();
    }
}
