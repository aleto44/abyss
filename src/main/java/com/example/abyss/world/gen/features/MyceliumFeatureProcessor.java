package com.example.abyss.world.gen.features;

import com.example.abyss.lib.FastNoiseLite;
import com.example.abyss.world.gen.GenConfigResolver;
import com.example.abyss.world.gen.LayerFeatureProcessor;
import com.example.abyss.world.voxel.VoxelData;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Plants giant mushroom columns in the Mycelium layer.
 * <p>
 * Uses a coarse-grain noise to decide where mushroom "stems" grow,
 * then builds them as vertical MUSHROOM columns with wide caps.
 * </p>
 */
public class MyceliumFeatureProcessor implements LayerFeatureProcessor {

    private final FastNoiseLite placementNoise;

    /** How often a mushroom column appears (higher = rarer). */
    private static final float PLACEMENT_THRESHOLD = 0.65f;

    /** Radius of the mushroom cap in voxels. */
    private static final int CAP_RADIUS = 3;
    /** Height of the stem in voxels. */
    private static final int STEM_HEIGHT = 10;

    public MyceliumFeatureProcessor(long seed) {
        placementNoise = new FastNoiseLite((int) (seed ^ 0x4D_59_43_45L)); // "MYCE"
        placementNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        placementNoise.SetFrequency(0.06f);
    }

    @Override
    public void process(VoxelData data, int worldBaseX, int worldBaseY, int worldBaseZ, GenConfigResolver resolver) {
        int size = VoxelData.SIZE;

        // Scan the XZ columns of the chunk for placement sites
        for (int lz = 0; lz < size; lz++) {
            int wz = worldBaseZ + lz;
            for (int lx = 0; lx < size; lx++) {
                int wx = worldBaseX + lx;

                float n = placementNoise.GetNoise(wx, wz);
                if (n < PLACEMENT_THRESHOLD) continue;

                // Find the topmost solid voxel in this column (ground level)
                int groundY = -1;
                for (int ly = size - 1; ly >= 0; ly--) {
                    if (data.getType(lx, ly, lz).isSolid()) {
                        groundY = ly;
                        break;
                    }
                }
                if (groundY < 0 || groundY + STEM_HEIGHT + 2 >= size) continue;

                // Build stem
                for (int dy = 1; dy <= STEM_HEIGHT; dy++) {
                    int ly = groundY + dy;
                    data.setType(lx, ly, lz, VoxelType.MUSHROOM);
                    data.setDensity(lx, ly, lz, 0.5f);
                }

                // Build cap (flat disc)
                int capY = groundY + STEM_HEIGHT + 1;
                for (int cz = -CAP_RADIUS; cz <= CAP_RADIUS; cz++) {
                    for (int cx = -CAP_RADIUS; cx <= CAP_RADIUS; cx++) {
                        if (cx * cx + cz * cz > CAP_RADIUS * CAP_RADIUS) continue;
                        int px = lx + cx;
                        int pz = lz + cz;
                        if (px < 0 || px >= size || pz < 0 || pz >= size) continue;
                        if (capY >= size) continue;
                        data.setType(px, capY, pz, VoxelType.MUSHROOM);
                        data.setDensity(px, capY, pz, 0.5f);
                    }
                }
            }
        }
    }
}