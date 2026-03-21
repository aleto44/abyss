package com.example.abyss.util;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Background watchdog that monitors a nominated "main" thread and dumps the full
 * thread state to {@link System#err} whenever that thread appears to be frozen.
 * <p>
 * Usage:
 * <pre>
 *   MainThreadWatchdog watchdog = new MainThreadWatchdog(Thread.currentThread(), 500);
 *   watchdog.start();
 *   // ... at the top of each frame:
 *   watchdog.tick();
 *   // ... on shutdown:
 *   watchdog.stop();
 * </pre>
 * Call {@link #tick()} once per frame on the watched thread.  If {@code tickIntervalMs}
 * elapses without a tick, the watchdog considers the thread frozen and emits a full
 * thread dump.  Repeated dumps are suppressed until the thread recovers and ticks again.
 * </p>
 */
public class MainThreadWatchdog {

    private static final int STACK_DEPTH = 40;

    private final Thread watchedThread;
    private final long freezeThresholdMs;
    private final AtomicLong lastTickNanos = new AtomicLong(System.nanoTime());
    private final AtomicBoolean frozen = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread watchdogThread;

    /**
     * @param watchedThread    the thread to monitor (typically {@link Thread#currentThread()} called from the game-loop thread)
     * @param freezeThresholdMs milliseconds without a {@link #tick()} before a freeze is reported
     */
    public MainThreadWatchdog(Thread watchedThread, long freezeThresholdMs) {
        this.watchedThread = watchedThread;
        this.freezeThresholdMs = freezeThresholdMs;
    }

    /** Call once per frame on the watched thread to prove it is still alive. */
    public void tick() {
        lastTickNanos.set(System.nanoTime());
        if (frozen.compareAndSet(true, false)) {
            System.err.printf("[Watchdog] Thread '%s' recovered after freeze.%n", watchedThread.getName());
        }
    }

    /** Starts the background monitoring thread. Safe to call only once. */
    public void start() {
        boolean wasRunning = running.getAndSet(true);
        if (wasRunning) {
            return;
        }
        lastTickNanos.set(System.nanoTime());
        watchdogThread = new Thread(this::watchLoop, "main-thread-watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();
    }

    /** Stops the background monitoring thread. May be called from any thread. */
    public void stop() {
        running.set(false);
        if (watchdogThread != null) {
            watchdogThread.interrupt();
        }
    }

    private void watchLoop() {
        while (running.get()) {
            try {
                Thread.sleep(freezeThresholdMs / 2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            long elapsedMs = (System.nanoTime() - lastTickNanos.get()) / 1_000_000L;
            boolean isFrozen = elapsedMs >= freezeThresholdMs;
            if (isFrozen && frozen.compareAndSet(false, true)) {
                dumpThreads(elapsedMs);
            }
        }
    }

    private void dumpThreads(long elapsedMs) {
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        System.err.printf("%n========== [Watchdog] FREEZE DETECTED ===========%n");
        System.err.printf("Thread '%s' has not ticked for %d ms (threshold: %d ms)%n",
                watchedThread.getName(), elapsedMs, freezeThresholdMs);

        // Print the watched thread's stack first, prominently.
        ThreadInfo watchedInfo = tmx.getThreadInfo(watchedThread.getId(), STACK_DEPTH);
        if (watchedInfo != null) {
            System.err.printf("%nWATCHED THREAD: %s%n", formatThreadInfo(watchedInfo));
        }

        // Then dump every other thread so inter-thread deadlocks are visible.
        System.err.printf("%n--- All threads ---%n");
        Map<Thread, StackTraceElement[]> allStacks = Thread.getAllStackTraces();
        for (Map.Entry<Thread, StackTraceElement[]> entry : allStacks.entrySet()) {
            Thread t = entry.getKey();
            if (t.getId() == watchedThread.getId()) {
                continue; // already printed above
            }
            System.err.printf("%nThread '%s' [%s] (id=%d, daemon=%b)%n",
                    t.getName(), t.getState(), t.getId(), t.isDaemon());
            for (StackTraceElement frame : entry.getValue()) {
                System.err.printf("    at %s%n", frame);
            }
        }

        // Highlight any deadlocked threads.
        long[] deadlocked = tmx.findDeadlockedThreads();
        if (deadlocked != null && deadlocked.length > 0) {
            System.err.printf("%n*** DEADLOCK DETECTED among %d threads ***%n", deadlocked.length);
            for (ThreadInfo info : tmx.getThreadInfo(deadlocked, STACK_DEPTH)) {
                System.err.println(formatThreadInfo(info));
            }
        }

        System.err.printf("=================================================%n%n");
        System.err.flush();
    }

    private static String formatThreadInfo(ThreadInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Thread '%s' [%s] (id=%d)%n",
                info.getThreadName(), info.getThreadState(), info.getThreadId()));
        if (info.getLockName() != null) {
            sb.append(String.format("  waiting on lock: %s", info.getLockName()));
            if (info.getLockOwnerName() != null) {
                sb.append(String.format(" held by '%s' (id=%d)", info.getLockOwnerName(), info.getLockOwnerId()));
            }
            sb.append('\n');
        }
        for (StackTraceElement frame : info.getStackTrace()) {
            sb.append(String.format("    at %s%n", frame));
        }
        return sb.toString();
    }
}
