package com.example.abyss.world;

import com.example.abyss.WorldConstants;
import com.example.abyss.world.meshing.MeshCoordinator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * Low-level executor for chunk load/unload operations.
 * <p>
 * Provides three primitives:
 * <ul>
 *   <li>{@link #requestChunkAsync} — submit a single chunk coordinate for background generation.</li>
 *   <li>{@link #pollCompletedChunks} — drain completed chunks and trigger boundary remeshing.</li>
 *   <li>{@link #unloadDistantChunks} — evict loaded and pending chunks beyond a given radius.</li>
 * </ul>
 * This class holds no sweep strategy, no player-position state, and no camera knowledge.
 * Callers are responsible for deciding <em>which</em> chunks to request and <em>when</em>
 * — see {@link com.example.abyss.world.render.NearChunkStreamer} for a cylinder-based policy.
 * The synchronous {@link #loadChunksAround} method exists for init and testing only.
 * </p>
 */
public class ChunkLoader {

    private final ChunkManager chunkManager;
    private final MeshCoordinator meshCoordinator;

    public ChunkLoader(ChunkManager chunkManager, MeshCoordinator meshCoordinator) {
        this.chunkManager = chunkManager;
        this.meshCoordinator = meshCoordinator;
    }

    // ----------------------------------------------------------------
    // Async loading
    // ----------------------------------------------------------------

    /**
     * Submits a single chunk coordinate for async generation.
     * No-op if the chunk is already loaded or pending.
     * Non-blocking — the result arrives via {@link #pollCompletedChunks()}.
     *
     * @return {@code true} if a new generation task was submitted; {@code false} if already known
     */
    public boolean requestChunkAsync(int chunkX, int chunkY, int chunkZ) {
        return chunkManager.requestChunkAsync(chunkX, chunkY, chunkZ);
    }

    /**
     * Drains completed async chunk results. Should be called once per frame.
     * Triggers boundary remeshing for each newly-loaded chunk, then sweeps all
     * dirty-but-unqueued chunks regardless of whether any new chunks arrived.
     * This ensures chunks that were previously blocked waiting for a neighbour
     * are retried every frame rather than only when another chunk happens to load.
     *
     * @return chunks that just became available this tick
     */
    public List<VoxelChunk> pollCompletedChunks() {
        List<VoxelChunk> chunks = chunkManager.pollCompletedChunks();
        if (!chunks.isEmpty()) {
            for (VoxelChunk chunk : chunks) {
                meshCoordinator.markNeighborsForBoundaryRemesh(
                        chunk.getChunkX(), chunk.getChunkY(), chunk.getChunkZ());
            }
        }
        // Always sweep for dirty chunks every frame so previously-blocked meshes
        // are retried as soon as their neighbours become available.
        meshCoordinator.queueDirtyMeshes();
        return chunks;
    }

    // ----------------------------------------------------------------
    // Synchronous loading (init / testing only)
    // ----------------------------------------------------------------

    /**
     * Synchronously generates all chunks within the given radii.
     * Blocks until complete — use only during initialisation or testing.
     */
    public void loadChunksAround(int worldX, int worldY, int worldZ,
                                 int hRadius, int vRadius) {
        int pcx = Math.floorDiv(worldX, WorldConstants.CHUNK_SIZE);
        int pcy = Math.floorDiv(worldY, WorldConstants.CHUNK_SIZE);
        int pcz = Math.floorDiv(worldZ, WorldConstants.CHUNK_SIZE);

        for (int dy = -vRadius; dy <= vRadius; dy++) {
            for (int dz = -hRadius; dz <= hRadius; dz++) {
                for (int dx = -hRadius; dx <= hRadius; dx++) {
                    chunkManager.getOrGenerateChunk(pcx + dx, pcy + dy, pcz + dz);
                }
            }
        }
        meshCoordinator.queueDirtyMeshes();
        meshCoordinator.pollCompletedMeshes(null);
    }

    // ----------------------------------------------------------------
    // Unloading
    // ----------------------------------------------------------------

    /**
     * Evicts every loaded chunk — and cancels every pending generation task — whose
     * key satisfies the given predicate.  Camera-awareness, frustum tests, and any
     * other policy logic belong in the predicate supplied by the caller.
     *
     * @param shouldUnload returns {@code true} for any {@link ChunkKey} that must be evicted
     */
    public UnloadStats unloadChunks(Predicate<ChunkKey> shouldUnload) {
        List<ChunkKey> toRemove = new ArrayList<>();
        for (VoxelChunk chunk : chunkManager.getLoadedChunks()) {
            ChunkKey key = new ChunkKey(chunk.getChunkX(), chunk.getChunkY(), chunk.getChunkZ());
            if (shouldUnload.test(key)) {
                toRemove.add(key);
            }
        }
        for (ChunkKey key : toRemove) {
            chunkManager.unloadChunk(key.cx(), key.cy(), key.cz());
        }

        int cancelledPendingCount = 0;
        Collection<ChunkKey> pendingKeys = chunkManager.getPendingChunkKeys();
        for (ChunkKey key : pendingKeys) {
            if (shouldUnload.test(key)) {
                chunkManager.cancelPendingChunk(key.cx(), key.cy(), key.cz());
                cancelledPendingCount++;
            }
        }
        return new UnloadStats(toRemove.size(), cancelledPendingCount);
    }

    public record UnloadStats(int unloadedChunkCount, int cancelledPendingCount) {
    }
}