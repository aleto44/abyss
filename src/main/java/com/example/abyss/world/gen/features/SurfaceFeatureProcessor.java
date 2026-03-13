package com.example.abyss.world.gen.features;

import com.example.abyss.lib.FastNoiseLite;
import com.example.abyss.world.gen.Layer;
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
    private static final int MOUNTAIN_BASE_WORLD_Y = 32;
    private static final int MOUNTAIN_HEIGHT_VARIATION = 180;
    private static final int STONE_CAP_DEPTH = 5;
    private static final int DIRT_DEPTH = 4;
    private static final float SHAFT_MARGIN = 18f;

    private final FastNoiseLite surfaceDetailNoise;

    public SurfaceFeatureProcessor(long seed) {
        surfaceDetailNoise = new FastNoiseLite((int) (seed ^ 0x53_55_52_46L)); // "SURF"
        surfaceDetailNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        surfaceDetailNoise.SetFrequency(0.07f);
    }

    @Override
    public void process(VoxelData data, int worldBaseX, int worldBaseY, int worldBaseZ) {
        int size = VoxelData.SIZE;

        shapeSurfaceChunk(data, worldBaseX, worldBaseY, worldBaseZ);

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

    private void shapeSurfaceChunk(VoxelData data, int worldBaseX, int worldBaseY, int worldBaseZ) {
        int size = VoxelData.SIZE;
        for (int lz = 0; lz < size; lz++) {
            int wz = worldBaseZ + lz;
            for (int lx = 0; lx < size; lx++) {
                int wx = worldBaseX + lx;
                boolean preserveShaft = shouldPreserveShaft(wx, wz);
                int mountainHeight = determineMountainHeight(wx, wz);
                int mountainTopWorldY = Layer.SURFACE.getYEnd() + mountainHeight;
                for (int ly = 0; ly < size; ly++) {
                    int wy = worldBaseY + ly;
                    if (preserveShaft) {
                        clearVoxel(data, lx, ly, lz);
                        continue;
                    }
                    if (wy <= mountainTopWorldY && wy >= Layer.SURFACE.getYEnd()) {
                        if (!data.getType(lx, ly, lz).isSolid()) {
                            data.setType(lx, ly, lz, VoxelType.STONE);
                        }
                    } else {
                        clearVoxel(data, lx, ly, lz);
                    }
                }
                if (!preserveShaft) {
                    paintMountainColumn(data, lx, lz, worldBaseY, mountainTopWorldY);
                }
            }
        }
    }

    private int determineMountainHeight(int wx, int wz) {
        float broadNoise = surfaceDetailNoise.GetNoise(wx * 0.45f, wz * 0.45f);
        float ridgeNoise = surfaceDetailNoise.GetNoise(wx * 1.15f + 41.3f, wz * 1.15f - 17.8f);
        int height = MOUNTAIN_BASE_WORLD_Y
                + Math.round((broadNoise * 0.5f + 0.5f) * MOUNTAIN_HEIGHT_VARIATION)
                + Math.round(ridgeNoise * 18f);
        return Math.max(0, Math.min(Layer.SURFACE.getYStart() - Layer.SURFACE.getYEnd(), height));
    }

    private void paintMountainColumn(VoxelData data, int lx, int lz, int worldBaseY, int mountainTopWorldY) {
        int localTop = mountainTopWorldY - worldBaseY;
        if (localTop < 0) {
            return;
        }

        int clampedLocalTop = Math.min(localTop, VoxelData.SIZE - 1);
        int localBottom = Math.max(0, Layer.SURFACE.getYEnd() - worldBaseY);
        if (localBottom > clampedLocalTop) {
            return;
        }

        for (int ly = localBottom; ly <= clampedLocalTop; ly++) {
            if (!data.getType(lx, ly, lz).isSolid()) {
                data.setType(lx, ly, lz, VoxelType.STONE);
            }

            int depthFromTop = clampedLocalTop - ly;
            VoxelType columnType = resolveMountainMaterial(depthFromTop, clampedLocalTop - localBottom);
            data.setType(lx, ly, lz, columnType);
            if (data.getDensity(lx, ly, lz) <= 0f) {
                data.setDensity(lx, ly, lz, 0.35f + (clampedLocalTop - ly) * 0.04f);
            }
        }
    }

    private static boolean shouldPreserveShaft(int wx, int wz) {
        float shaftRadius = Layer.SURFACE.getConfig().shaftDiameter() * 0.5f;
        float horizontalDistance = (float) Math.sqrt((double) wx * wx + (double) wz * wz);
        return horizontalDistance <= shaftRadius + SHAFT_MARGIN;
    }

    private static VoxelType resolveMountainMaterial(int depthFromTop, int columnHeight) {
        if (columnHeight >= STONE_CAP_DEPTH * 3 && depthFromTop <= STONE_CAP_DEPTH) {
            return VoxelType.STONE;
        }
        if (depthFromTop == 0) {
            return VoxelType.GRASS;
        }
        if (depthFromTop <= DIRT_DEPTH) {
            return VoxelType.DIRT;
        }
        return VoxelType.STONE;
    }

    private static void clearVoxel(VoxelData data, int lx, int ly, int lz) {
        data.setType(lx, ly, lz, VoxelType.AIR);
        data.setDensity(lx, ly, lz, Math.min(data.getDensity(lx, ly, lz), -0.2f));
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