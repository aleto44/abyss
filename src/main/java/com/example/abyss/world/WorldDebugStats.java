package com.example.abyss.world;

/**
 * Debug-facing snapshot of near-field world state.
 * <p>
 * Values are intentionally approximate where noted so callers can observe
 * pressure trends without taking ownership of runtime internals.
 * </p>
 *
 * @param loadedChunkCount loaded near-field chunk count
 * @param pendingChunkCount pending near-field chunk generation count
 * @param pendingMeshCount pending near-field mesh build count
 * @param approximateLoadedVoxelMemoryBytes approximate CPU memory used by loaded voxel arrays
 * @param approximatePendingMeshMemoryBytes approximate CPU memory used by pending mesh data
 */
public record WorldDebugStats(
        int loadedChunkCount,
        int pendingChunkCount,
        int pendingMeshCount,
        long approximateLoadedVoxelMemoryBytes,
        long approximatePendingMeshMemoryBytes
) {
}
