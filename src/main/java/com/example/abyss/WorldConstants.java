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

    /** Horizontal chunk radius streamed around the observer by {@code NearChunkStreamer}. */
    public static final int NEAR_FIELD_H_RADIUS = 5;

    /** Vertical chunk radius streamed around the observer by {@code NearChunkStreamer}. */
    public static final int NEAR_FIELD_V_RADIUS = 4;

    /** Horizontal band radius in region-space units for LOD streaming (XZ axes). */
    public static final int DEFAULT_BAND_H_RADIUS = 10;

    /** Vertical band radius in region-space units for LOD streaming (Y axis). */
    public static final int DEFAULT_BAND_V_RADIUS = 7;

    private WorldConstants() {
        // utility class – no instances
    }
}