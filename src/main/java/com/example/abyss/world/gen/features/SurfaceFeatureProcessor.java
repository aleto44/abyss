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
    /**
     * Minimum height (in voxels above the surface layer floor) that every mountain
     * column reaches.  Raising this makes the whole range feel heftier.
     */
    private static final int MOUNTAIN_BASE_WORLD_Y = 60;
    /**
     * Maximum additional height variation on top of the base.  A larger value
     * produces taller, more dramatic peaks.
     */
    private static final int MOUNTAIN_HEIGHT_VARIATION = 280;
    private static final int STONE_CAP_DEPTH = 8;
    private static final int DIRT_DEPTH = 5;
    private static final float SHAFT_MARGIN = 18f;

    /**
     * Inner radius where the mountain ring begins (just outside the shaft opening).
     * Should be slightly larger than the shaft radius (shaftDiameter/2 = 260).
     */
    private static final float MOUNTAIN_INNER_RADIUS = 280f;
    /**
     * Radius at which mountain height reaches its maximum.  Mountains build up
     * from zero at {@link #MOUNTAIN_INNER_RADIUS} to full height here.
     */
    private static final float MOUNTAIN_PEAK_RADIUS = 450f;
    /**
     * Radius at which mountains have fully faded back to zero.  Everything beyond
     * this distance is flat ocean, giving the world an island-around-an-abyss look.
     * Must be greater than {@link #MOUNTAIN_PEAK_RADIUS}.
     */
    private static final float MOUNTAIN_FADE_END = 700f;

    private final FastNoiseLite surfaceDetailNoise;
    /**
     * Very-low-frequency "continent" noise that clusters peaks into broad mountain
     * ranges rather than scattering them evenly across the surface.
     */
    private final FastNoiseLite continentNoise;

    public SurfaceFeatureProcessor(long seed) {
        // Fine detail / ridge noise – used for vegetation placement and peak sharpening.
        surfaceDetailNoise = new FastNoiseLite((int) (seed ^ 0x53_55_52_46L)); // "SURF"
        surfaceDetailNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        surfaceDetailNoise.SetFrequency(0.025f); // low-frequency → wide, thick mountains

        // Continental noise – groups mountains into large connected ranges.
        continentNoise = new FastNoiseLite((int) (seed ^ 0x434F_4E54L)); // "CONT"
        continentNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        continentNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        continentNoise.SetFractalOctaves(3);
        continentNoise.SetFrequency(0.004f); // scaled to island ring (~700 voxel radius)
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
        // --- Radial envelope ---
        // Mountains ring the abyss shaft and must not go on forever.
        // The envelope is 0 inside the shaft, rises to 1 at MOUNTAIN_PEAK_RADIUS,
        // then fades smoothly back to 0 at MOUNTAIN_FADE_END.
        float horizontalDist = (float) Math.sqrt((double) wx * wx + (double) wz * wz);
        float riseT  = Math.max(0f, Math.min(1f, (horizontalDist - MOUNTAIN_INNER_RADIUS)
                                                  / (MOUNTAIN_PEAK_RADIUS - MOUNTAIN_INNER_RADIUS)));
        float fadeT  = Math.max(0f, Math.min(1f, (horizontalDist - MOUNTAIN_PEAK_RADIUS)
                                                  / (MOUNTAIN_FADE_END - MOUNTAIN_PEAK_RADIUS)));
        // Smoothstep both transitions for natural curves, then combine: rise up, then fade out.
        float rise   = riseT * riseT * (3f - 2f * riseT);
        float fade   = fadeT * fadeT * (3f - 2f * fadeT);
        float radialEnvelope = rise * (1f - fade);

        // Early-out: column is flat, nothing to build.
        if (radialEnvelope <= 0f) {
            return 0;
        }

        // --- Continental noise ---
        // Groups peaks into large ranges with wide flat valleys between them.
        float cont = continentNoise.GetNoise(wx, wz) * 0.5f + 0.5f; // 0..1
        cont = cont * cont * (3f - 2f * cont); // smoothstep – sharpens range boundaries
        cont = cont * cont;                      // extra squeeze – very wide flat valleys

        // --- Shape noise ---
        float broadNoise = surfaceDetailNoise.GetNoise(wx * 0.3f, wz * 0.3f);
        float ridgeNoise  = surfaceDetailNoise.GetNoise(wx * 0.8f + 41.3f, wz * 0.8f - 17.8f);
        float blendedShape = (broadNoise * 0.5f + 0.5f) * 0.7f + (ridgeNoise * 0.5f + 0.5f) * 0.3f;

        // Final height = full potential × continental gate × radial envelope.
        int height = Math.round(
                radialEnvelope * cont * (MOUNTAIN_BASE_WORLD_Y + blendedShape * MOUNTAIN_HEIGHT_VARIATION));
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