package com.example.abyss.world.physics;

import com.example.abyss.world.AbyssWorld;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Handles voxel collapse mechanics: when a supporting voxel is removed,
 * unsupported solid voxels above it fall until they land on something solid
 * or reach air.
 * <p>
 * This is a simplified gravity-column approach suitable for the initial
 * implementation. A full structural-integrity system (e.g., flood-fill
 * connectivity checks) can replace this later.
 * </p>
 */
public class CollapseSimulator {

    private final AbyssWorld world;

    /** Maximum number of voxels to cascade downward per trigger. */
    private static final int MAX_FALL_DISTANCE = 64;

    public CollapseSimulator(AbyssWorld world) {
        this.world = world;
    }

    /**
     * Called after a voxel at ({@code x, y, z}) is removed (mined).
     * Checks every voxel directly above the removed position and drops
     * any unsupported solid voxels downward.
     *
     * @param x world X of the removed voxel
     * @param y world Y of the removed voxel
     * @param z world Z of the removed voxel
     */
    public void onVoxelRemoved(int x, int y, int z) {
        // Walk upward from the removed voxel
        for (int checkY = y + 1; checkY <= y + MAX_FALL_DISTANCE; checkY++) {
            VoxelType above = world.getVoxelAt(x, checkY, z);
            if (!above.isSolid()) break; // gap reached, nothing more to fall

            // This solid voxel has lost its support – find where it lands
            int landY = findLandingY(x, y, z, checkY);
            if (landY == checkY) continue; // already supported

            // Move the voxel down
            world.setVoxelAt(x, checkY, z, VoxelType.AIR);
            world.setVoxelAt(x, landY, z, above);
        }
    }

    /**
     * Scans downward from {@code fromY - 1} to find the first solid surface
     * for a falling voxel to land on.
     *
     * @return the Y coordinate where the voxel should come to rest
     */
    private int findLandingY(int x, int removedY, int z, int fromY) {
        for (int scanY = fromY - 1; scanY >= removedY - MAX_FALL_DISTANCE; scanY--) {
            VoxelType below = world.getVoxelAt(x, scanY, z);
            if (below.isSolid()) {
                return scanY + 1; // land on top of the solid voxel
            }
        }
        // Fell all the way down – place at the bottom of the scan range
        return fromY - MAX_FALL_DISTANCE;
    }
}
