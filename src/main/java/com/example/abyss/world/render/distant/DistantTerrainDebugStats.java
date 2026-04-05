package com.example.abyss.world.render.distant;

/**
 * Debug-facing snapshot of distant terrain background state.
 *
 * @param loadedRegionCount         loaded distant terrain region count
 * @param pendingMeshCount          pending mesh build count
 * @param approximateMeshMemoryBytes approximate CPU/GPU-side mesh memory in bytes
 * @param evictionCount             total regions evicted by the safety-valve since startup
 */
public record DistantTerrainDebugStats(
        int loadedRegionCount,
        int pendingMeshCount,
        long approximateMeshMemoryBytes,
        long evictionCount
) {

    /** Returns a zeroed stats snapshot, suitable as a default before any regions load. */
    public static DistantTerrainDebugStats empty() {
        return new DistantTerrainDebugStats(0, 0, 0L, 0L);
    }
}
