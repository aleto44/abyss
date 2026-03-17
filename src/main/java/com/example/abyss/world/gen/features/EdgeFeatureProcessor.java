package com.example.abyss.world.gen.features;

import com.example.abyss.lib.FastNoiseLite;
import com.example.abyss.world.gen.Layer;
import com.example.abyss.world.gen.LayerFeatureProcessor;
import com.example.abyss.world.voxel.VoxelData;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Feature processor for The Edge layer.
 * <p>
 * Two passes are applied:
 * <ol>
 *   <li><b>Ceiling blend</b> — near y=0 a density bias is added on top of the
 *       existing noise-generated density, pushing terrain solid where it connects
 *       to the Surface floor.  The bias is shaped by a low-frequency 3D noise so
 *       it fades out with the same large, rolling forms the rest of the Edge uses
 *       rather than a flat or stippled cut-off.</li>
 *   <li><b>Vegetation</b> — places grass tufts and bush clusters on the topmost
 *       solid voxel of each XZ column below the ceiling zone.</li>
 * </ol>
 * </p>
 */
public class EdgeFeatureProcessor implements LayerFeatureProcessor {

    private final FastNoiseLite vegetationNoise;
    /**
     * Low-frequency 3D noise that shapes the ceiling blend magnitude per voxel.
     * Matching the Edge terrain noise scale (≈0.018) keeps the transition visually
     * consistent with the surrounding terrain.
     */
    private final FastNoiseLite ceilingShapeNoise;
    /**
     * Very-low-frequency XZ noise that varies the effective fade depth per column.
     * Low frequency (0.005) means depth variation forms wide sweeping regions,
     * not rapid jitter, so thick hanging masses dissolve into Edge terrain gradually.
     */
    private final FastNoiseLite ceilingDepthNoise;

    /** Threshold above which a bush is placed. */
    private static final float BUSH_THRESHOLD = 0.40f;
    /** Higher threshold for denser bush clusters. */
    private static final float DENSE_BUSH_THRESHOLD = 0.60f;

    /** Shallowest per-column fade depth — thin lips that open up quickly. */
    private static final int CEILING_FADE_MIN = 48;
    /** Deepest per-column fade depth — thick hanging masses blending into Edge terrain. */
    private static final int CEILING_FADE_MAX = 224;
    /** Outer Y bound for the ceiling zone loop — equal to the deepest possible fade. */
    private static final int CEILING_ZONE_HEIGHT = CEILING_FADE_MAX;

    /**
     * Maximum density bias injected at y=0. Large enough to push any air voxel
     * over the solid threshold near the ceiling; noise modulation can still keep
     * some pockets open even right at the top.
     */
    private static final float CEILING_MAX_BIAS = 1.2f;

    /** Margin beyond the shaft radius where ceiling fill is suppressed. */
    private static final float SHAFT_FILL_MARGIN = 30f;

    public EdgeFeatureProcessor(long seed) {
        vegetationNoise = new FastNoiseLite((int) (seed ^ 0x45_44_47_45L)); // "EDGE"
        vegetationNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        vegetationNoise.SetFrequency(0.08f);

        ceilingShapeNoise = new FastNoiseLite((int) (seed ^ 0x43_4C_47_00L)); // "CLG\0"
        ceilingShapeNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        ceilingShapeNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        ceilingShapeNoise.SetFractalOctaves(3);
        ceilingShapeNoise.SetFrequency(0.018f);

        ceilingDepthNoise = new FastNoiseLite((int) (seed ^ 0x44_50_54_48L)); // "DPTH"
        ceilingDepthNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        ceilingDepthNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        ceilingDepthNoise.SetFractalOctaves(2);
        ceilingDepthNoise.SetFrequency(0.005f);
    }

    @Override
    public void process(VoxelData data, int worldBaseX, int worldBaseY, int worldBaseZ) {
        int size = VoxelData.SIZE;
        int ceilingY = Layer.THE_EDGE.getYStart(); // == 0

        blendCeiling(data, worldBaseX, worldBaseY, worldBaseZ, ceilingY);

        for (int lz = 0; lz < size; lz++) {
            int wz = worldBaseZ + lz;
            for (int lx = 0; lx < size; lx++) {
                int wx = worldBaseX + lx;

                float n = vegetationNoise.GetNoise(wx, wz);
                if (n < BUSH_THRESHOLD) {
                    continue;
                }

                int surfaceY = findTopmostSolidBelowCeiling(data, lx, lz, worldBaseY, ceilingY);
                if (surfaceY < 0 || surfaceY + 1 >= size) {
                    continue;
                }
                if (data.getType(lx, surfaceY + 1, lz) != VoxelType.AIR) {
                    continue;
                }

                data.setType(lx, surfaceY + 1, lz, VoxelType.GRASS);
                data.setDensity(lx, surfaceY + 1, lz, 0.2f);

                if (n > DENSE_BUSH_THRESHOLD && surfaceY + 2 < size
                        && data.getType(lx, surfaceY + 2, lz) == VoxelType.AIR) {
                    data.setType(lx, surfaceY + 2, lz, VoxelType.VINE);
                    data.setDensity(lx, surfaceY + 2, lz, 0.15f);
                }
            }
        }
    }

