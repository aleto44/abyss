package com.example.abyss.world;

import com.example.abyss.WorldConstants;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Handles world-to-chunk coordinate translation and delegates voxel
 * read/write operations to the {@link ChunkManager}.
 * <p>
 * All methods accept world-space coordinates and internally compute the
 * containing chunk and local offset.
 * </p>
 */
public class WorldVoxelAccess {

    private final ChunkManager chunkManager;

    /**
     * Listener invoked after {@link #setVoxelAt} mutates a chunk, so that
     * the mesh layer can invalidate the affected chunk and its neighbors.
     */
    public interface EditListener {
        void onVoxelEdited(int cx, int cy, int cz, int lx, int ly, int lz);
    }

    private EditListener editListener;

    public WorldVoxelAccess(ChunkManager chunkManager) {
        this.chunkManager = chunkManager;
    }

    /** Optional callback fired after every voxel mutation. */
    public void setEditListener(EditListener editListener) {
        this.editListener = editListener;
    }

    // ----------------------------------------------------------------
    // Coordinate helpers
    // ----------------------------------------------------------------

    /** Converts a single world-space axis value to chunk-space. */
    private static int toChunkCoord(int world) {
        return Math.floorDiv(world, WorldConstants.CHUNK_SIZE);
    }

    /** Converts a single world-space axis value to its local offset within a chunk. */
    private static int toLocalCoord(int world) {
        return Math.floorMod(world, WorldConstants.CHUNK_SIZE);
    }

    // ----------------------------------------------------------------
    // Synchronous reads (generate chunk if needed)
    // ----------------------------------------------------------------

    /**
     * Returns the {@link VoxelType} at the given world coordinates,
     * generating the containing chunk synchronously if necessary.
     */
    public VoxelType getVoxelAt(int worldX, int worldY, int worldZ) {
        int cx = toChunkCoord(worldX), cy = toChunkCoord(worldY), cz = toChunkCoord(worldZ);
        VoxelChunk chunk = chunkManager.getOrGenerateChunk(cx, cy, cz);
        return chunk.getVoxel(toLocalCoord(worldX), toLocalCoord(worldY), toLocalCoord(worldZ));
    }

    /**
     * Returns the continuous density at the given world coordinates,
     * generating the containing chunk synchronously if necessary.
     */
    public float getDensityAt(int worldX, int worldY, int worldZ) {
        int cx = toChunkCoord(worldX), cy = toChunkCoord(worldY), cz = toChunkCoord(worldZ);
        VoxelChunk chunk = chunkManager.getOrGenerateChunk(cx, cy, cz);
        return chunk.getDensity(toLocalCoord(worldX), toLocalCoord(worldY), toLocalCoord(worldZ));
    }

    // ----------------------------------------------------------------
    // Non-blocking reads (queue async generation if not loaded)
    // ----------------------------------------------------------------

    /**
     * Returns the {@link VoxelType} without blocking. If the chunk is not
     * loaded, queues it for async generation and returns {@code null}.
     */
    public VoxelType tryGetVoxelAt(int worldX, int worldY, int worldZ) {
        int cx = toChunkCoord(worldX), cy = toChunkCoord(worldY), cz = toChunkCoord(worldZ);
        VoxelChunk chunk = chunkManager.getChunk(cx, cy, cz);
        if (chunk == null) {
            chunkManager.requestChunkAsync(cx, cy, cz);
            return null;
        }
        return chunk.getVoxel(toLocalCoord(worldX), toLocalCoord(worldY), toLocalCoord(worldZ));
    }

    /**
     * Returns the density without blocking. If the chunk is not loaded,
     * queues it for async generation and returns {@link Float#NaN}.
     */
    public float tryGetDensityAt(int worldX, int worldY, int worldZ) {
        int cx = toChunkCoord(worldX), cy = toChunkCoord(worldY), cz = toChunkCoord(worldZ);
        VoxelChunk chunk = chunkManager.getChunk(cx, cy, cz);
        if (chunk == null) {
            chunkManager.requestChunkAsync(cx, cy, cz);
            return Float.NaN;
        }
        return chunk.getDensity(toLocalCoord(worldX), toLocalCoord(worldY), toLocalCoord(worldZ));
    }

    // ----------------------------------------------------------------
    // Mutation
    // ----------------------------------------------------------------

    /**
     * Sets a voxel at the given world coordinates. Marks the chunk dirty
     * and fires the {@link EditListener} so the mesh layer can schedule
     * remeshing for the affected chunk and its face-neighbors.
     */
    public void setVoxelAt(int worldX, int worldY, int worldZ, VoxelType type) {
        int cx = toChunkCoord(worldX), cy = toChunkCoord(worldY), cz = toChunkCoord(worldZ);
        int lx = toLocalCoord(worldX), ly = toLocalCoord(worldY), lz = toLocalCoord(worldZ);
        VoxelChunk chunk = chunkManager.getOrGenerateChunk(cx, cy, cz);
        chunk.setVoxel(lx, ly, lz, type);
        chunk.setDensity(lx, ly, lz, type.isSolid() ? 1f : -1f);

        if (editListener != null) {
            editListener.onVoxelEdited(cx, cy, cz, lx, ly, lz);
        }
    }
}