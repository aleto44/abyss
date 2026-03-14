package com.example.abyss.world;

import com.example.abyss.WorldConstants;
import com.example.abyss.world.meshing.MeshCoordinator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Manages the load/unload lifecycle for chunks around the player.
 * <p>
 * Supports both synchronous (init/testing) and asynchronous (per-frame)
 * loading. Delegates async completion notification to the
 * {@link MeshCoordinator} so that boundary meshes are invalidated whenever
 * new chunks become available.
 * </p>
 */
public class ChunkLoader {

    private static final int NO_CHUNK_COORD = Integer.MIN_VALUE;

    private final ChunkManager chunkManager;
    private final MeshCoordinator meshCoordinator;

    /**
     * The chunk-space position used in the last {@link #loadChunksAroundAsync} sweep.
     * Chunk requests are only re-submitted when the player crosses a chunk boundary,
     * preventing per-frame O(volume) submission spam.
     */
    private int lastLoadPcx = NO_CHUNK_COORD;
    private int lastLoadPcy = NO_CHUNK_COORD;
    private int lastLoadPcz = NO_CHUNK_COORD;

    public ChunkLoader(ChunkManager chunkManager, MeshCoordinator meshCoordinator) {
        this.chunkManager = chunkManager;
        this.meshCoordinator = meshCoordinator;
    }

    // ----------------------------------------------------------------
    // Async loading
    // ----------------------------------------------------------------

    /**
     * Queues async generation for all chunks within the given radii of the world
     * position. Only re-submits the full volume when the player has crossed into a
     * new chunk since the last call, preventing per-frame O(volume) submission spam.
     * Non-blocking — chunks arrive over subsequent frames via {@link #pollCompletedChunks()}.
     */
    public void loadChunksAroundAsync(int worldX, int worldY, int worldZ,
                                      int hRadius, int vRadius) {
        int pcx = Math.floorDiv(worldX, WorldConstants.CHUNK_SIZE);
        int pcy = Math.floorDiv(worldY, WorldConstants.CHUNK_SIZE);
        int pcz = Math.floorDiv(worldZ, WorldConstants.CHUNK_SIZE);

        boolean playerChunkChanged = pcx != lastLoadPcx || pcy != lastLoadPcy || pcz != lastLoadPcz;
        if (!playerChunkChanged) {
            return;
        }

        lastLoadPcx = pcx;
        lastLoadPcy = pcy;
        lastLoadPcz = pcz;

        for (int dy = -vRadius; dy <= vRadius; dy++) {
            for (int dz = -hRadius; dz <= hRadius; dz++) {
                for (int dx = -hRadius; dx <= hRadius; dx++) {
                    chunkManager.requestChunkAsync(pcx + dx, pcy + dy, pcz + dz);
                }
            }
        }
    }

    /**
     * Drains completed async chunk results. Should be called once per frame.
     * Triggers boundary remeshing for each newly-loaded chunk.
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
            meshCoordinator.queueDirtyMeshes();
        }
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
        meshCoordinator.pollCompletedMeshes();
    }

    // ----------------------------------------------------------------
    // Unloading
    // ----------------------------------------------------------------

    /**
     * Unloads any loaded chunk whose chunk-space distance from the given world
     * position exceeds the specified radii, and cancels any pending (in-flight)
     * generation tasks that are also out of range. Call periodically as the
     * player moves to keep memory bounded.
     */
    public void unloadDistantChunks(int worldX, int worldY, int worldZ,
                                    int hRadius, int vRadius) {
        int pcx = Math.floorDiv(worldX, WorldConstants.CHUNK_SIZE);
        int pcy = Math.floorDiv(worldY, WorldConstants.CHUNK_SIZE);
        int pcz = Math.floorDiv(worldZ, WorldConstants.CHUNK_SIZE);

        // Unload already-loaded chunks that are out of range.
        List<ChunkKey> toRemove = new ArrayList<>();
        for (VoxelChunk chunk : chunkManager.getLoadedChunks()) {
            int dx = Math.abs(chunk.getChunkX() - pcx);
            int dy = Math.abs(chunk.getChunkY() - pcy);
            int dz = Math.abs(chunk.getChunkZ() - pcz);
            boolean isOutOfRange = dx > hRadius || dz > hRadius || dy > vRadius;
            if (isOutOfRange) {
                toRemove.add(new ChunkKey(chunk.getChunkX(), chunk.getChunkY(), chunk.getChunkZ()));
            }
        }
        for (ChunkKey key : toRemove) {
            chunkManager.unloadChunk(key.cx(), key.cy(), key.cz());
        }

        // Cancel pending generation tasks that are also out of range, so worker
        // threads don't waste CPU and heap on chunks that will be immediately
        // discarded once they complete.
        Collection<ChunkKey> pendingKeys = chunkManager.getPendingChunkKeys();
        for (ChunkKey key : pendingKeys) {
            int dx = Math.abs(key.cx() - pcx);
            int dy = Math.abs(key.cy() - pcy);
            int dz = Math.abs(key.cz() - pcz);
            boolean isOutOfRange = dx > hRadius || dz > hRadius || dy > vRadius;
            if (isOutOfRange) {
                chunkManager.cancelPendingChunk(key.cx(), key.cy(), key.cz());
            }
        }
    }
}