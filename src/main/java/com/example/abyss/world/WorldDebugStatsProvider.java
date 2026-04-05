package com.example.abyss.world;

import com.example.abyss.world.meshing.ChunkMeshState;
import com.example.abyss.world.meshing.MeshData;
import com.example.abyss.world.meshing.MeshSectionData;
import com.example.abyss.world.render.distant.DistantTerrainManager;
import com.example.abyss.world.render.distant.DistantTerrainStreamer;
import com.example.abyss.world.voxel.VoxelData;

import java.util.List;

/**
 * Builds a debug snapshot for the near-field world pipeline.
 */
public class WorldDebugStatsProvider {

    private static final long BYTES_PER_FLOAT = 4L;
    private static final long BYTES_PER_REFERENCE = 8L;
    private static final long BYTES_PER_SHORT = 2L;

    private final ChunkManager chunkManager;
    private final DistantTerrainManager distantTerrainManager;
    private final DistantTerrainStreamer distantTerrainStreamer;

    public WorldDebugStatsProvider(ChunkManager chunkManager,
                                   DistantTerrainManager distantTerrainManager,
                                   DistantTerrainStreamer distantTerrainStreamer) {
        this.chunkManager           = chunkManager;
        this.distantTerrainManager  = distantTerrainManager;
        this.distantTerrainStreamer  = distantTerrainStreamer;
    }

    public WorldDebugStats snapshot() {
        int loadedChunkCount = chunkManager.getLoadedCount();
        int pendingChunkCount = chunkManager.getPendingCount();
        int pendingMeshCount = chunkManager.getPendingMeshCount();
        long approximateLoadedVoxelMemoryBytes = estimateLoadedVoxelMemoryBytes();
        long approximatePendingMeshMemoryBytes = estimatePendingMeshMemoryBytes();
        return new WorldDebugStats(
                loadedChunkCount,
                pendingChunkCount,
                pendingMeshCount,
                approximateLoadedVoxelMemoryBytes,
                approximatePendingMeshMemoryBytes,
                distantTerrainManager.getDebugStats(distantTerrainStreamer.getEvictionCount())
        );
    }

    private long estimateLoadedVoxelMemoryBytes() {
        long totalBytes = 0L;
        for (LoadedChunk loadedChunk : chunkManager.iterateLoadedChunks()) {
            VoxelData voxelData = loadedChunk.chunk().getData();
            totalBytes += (long) voxelData.getDensityField().length * BYTES_PER_FLOAT;
            totalBytes += (long) voxelData.getTypeField().length * BYTES_PER_REFERENCE;
        }
        return totalBytes;
    }

    private long estimatePendingMeshMemoryBytes() {
        long totalBytes = 0L;
        for (LoadedChunk loadedChunk : chunkManager.iterateLoadedChunks()) {
            ChunkMeshState meshState = loadedChunk.renderState();
            MeshData pendingMeshData = meshState.getPendingMeshData();
            boolean hasPendingMeshData = pendingMeshData != null;
            if (hasPendingMeshData) {
                totalBytes += estimateMeshDataBytes(pendingMeshData);
            }
        }
        return totalBytes;
    }

    private long estimateMeshDataBytes(MeshData meshData) {
        long opaqueBytes = estimateMeshSectionBytes(meshData.opaqueSections());
        long transparentBytes = estimateMeshSectionBytes(meshData.transparentSections());
        return opaqueBytes + transparentBytes;
    }

    private long estimateMeshSectionBytes(List<MeshSectionData> sections) {
        long totalBytes = 0L;
        for (MeshSectionData section : sections) {
            totalBytes += (long) section.vertices().length * BYTES_PER_FLOAT;
            totalBytes += (long) section.indices().length * BYTES_PER_SHORT;
        }
        return totalBytes;
    }
}