    /**
     * Adds a depth-decaying density bias to every voxel in the ceiling zone.
     * <p>
     * Each XZ column gets its own effective fade depth from {@link #ceilingDepthNoise},
     * ranging from {@link #CEILING_FADE_MIN} to {@link #CEILING_FADE_MAX}. Columns
     * that draw a deep value stay solid for many chunks worth of depth before
     * dissolving into open Edge terrain; shallow columns open up quickly, creating
     * irregular rocky overhangs. Within each column the bias decays via smoothstep
     * relative to that column's own depth, and is further modulated by
     * {@link #ceilingShapeNoise} so the solid/air boundary traces terrain-shaped
     * contours rather than a flat cut.
     * </p>
     */
    private void blendCeiling(VoxelData data, int worldBaseX, int worldBaseY, int worldBaseZ, int ceilingY) {
        int size = VoxelData.SIZE;
        float shaftRadius    = Layer.THE_EDGE.getGenConfig().shaftDiameter() * 0.5f;
        float shaftFillLimit = shaftRadius + SHAFT_FILL_MARGIN;

        for (int lz = 0; lz < size; lz++) {
            int wz = worldBaseZ + lz;
            for (int lx = 0; lx < size; lx++) {
                int wx = worldBaseX + lx;

                float horizontalDist = (float) Math.sqrt((double) wx * wx + (double) wz * wz);
                boolean isInsideShaft = horizontalDist <= shaftFillLimit;

                // Per-column fade depth: depth noise [0,1] maps linearly to [MIN, MAX].
                float depthSample     = ceilingDepthNoise.GetNoise(wx, wz) * 0.5f + 0.5f;
                float columnFadeDepth = CEILING_FADE_MIN + depthSample * (CEILING_FADE_MAX - CEILING_FADE_MIN);

                for (int ly = 0; ly < size; ly++) {
                    int wy = worldBaseY + ly;
                    int depthBelowCeiling = ceilingY - wy;
                    boolean isInCeilingZone = depthBelowCeiling >= 0 && depthBelowCeiling < CEILING_ZONE_HEIGHT;
                    if (!isInCeilingZone) {
                        continue;
                    }

                    if (isInsideShaft) {
                        data.setType(lx, ly, lz, VoxelType.AIR);
                        data.setDensity(lx, ly, lz, Math.min(data.getDensity(lx, ly, lz), -0.2f));
                        continue;
                    }

                    // Beyond this column's own fade depth the bias is zero.
                    if (depthBelowCeiling >= columnFadeDepth) {
                        continue;
                    }

                    // Smoothstep fade relative to this column's own depth.
                    float t          = depthBelowCeiling / columnFadeDepth;
                    float fadeWeight = 1f - (t * t * (3f - 2f * t));

                    // Shape noise modulates magnitude so the boundary traces terrain contours.
                    float shapeSample = ceilingShapeNoise.GetNoise(wx, wy, wz);
                    float densityBias = fadeWeight * CEILING_MAX_BIAS * (0.6f + 0.4f * shapeSample);

                    float existingDensity = data.getDensity(lx, ly, lz);
                    float newDensity      = existingDensity + densityBias;
                    data.setDensity(lx, ly, lz, newDensity);

                    boolean becomesSolid = newDensity > 0f && !data.getType(lx, ly, lz).isSolid();
                    if (becomesSolid) {
                        data.setType(lx, ly, lz, VoxelType.STONE);
                    }
                }
            }
        }
    }

    /**
     * Finds the topmost solid voxel in a column that sits below the ceiling zone,
     * so vegetation is never placed on the ceiling itself.
     */
    private int findTopmostSolidBelowCeiling(VoxelData data, int lx, int lz, int worldBaseY, int ceilingY) {
        int ceilingZoneBottom = ceilingY - CEILING_ZONE_HEIGHT;
        for (int ly = VoxelData.SIZE - 1; ly >= 0; ly--) {
            int wy = worldBaseY + ly;
            boolean isBelowCeilingZone = wy < ceilingZoneBottom;
            if (isBelowCeilingZone && data.getType(lx, ly, lz).isSolid()) {
                return ly;
            }
        }
        return -1;
    }
}