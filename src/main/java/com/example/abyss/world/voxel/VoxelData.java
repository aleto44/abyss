package com.example.abyss.world.voxel;

import com.example.abyss.WorldConstants;

/**
 * Holds the raw data for a single 32×32×32 chunk:
 * <ul>
 *   <li>{@code densityField} – continuous floats consumed by the marching-cubes mesher</li>
 *   <li>{@code typeField} – discrete {@link VoxelType}s for gameplay logic (collision, hazards, etc.)</li>
 * </ul>
 * Both arrays are flat, indexed as {@code y * 1024 + z * 32 + x}.
 */
public class VoxelData {

    public static final int SIZE = WorldConstants.CHUNK_SIZE;
    private static final int VOLUME = SIZE * SIZE * SIZE; // 32768

    private final float[] densityField;
    private final VoxelType[] typeField;

    public VoxelData() {
        densityField = new float[VOLUME];
        typeField = new VoxelType[VOLUME];
        // Default every voxel to air / zero density
        for (int i = 0; i < VOLUME; i++) {
            typeField[i] = VoxelType.AIR;
        }
    }

    // ---- index helpers ----

    private static int idx(int x, int y, int z) {
        return y * (SIZE * SIZE) + z * SIZE + x;
    }

    private static boolean inBounds(int x, int y, int z) {
        return x >= 0 && x < SIZE && y >= 0 && y < SIZE && z >= 0 && z < SIZE;
    }

    // ---- density ----

    public float getDensity(int x, int y, int z) {
        if (!inBounds(x, y, z)) return 0f;
        return densityField[idx(x, y, z)];
    }

    public void setDensity(int x, int y, int z, float density) {
        if (!inBounds(x, y, z)) return;
        densityField[idx(x, y, z)] = density;
    }

    // ---- voxel type ----

    public VoxelType getType(int x, int y, int z) {
        if (!inBounds(x, y, z)) return VoxelType.AIR;
        return typeField[idx(x, y, z)];
    }

    public void setType(int x, int y, int z, VoxelType type) {
        if (!inBounds(x, y, z)) return;
        typeField[idx(x, y, z)] = type;
    }

    // ---- raw access (for bulk operations / mesher) ----

    public float[] getDensityField() {
        return densityField;
    }

    public VoxelType[] getTypeField() {
        return typeField;
    }
}
