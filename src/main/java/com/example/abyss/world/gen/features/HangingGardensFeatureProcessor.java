package com.example.abyss.world.gen.features;

import com.example.abyss.lib.FastNoiseLite;
import com.example.abyss.world.gen.GenConfigResolver;
import com.example.abyss.world.gen.LayerFeatureProcessor;
import com.example.abyss.world.voxel.VoxelData;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Adds floating island clusters in the Hanging Gardens layer.
 * <p>
 * Uses a secondary 3D noise field evaluated at lower frequency to
 * create isolated blobs of solid terrain suspended in air.  These
 * are overlaid on top of the base terrain so they can appear even
 * where the base density was negative (air).
 * </p>
 */
public class HangingGardensFeatureProcessor implements LayerFeatureProcessor {

    private final FastNoiseLite islandNoise;

    /** Density above which floating island material is placed. */
    private static final float ISLAND_THRESHOLD = 0.35f;

    public HangingGardensFeatureProcessor(long seed) {
        islandNoise = new FastNoiseLite((int) (seed ^ 0x48_41_4E_47L)); // "HANG"
        islandNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        islandNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        islandNoise.SetFractalOctaves(3);
        islandNoise.SetFrequency(0.008f);
    }

    @Override
    public void process(VoxelData data, int worldBaseX, int worldBaseY, int worldBaseZ, GenConfigResolver resolver) {
        int size = VoxelData.SIZE;

        for (int ly = 0; ly < size; ly++) {
            int wy = worldBaseY + ly;
            for (int lz = 0; lz < size; lz++) {
                int wz = worldBaseZ + lz;
                for (int lx = 0; lx < size; lx++) {
                    int wx = worldBaseX + lx;

                    // Only add to currently-air voxels
                    if (data.getType(lx, ly, lz) != VoxelType.AIR) continue;

                    float n = islandNoise.GetNoise(wx, wy, wz);
                    if (n > ISLAND_THRESHOLD) {
                        // Alternate between stone and vine for organic look
                        VoxelType type = (n > 0.50f) ? VoxelType.STONE : VoxelType.VINE;
                        data.setType(lx, ly, lz, type);
                        data.setDensity(lx, ly, lz, n);
                    }
                }
            }
        }
    }
}