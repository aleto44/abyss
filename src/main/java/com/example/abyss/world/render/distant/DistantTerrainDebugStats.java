package com.example.abyss.world.render.distant;

/**
 * Debug-facing snapshot of distant terrain background state.
 *
 * @param loadedRegionCount          loaded distant terrain region count
 * @param pendingRegionCount         pending region generation tasks in the task queue
 * @param pendingMeshCount           pending mesh build count
 * @param approximateMeshMemoryBytes approximate CPU/GPU-side mesh memory in bytes
 * @param evictionCount              total regions evicted by the safety-valve since startup
 * @param doneNotDrainedCount        tasks completed on a worker but not yet consumed by pollCompleted()
 * @param stuckTaskCount             tasks in-flight for longer than the stuck threshold (likely hung)
 */
public record DistantTerrainDebugStats(
        int loadedRegionCount,
        int pendingRegionCount,
        int pendingMeshCount,
        long approximateMeshMemoryBytes,
        long evictionCount,
        int doneNotDrainedCount,
        int stuckTaskCount
) {

    /** Returns a zeroed stats snapshot, suitable as a default before any regions load. */
    public static DistantTerrainDebugStats empty() {
        return new DistantTerrainDebugStats(0, 0, 0, 0L, 0L, 0, 0);
    }
}
