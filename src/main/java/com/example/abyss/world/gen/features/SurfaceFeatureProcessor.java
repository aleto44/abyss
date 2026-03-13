package com.example.abyss.world.gen.features;

import com.example.abyss.lib.FastNoiseLite;
import com.example.abyss.world.gen.LayerFeatureProcessor;
import com.example.abyss.world.voxel.VoxelData;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Adds light alpine dressing to the Surface layer.
 * <p>
 * The processor scans for exposed ground and, in sparse noise-selected columns,
 * places a tuft of grass or a small shrub so the surface mountains feel less barren.
 * Placement is chunk-local and deterministic.
 * </p>
 */
public class SurfaceFeatureProcessor implements LayerFeatureProcessor {

    private static final float SHRUB_THRESHOLD = 0.58f;
    private static final float TALL_GRASS_THRESHOLD = 0.30f;

    private final FastNoiseLite surfaceDetailNoise;

    public SurfaceFeatureProcessor(long seed) {
        surfaceDetailNoise = new FastNoiseLite((int) (seed ^ 0x53_55_52_46L)); // "SURF"
        surfaceDetailNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        surfaceDetailNoise.SetFrequency(0.07f);
    }

    @Override
    public void process(VoxelData data, int worldBaseX, int worldBaseY, int worldBaseZ) {
        int size = VoxelData.SIZE;

        for (int lz = 0; lz < size; lz++) {
            int wz = worldBaseZ + lz;
            for (int lx = 0; lx < size; lx++) {
                int wx = worldBaseX + lx;
                float noiseSample = surfaceDetailNoise.GetNoise(wx, wz);
                if (noiseSample < TALL_GRASS_THRESHOLD) {
                    continue;
                }

                int groundY = findTopSolidVoxel(data, lx, lz);
                if (groundY < 0 || groundY + 1 >= size) {
                    continue;
                }

                VoxelType groundType = data.getType(lx, groundY, lz);
                if (groundType != VoxelType.GRASS && groundType != VoxelType.DIRT) {
                    continue;
                }
                if (data.getType(lx, groundY + 1, lz) != VoxelType.AIR) {
                    continue;
                }

                data.setType(lx, groundY + 1, lz, VoxelType.GRASS);
                data.setDensity(lx, groundY + 1, lz, 0.18f);

                if (noiseSample >= SHRUB_THRESHOLD && groundY + 2 < size && data.getType(lx, groundY + 2, lz) == VoxelType.AIR) {
                    data.setType(lx, groundY + 2, lz, VoxelType.VINE);
                    data.setDensity(lx, groundY + 2, lz, 0.12f);
                }
            }
        }
    }

    private static int findTopSolidVoxel(VoxelData data, int lx, int lz) {
        for (int ly = VoxelData.SIZE - 1; ly >= 0; ly--) {
            if (data.getType(lx, ly, lz).isSolid()) {
                return ly;
            }
        }
        return -1;
    }
}
