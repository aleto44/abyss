package com.example.abyss.world.gen.features;

import com.example.abyss.lib.FastNoiseLite;
import com.example.abyss.world.gen.LayerFeatureProcessor;
import com.example.abyss.world.voxel.VoxelData;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Places surface vegetation (grass tufts and bush clusters) on The Edge layer.
 * <p>
 * Scans each XZ column for the topmost solid voxel, then places GRASS or
 * VINE voxels on top to simulate bushes and ground cover.
 * </p>
 */
public class EdgeFeatureProcessor implements LayerFeatureProcessor {

    private final FastNoiseLite vegetationNoise;

    /** Threshold above which a bush is placed. */
    private static final float BUSH_THRESHOLD = 0.40f;
    /** Higher threshold for denser bush clusters. */
    private static final float DENSE_BUSH_THRESHOLD = 0.60f;

    public EdgeFeatureProcessor(long seed) {
        vegetationNoise = new FastNoiseLite((int) (seed ^ 0x45_44_47_45L)); // "EDGE"
        vegetationNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        vegetationNoise.SetFrequency(0.08f);
    }

    @Override
    public void process(VoxelData data, int worldBaseX, int worldBaseY, int worldBaseZ) {
        int size = VoxelData.SIZE;

        for (int lz = 0; lz < size; lz++) {
            int wz = worldBaseZ + lz;
            for (int lx = 0; lx < size; lx++) {
                int wx = worldBaseX + lx;

                float n = vegetationNoise.GetNoise(wx, wz);
                if (n < BUSH_THRESHOLD) continue;

                // Find topmost solid voxel
                int surfaceY = -1;
                for (int ly = size - 1; ly >= 0; ly--) {
                    if (data.getType(lx, ly, lz).isSolid()) {
                        surfaceY = ly;
                        break;
                    }
                }
                if (surfaceY < 0 || surfaceY + 1 >= size) continue;
                if (data.getType(lx, surfaceY + 1, lz) != VoxelType.AIR) continue;

                // Place vegetation
                data.setType(lx, surfaceY + 1, lz, VoxelType.GRASS);
                data.setDensity(lx, surfaceY + 1, lz, 0.2f);

                // Dense clusters get a second layer
                if (n > DENSE_BUSH_THRESHOLD && surfaceY + 2 < size
                        && data.getType(lx, surfaceY + 2, lz) == VoxelType.AIR) {
                    data.setType(lx, surfaceY + 2, lz, VoxelType.VINE);
                    data.setDensity(lx, surfaceY + 2, lz, 0.15f);
                }
            }
        }
    }
}
