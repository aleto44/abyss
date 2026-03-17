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
     * Low-frequency 3D noise that shapes the ceiling blend.  Matching the Edge
     * terrain noise scale (≈0.020) keeps the transition visually consistent with
     * the surrounding terrain rather than introducing a new, alien pattern.
     */
    private final FastNoiseLite ceilingShapeNoise;

    /** Threshold above which a bush is placed. */
    private static final float BUSH_THRESHOLD = 0.40f;
    /** Higher threshold for denser bush clusters. */
    private static final float DENSE_BUSH_THRESHOLD = 0.60f;

    /**
     * Depth (in voxels below y=0) over which the ceiling density bias fades to
     * zero.  Beyond this depth the terrain is entirely noise-driven.
     */
    private static final int CEILING_FADE_DEPTH = 96;

    /**
     * Maximum density bias injected at y=0.  Must be large enough to push any
     * noise-generated air voxel over the solid threshold (density > 0) near the
     * ceiling, while the noise modulation can still keep some voxels open for a
     * natural look even right at the top.
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
     * The bias is modulated by {@link #ceilingShapeNoise} so the solid/air
     * boundary at the transition has the same large rolling shapes as the rest
     * of the Edge terrain.  Smoothstep easing ensures there is no perceptible
     * seam at the bottom of the zone.
     * <p>
     * The shaft opening and its margin are cleared, not filled.
     * </p>
     */
    private void blendCeiling(VoxelData data, int worldBaseX, int worldBaseY, int worldBaseZ, int ceilingY) {
        int size = VoxelData.SIZE;
        float shaftRadius   = Layer.THE_EDGE.getGenConfig().shaftDiameter() * 0.5f;
        float shaftFillLimit = shaftRadius + SHAFT_FILL_MARGIN;

        for (int ly = 0; ly < size; ly++) {
            int wy = worldBaseY + ly;
            int depthBelowCeiling = ceilingY - wy;
            boolean isInCeilingZone = depthBelowCeiling >= 0 && depthBelowCeiling < CEILING_FADE_DEPTH;
            if (!isInCeilingZone) {
                continue;
            }

            // Smoothstep fade: 1.0 at ceiling, eases to 0.0 at CEILING_FADE_DEPTH.
            float t = (float) depthBelowCeiling / CEILING_FADE_DEPTH;
            float fadeWeight = 1f - (t * t * (3f - 2f * t));

            for (int lz = 0; lz < size; lz++) {
                int wz = worldBaseZ + lz;
                for (int lx = 0; lx < size; lx++) {
                    int wx = worldBaseX + lx;

                    float horizontalDist = (float) Math.sqrt((double) wx * wx + (double) wz * wz);
                    boolean isInsideShaft = horizontalDist <= shaftFillLimit;
                    if (isInsideShaft) {
                        data.setType(lx, ly, lz, VoxelType.AIR);
                        data.setDensity(lx, ly, lz, Math.min(data.getDensity(lx, ly, lz), -0.2f));
                        continue;
                    }

                    // Noise in [-1,1]: modulates the bias so the boundary is terrain-shaped.
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
        int ceilingZoneBottom = ceilingY - CEILING_FADE_DEPTH;
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