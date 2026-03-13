package com.example.abyss;

/**
 * Repo-wide constants that are referenced across multiple packages.
 * <p>
 * Centralising these here avoids the same magic number being duplicated
 * in {@code VoxelData}, {@code VoxelChunk}, {@code AbyssWorld}, and the
 * meshing / loading subsystems.
 * </p>
 */
public final class WorldConstants {

    /** Side length of a single voxel chunk in all three axes (32 voxels). */
    public static final int CHUNK_SIZE = 32;

    private WorldConstants() {
        // utility class – no instances
    }
}
