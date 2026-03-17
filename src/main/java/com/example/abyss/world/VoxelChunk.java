package com.example.abyss.world;

import com.example.abyss.WorldConstants;
import com.example.abyss.world.voxel.VoxelData;
import com.example.abyss.world.voxel.VoxelType;

/**
 * A single 32×32×32 chunk in the Abyss world.
 * Stores chunk coordinates (in chunk-space) and the underlying {@link VoxelData}.
 */
public class VoxelChunk {

    public static final int SIZE = WorldConstants.CHUNK_SIZE;

    private final int cx, cy, cz;
    private final VoxelData data;
    private boolean dirty; // true when the player has modified this chunk

    /**
     * Face-connectivity bitmask computed by {@link ChunkFaceConnectivity} during meshing.
     * Defaults to fully-open so this chunk is treated as passable by the visibility BFS
     * until its mesh (and the connectivity scan) has been built.
     */
    private volatile short faceConnectivity = ChunkFaceConnectivity.FULLY_OPEN;

    public VoxelChunk(int cx, int cy, int cz, VoxelData data) {
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.data = data;
        this.dirty = false;
    }

    // ---- world ↔ local coordinate helpers ----

    /** World X of the chunk origin. */
    public int getWorldX() { return cx * SIZE; }
    /** World Y of the chunk origin. */
    public int getWorldY() { return cy * SIZE; }
    /** World Z of the chunk origin. */
    public int getWorldZ() { return cz * SIZE; }

    public int getChunkX() { return cx; }
    public int getChunkY() { return cy; }
    public int getChunkZ() { return cz; }

    // ---- voxel access (local coords 0..31) ----

    public VoxelType getVoxel(int x, int y, int z) {
        return data.getType(x, y, z);
    }

    public void setVoxel(int x, int y, int z, VoxelType type) {
        data.setType(x, y, z, type);
        dirty = true;
    }

    public float getDensity(int x, int y, int z) {
        return data.getDensity(x, y, z);
    }

    public void setDensity(int x, int y, int z, float density) {
        data.setDensity(x, y, z, density);
        dirty = true;
    }

    // ---- raw data ----

    public VoxelData getData() { return data; }

    public boolean isDirty() { return dirty; }

    public void setDirty(boolean dirty) { this.dirty = dirty; }

    public short getFaceConnectivity() { return faceConnectivity; }

    public void setFaceConnectivity(short faceConnectivity) { this.faceConnectivity = faceConnectivity; }
}