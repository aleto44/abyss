package com.example.abyss.world.gen.features;

import com.example.abyss.lib.FastNoiseLite;
import com.example.abyss.world.gen.GenConfigResolver;
import com.example.abyss.world.gen.LayerFeatureProcessor;
import com.example.abyss.world.voxel.VoxelData;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Carves narrow ledges and cave openings into the perimeter walls
 * of the Sky Walls layer.
 * <p>
 * Uses a 2D noise field (Y × angular position) to decide where to
 * carve horizontal ledge pockets into otherwise-solid wall voxels.
 * This creates the "narrow caves and ledges branching from walls"
 * described in the spec.
 * </p>
 */
public class SkyWallsFeatureProcessor implements LayerFeatureProcessor {

    private final FastNoiseLite ledgeNoise;

    /** Noise above this threshold carves a ledge pocket. */
    private static final float LEDGE_THRESHOLD = 0.35f;
    /** Depth of ledge carve into solid terrain (voxels). */
    private static final int LEDGE_DEPTH = 4;

    public SkyWallsFeatureProcessor(long seed) {
        ledgeNoise = new FastNoiseLite((int) (seed ^ 0x534B_5957L)); // "SKYW"
        ledgeNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        ledgeNoise.SetFrequency(0.04f);
    }

    @Override
    public void process(VoxelData data, int worldBaseX, int worldBaseY, int worldBaseZ, GenConfigResolver resolver) {
        int size = VoxelData.SIZE;

        for (int ly = 0; ly < size; ly++) {
            int wy = worldBaseY + ly;
            for (int lz = 0; lz < size; lz++) {
                for (int lx = 0; lx < size; lx++) {
                    // Only carve into solid voxels
                    if (!data.getType(lx, ly, lz).isSolid()) continue;

                    // Check if this voxel is adjacent to air (wall surface)
                    if (!isAdjacentToAir(data, lx, ly, lz, size)) continue;

                    int wx = worldBaseX + lx;
                    int wz = worldBaseZ + lz;

                    // Use Y and angular position for the noise lookup
                    float angle = (float) Math.atan2(wz, wx);
                    float n = ledgeNoise.GetNoise(wy * 0.5f, angle * 100f);

                    if (n > LEDGE_THRESHOLD) {
                        // Carve a pocket inward
                        carveLedge(data, lx, ly, lz, wx, wz, size);
                    }
                }
            }
        }
    }

    private boolean isAdjacentToAir(VoxelData data, int x, int y, int z, int size) {
        if (x > 0 && data.getType(x - 1, y, z) == VoxelType.AIR) return true;
        if (x < size - 1 && data.getType(x + 1, y, z) == VoxelType.AIR) return true;
        if (y > 0 && data.getType(x, y - 1, z) == VoxelType.AIR) return true;
        if (y < size - 1 && data.getType(x, y + 1, z) == VoxelType.AIR) return true;
        if (z > 0 && data.getType(x, y, z - 1) == VoxelType.AIR) return true;
        return z < size - 1 && data.getType(x, y, z + 1) == VoxelType.AIR;
    }

    /**
     * Carves a small ledge pocket by clearing a few voxels inward
     * from the wall surface toward the abyss center.
     */
    private void carveLedge(VoxelData data, int lx, int ly, int lz,
                            int wx, int wz, int size) {
        // Direction toward center (0,0)
        float dist = (float) Math.sqrt((double) wx * wx + (double) wz * wz);
        if (dist < 1f) return;
        float dirX = -wx / dist;
        float dirZ = -wz / dist;

        for (int d = 0; d < LEDGE_DEPTH; d++) {
            int cx = lx + Math.round(dirX * d);
            int cz = lz + Math.round(dirZ * d);
            if (cx < 0 || cx >= size || cz < 0 || cz >= size) break;
            if (!data.getType(cx, ly, cz).isSolid()) break;

            data.setType(cx, ly, cz, VoxelType.AIR);
            data.setDensity(cx, ly, cz, -0.5f);
        }
    }
}