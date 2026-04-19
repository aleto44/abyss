package com.example.abyss.world.render.distant;

/**
 * Debug-facing snapshot of distant terrain background state.
 *
 * @param loadedMacroChunkCount      loaded macro-chunk count
 * @param pendingCount               pending heightmap generation tasks in the task queue
 * @param activeBandMeshCount        number of band mesh states currently registered
 * @param approximateMeshMemoryBytes approximate GPU-side mesh memory in bytes
 * @param evictionCount              total macro-chunks evicted by the safety valve since startup
 * @param stuckTaskCount             tasks in-flight longer than the stuck threshold (likely hung)
 * @param avgGenerationNanos         rolling average wall-clock time per heightmap generation task, in nanoseconds
 * @param pendingGpuUploadCount      mesh states waiting in the promotion queue for GPU upload this frame
 */
public record DistantTerrainDebugStats(
        int loadedMacroChunkCount,
        int pendingCount,
        int activeBandMeshCount,
        long approximateMeshMemoryBytes,
        long evictionCount,
        int stuckTaskCount,
        long avgGenerationNanos,
        int pendingGpuUploadCount
) {

    /** Returns a zeroed stats snapshot, suitable as a default before any macro-chunks load. */
    public static DistantTerrainDebugStats empty() {
        return new DistantTerrainDebugStats(0, 0, 0, 0L, 0L, 0, 0L, 0);
    }
}
