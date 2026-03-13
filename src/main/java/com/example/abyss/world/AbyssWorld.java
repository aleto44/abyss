package com.example.abyss.world;

import com.badlogic.gdx.graphics.Mesh;
import com.example.abyss.world.voxel.VoxelType;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level façade for the Abyss world.
 * <p>
 * Owns the {@link ChunkManager}, and
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
     * Returns the {@link VoxelType} at the given world coordinates without
     * blocking.  If the chunk is not yet loaded, queues it for async generation
     * and returns {@code null}.  Call {@link #pollCompletedChunks()} each frame
     * to drain results.
     */
    public VoxelType tryGetVoxelAt(int worldX, int worldY, int worldZ) {
        int cx = Math.floorDiv(worldX, CHUNK_SIZE);
        int cy = Math.floorDiv(worldY, CHUNK_SIZE);
        int cz = Math.floorDiv(worldZ, CHUNK_SIZE);
        VoxelChunk chunk = chunkManager.getChunk(cx, cy, cz);
        if (chunk == null) {
            chunkManager.requestChunkAsync(cx, cy, cz);
            return null;
        }
        int lx = Math.floorMod(worldX, CHUNK_SIZE);
        int ly = Math.floorMod(worldY, CHUNK_SIZE);
        int lz = Math.floorMod(worldZ, CHUNK_SIZE);
        return chunk.getVoxel(lx, ly, lz);
    }

    /**
     * Returns the continuous density value at the given world coordinates.
     * Needed by the mesher.  Generates chunk synchronously if not yet loaded.
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
     * Returns the continuous density at the given world coordinates without
     * blocking.  If the chunk is not yet loaded, queues it for async generation
     * and returns {@link Float#NaN}.  Call {@link #pollCompletedChunks()} each
     * frame to drain results.
     */
    public float tryGetDensityAt(int worldX, int worldY, int worldZ) {
        int cx = Math.floorDiv(worldX, CHUNK_SIZE);
        int cy = Math.floorDiv(worldY, CHUNK_SIZE);
        int cz = Math.floorDiv(worldZ, CHUNK_SIZE);
        VoxelChunk chunk = chunkManager.getChunk(cx, cy, cz);
        if (chunk == null) {
            chunkManager.requestChunkAsync(cx, cy, cz);
            return Float.NaN;
        }
        int lx = Math.floorMod(worldX, CHUNK_SIZE);
        int ly = Math.floorMod(worldY, CHUNK_SIZE);
        int lz = Math.floorMod(worldZ, CHUNK_SIZE);
        return chunk.getDensity(lx, ly, lz);
    }

    /**
     * Sets a voxel at the given world coordinates (mining / building).
     * Marks the containing chunk as dirty for future persistence and remeshing.
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
        chunk.setDensity(lx, ly, lz, type.isSolid() ? 1f : -1f);

        invalidateMeshForEdit(cx, cy, cz, lx, ly, lz);
    }

    /**
     * Queues async generation for all chunks within the given radii of
     * the world position.  Non-blocking — chunks arrive over subsequent
     * frames via {@link #pollCompletedChunks()}.
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
        queueDirtyMeshes();
        pollCompletedMeshes();
    }

    /**
     * Drains completed async chunk results. Call once per frame.
     *
     * @return chunks that just became available this tick
     */
    public List<VoxelChunk> pollCompletedChunks() {
        List<VoxelChunk> chunks = chunkManager.pollCompletedChunks();
        if (!chunks.isEmpty()) {
            for (VoxelChunk chunk : chunks) {
                markNeighborsForBoundaryRemesh(chunk.getChunkX(), chunk.getChunkY(), chunk.getChunkZ());
            }
            queueDirtyMeshes();
        }
        return chunks;
    }

    public void queueDirtyMeshes() {
        List<ChunkKey> keys = new ArrayList<>();
        for (LoadedChunk loadedChunk : chunkManager.getLoadedChunkEntries()) {
            if (loadedChunk.renderState().isMeshDirty() && !loadedChunk.renderState().isMeshQueued()) {
                VoxelChunk chunk = loadedChunk.chunk();
                keys.add(new ChunkKey(chunk.getChunkX(), chunk.getChunkY(), chunk.getChunkZ()));
            }
        }
        for (ChunkKey key : keys) {
            chunkManager.requestChunkMesh(key);
        }
    }

    public void pollCompletedMeshes() {
        chunkManager.pollCompletedMeshes();
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

        List<ChunkKey> toRemove = new ArrayList<>();
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

    public Iterable<LoadedChunk> getRenderableChunks() {
        return chunkManager.getLoadedChunkEntries();
    }

    public Iterable<Mesh> getOpaqueMeshes() {
        List<Mesh> meshes = new ArrayList<>();
        for (LoadedChunk loadedChunk : chunkManager.getLoadedChunkEntries()) {
            meshes.addAll(loadedChunk.renderState().getOpaqueMeshes());
        }
        return meshes;
    }

    public Iterable<Mesh> getTransparentMeshes() {
        List<Mesh> meshes = new ArrayList<>();
        for (LoadedChunk loadedChunk : chunkManager.getLoadedChunkEntries()) {
            meshes.addAll(loadedChunk.renderState().getTransparentMeshes());
        }
        return meshes;
    }

    private void invalidateMeshForEdit(int cx, int cy, int cz, int lx, int ly, int lz) {
        chunkManager.markChunkMeshDirty(new ChunkKey(cx, cy, cz));
        if (lx == 0) {
            chunkManager.markChunkMeshDirty(new ChunkKey(cx - 1, cy, cz));
        }
        if (ly == 0) {
            chunkManager.markChunkMeshDirty(new ChunkKey(cx, cy - 1, cz));
        }
        if (lz == 0) {
            chunkManager.markChunkMeshDirty(new ChunkKey(cx, cy, cz - 1));
        }
        if (lx == CHUNK_SIZE - 1) {
            chunkManager.markChunkMeshDirty(new ChunkKey(cx + 1, cy, cz));
        }
        if (ly == CHUNK_SIZE - 1) {
            chunkManager.markChunkMeshDirty(new ChunkKey(cx, cy + 1, cz));
        }
        if (lz == CHUNK_SIZE - 1) {
            chunkManager.markChunkMeshDirty(new ChunkKey(cx, cy, cz + 1));
        }
    }

    private void markNeighborsForBoundaryRemesh(int cx, int cy, int cz) {
        chunkManager.markChunkMeshDirty(new ChunkKey(cx, cy, cz));
        chunkManager.markChunkMeshDirty(new ChunkKey(cx - 1, cy, cz));
        chunkManager.markChunkMeshDirty(new ChunkKey(cx, cy - 1, cz));
        chunkManager.markChunkMeshDirty(new ChunkKey(cx, cy, cz - 1));
    }

    public long getSeed() { return seed; }
    public ChunkManager getChunkManager() { return chunkManager; }

    /** Shuts down background generation threads and releases resources. */
    public void dispose() {
        if (chunkManager != null) {
            chunkManager.shutdown();
        }
    }
}