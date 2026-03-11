package com.example.abyss.world;

import com.example.abyss.world.gen.ProceduralGenerator;
import com.example.abyss.world.voxel.VoxelType;

import java.util.List;

/**
 * Top-level façade for the Abyss world.
 * <p>
 * Owns the {@link ProceduralGenerator} and {@link ChunkManager}, and
 * exposes world-space helpers for the rest of the game (rendering, entities,
 * physics).
 * </p>
 */
public class AbyssWorld {

    private static final int CHUNK_SIZE = VoxelChunk.SIZE; // 32

    private final long seed;
    private ChunkManager chunkManager;

    public AbyssWorld(long seed) {
        this.seed = seed;
    }

    /** Initialise generation pipeline and chunk storage. Call once. */
    public void init() {
        chunkManager = new ChunkManager(seed);
    }

    // ---- world-space voxel access ----

    /**
     * Returns the {@link VoxelType} at the given world coordinates,
     * generating the containing chunk <b>synchronously</b> if necessary.
     */
    public VoxelType getVoxelAt(int worldX, int worldY, int worldZ) {
        int cx = Math.floorDiv(worldX, CHUNK_SIZE);
        int cy = Math.floorDiv(worldY, CHUNK_SIZE);
        int cz = Math.floorDiv(worldZ, CHUNK_SIZE);
        VoxelChunk chunk = chunkManager.getOrGenerateChunk(cx, cy, cz);

        int lx = Math.floorMod(worldX, CHUNK_SIZE);
        int ly = Math.floorMod(worldY, CHUNK_SIZE);
        int lz = Math.floorMod(worldZ, CHUNK_SIZE);
        return chunk.getVoxel(lx, ly, lz);
    }

    /**
     * Returns the continuous density value at the given world coordinates.
     * Needed by the marching-cubes mesher.  Generates chunk synchronously
     * if not yet loaded.
     */
    public float getDensityAt(int worldX, int worldY, int worldZ) {
        int cx = Math.floorDiv(worldX, CHUNK_SIZE);
        int cy = Math.floorDiv(worldY, CHUNK_SIZE);
        int cz = Math.floorDiv(worldZ, CHUNK_SIZE);
        VoxelChunk chunk = chunkManager.getOrGenerateChunk(cx, cy, cz);

        int lx = Math.floorMod(worldX, CHUNK_SIZE);
        int ly = Math.floorMod(worldY, CHUNK_SIZE);
        int lz = Math.floorMod(worldZ, CHUNK_SIZE);
        return chunk.getDensity(lx, ly, lz);
    }

    /**
     * Sets a voxel at the given world coordinates (mining / building).
     * Marks the containing chunk as dirty for future persistence.
     */
    public void setVoxelAt(int worldX, int worldY, int worldZ, VoxelType type) {
        int cx = Math.floorDiv(worldX, CHUNK_SIZE);
        int cy = Math.floorDiv(worldY, CHUNK_SIZE);
        int cz = Math.floorDiv(worldZ, CHUNK_SIZE);
        VoxelChunk chunk = chunkManager.getOrGenerateChunk(cx, cy, cz);

        int lx = Math.floorMod(worldX, CHUNK_SIZE);
        int ly = Math.floorMod(worldY, CHUNK_SIZE);
        int lz = Math.floorMod(worldZ, CHUNK_SIZE);
        chunk.setVoxel(lx, ly, lz, type);

        // Also update density so the mesher reflects the change
        chunk.setDensity(lx, ly, lz, type.isSolid() ? 1f : -1f);
    }

    // ---- chunk lifecycle ----

    /**
     * Queues async generation for all chunks within the given radii of
     * the world position.  Non-blocking — chunks arrive over subsequent
     * frames via {@link #pollCompletedChunks()}.
     *
     * @param worldX  player world X
     * @param worldY  player world Y
     * @param worldZ  player world Z
     * @param hRadius horizontal chunk radius to load
     * @param vRadius vertical chunk radius to load
     */
    public void loadChunksAroundAsync(int worldX, int worldY, int worldZ,
                                       int hRadius, int vRadius) {
        int pcx = Math.floorDiv(worldX, CHUNK_SIZE);
        int pcy = Math.floorDiv(worldY, CHUNK_SIZE);
        int pcz = Math.floorDiv(worldZ, CHUNK_SIZE);

        for (int dy = -vRadius; dy <= vRadius; dy++) {
            for (int dz = -hRadius; dz <= hRadius; dz++) {
                for (int dx = -hRadius; dx <= hRadius; dx++) {
                    chunkManager.requestChunkAsync(pcx + dx, pcy + dy, pcz + dz);
                }
            }
        }
    }

    /**
     * Synchronous version — blocks until all chunks are generated.
     * Use only during initialisation or testing.
     */
    public void loadChunksAround(int worldX, int worldY, int worldZ,
                                  int hRadius, int vRadius) {
        int pcx = Math.floorDiv(worldX, CHUNK_SIZE);
        int pcy = Math.floorDiv(worldY, CHUNK_SIZE);
        int pcz = Math.floorDiv(worldZ, CHUNK_SIZE);

        for (int dy = -vRadius; dy <= vRadius; dy++) {
            for (int dz = -hRadius; dz <= hRadius; dz++) {
                for (int dx = -hRadius; dx <= hRadius; dx++) {
                    chunkManager.getOrGenerateChunk(pcx + dx, pcy + dy, pcz + dz);
                }
            }
        }
    }

    /**
     * Drains completed async chunk results.  Call once per frame.
     *
     * @return chunks that just became available this tick
     */
    public List<VoxelChunk> pollCompletedChunks() {
        return chunkManager.pollCompletedChunks();
    }

    /**
     * Unloads any chunk whose chunk-space distance from the given world
     * position exceeds the specified radii.  Call periodically as the
     * player moves to keep memory bounded.
     */
    public void unloadDistantChunks(int worldX, int worldY, int worldZ,
                                     int hRadius, int vRadius) {
        int pcx = Math.floorDiv(worldX, CHUNK_SIZE);
        int pcy = Math.floorDiv(worldY, CHUNK_SIZE);
        int pcz = Math.floorDiv(worldZ, CHUNK_SIZE);

        var toRemove = new java.util.ArrayList<ChunkKey>();
        for (VoxelChunk chunk : chunkManager.getLoadedChunks()) {
            int dx = Math.abs(chunk.getChunkX() - pcx);
            int dy = Math.abs(chunk.getChunkY() - pcy);
            int dz = Math.abs(chunk.getChunkZ() - pcz);
            if (dx > hRadius || dz > hRadius || dy > vRadius) {
                toRemove.add(new ChunkKey(chunk.getChunkX(), chunk.getChunkY(), chunk.getChunkZ()));
            }
        }
        for (ChunkKey key : toRemove) {
            chunkManager.unloadChunk(key.cx(), key.cy(), key.cz());
        }
    }

    // ---- accessors ----

    public long getSeed() { return seed; }
    public ChunkManager getChunkManager() { return chunkManager; }

    /** Shuts down background generation threads and releases resources. */
    public void dispose() {
        if (chunkManager != null) {
            chunkManager.shutdown();
        }
    }
}
