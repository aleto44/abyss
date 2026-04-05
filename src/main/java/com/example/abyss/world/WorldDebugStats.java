package com.example.abyss.world;

import com.example.abyss.world.render.distant.DistantTerrainDebugStats;

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
 * @param distantTerrainStats debug-facing snapshot of distant terrain background state
 */
public record WorldDebugStats(
        int loadedChunkCount,
        int pendingChunkCount,
        int pendingMeshCount,
        long approximateLoadedVoxelMemoryBytes,
        long approximatePendingMeshMemoryBytes,
        DistantTerrainDebugStats distantTerrainStats
) {
}