package com.example.abyss;

/**
 * Repo-wide constants. All distance values are in chunks (1 chunk = 32 voxels).
 */
public final class WorldConstants {

    /** Side length of a single chunk in voxels. */
    public static final int CHUNK_SIZE = 32;

    /** Horizontal radius of full-detail chunks loaded around the player, in chunks. */
    public static final int NEAR_FIELD_H_RADIUS = 8;

    /** Vertical radius of full-detail chunks loaded around the player, in chunks. */
    public static final int NEAR_FIELD_V_RADIUS = 5;

    /**
     * Total background worker thread budget for the shared worker pool.
     * Uses half of the available cores, clamped to [2, 6], so the GL/main
     * thread and the OS always have substantial headroom. Keeping this low
     * is important — chunk and LOD generation are CPU-bound and will saturate
     * the machine if given too many threads.
     *
     * @see com.example.abyss.util.SharedWorkerPool
     */
    public static final int BACKGROUND_THREAD_BUDGET =
            Math.min(6, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));

    private WorldConstants() {}
}