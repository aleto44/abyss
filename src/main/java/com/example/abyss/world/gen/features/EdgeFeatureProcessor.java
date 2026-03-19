package com.example.abyss.world.gen.features;

import com.example.abyss.lib.FastNoiseLite;
import com.example.abyss.world.gen.Layer;
import com.example.abyss.world.gen.LayerFeatureProcessor;
import com.example.abyss.world.voxel.VoxelData;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Generates the mostly-solid vertical abyss wall for The Edge layer.
 * <p>
 * Implements the three spec steps in order:
 * <ol>
 *   <li>Determine wall position — radial void radius, tapered slightly inward with depth
 *       to give the crater a natural conical profile.</li>
 *   <li>Solid fill with jagged inner face — every voxel defaults to solid; only the band
 *       immediately adjacent to the exposed wall face is perturbed with very-low-amplitude
 *       3D FBm noise to create subtle irregularity.</li>
 *   <li>Central void carve — everything within the effective void radius (plus a gentle
 *       Perlin jitter for non-circular irregularity) is carved to air.</li>
 * </ol>
 * No vegetation, ceiling blend, or traversable features — all deprecated.
 * </p>
 */
public class EdgeFeatureProcessor implements LayerFeatureProcessor {

    // ---------------------------------------------------------------
    // Wall geometry constants
    // ---------------------------------------------------------------

    /**
     * Radius of the open void at the top of the Edge layer (y = 0).
     * Derived from The Edge's shaft diameter so this always matches the shaft
     * carved by the density function.
     */
    private static final float VOID_RADIUS_AT_SURFACE =
            Layer.THE_EDGE.getGenConfig().shaftDiameter() * 0.5f;

    /**
     * How much the void radius widens per 1000 depth-units below the Edge top.
     * A positive value means the hole gets slightly larger as you descend,
     * giving the crater a natural inward-tapering (conical) appearance.
     */
    private static final float TAPER_PER_1000_DEPTH = 0.10f;

    /**
     * Maximum XZ perturbation (in voxels) applied to voxels inside the inner-face
     * band.  Spec says 2–6 voxels; 4 sits comfortably in the middle.
     */
    private static final float WALL_PERTURB_AMPLITUDE = 4f;

    /**
     * How many voxels inward from the void boundary counts as the "inner face band".
     * Only voxels within this band have wall-surface jitter applied.
     */
    private static final float INNER_FACE_BAND = 20f;

    /**
     * Amplitude of the large-scale Perlin jitter applied to the void edge.
     * Spec says 8–20 voxels; 14 gives a noticeable but not excessive irregularity.
     */
    private static final float VOID_JITTER_AMPLITUDE = 14f;

    // ---------------------------------------------------------------
    // Noise
    // ---------------------------------------------------------------

    /**
     * Very low frequency 3D FBm used to perturb the exposed inner wall face.
     * Low frequency (0.003) means deviations form broad sweeping bumps, not chaotic
     * pocking, consistent with the spec's "subtle jaggedness" intent.
     */
    private final FastNoiseLite wallPerturbNoise;

    /**
     * Low frequency 2D noise used to jitter the void edge so the central opening
     * is irregular rather than a perfect circle.
     */
    private final FastNoiseLite voidJitterNoise;

    public EdgeFeatureProcessor(long seed) {
        wallPerturbNoise = new FastNoiseLite((int) (seed ^ 0x574C_5052L)); // "WLPR"
        wallPerturbNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        wallPerturbNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        wallPerturbNoise.SetFractalOctaves(2);
        wallPerturbNoise.SetFrequency(0.003f);

        voidJitterNoise = new FastNoiseLite((int) (seed ^ 0x564A_5452L)); // "VJTR"
        voidJitterNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        voidJitterNoise.SetFrequency(0.007f);
    }

    // ---------------------------------------------------------------
    // Process
    // ---------------------------------------------------------------

    @Override
    public void process(VoxelData data, int worldBaseX, int worldBaseY, int worldBaseZ) {
        int size = VoxelData.SIZE;
        int edgeTop    = Layer.THE_EDGE.getYStart(); // == 0  (inclusive upper bound)
        int edgeBottom = Layer.THE_EDGE.getYEnd();   // == -500 (exclusive lower bound)

        for (int ly = 0; ly < size; ly++) {
            int wy = worldBaseY + ly;

            // Only process voxels that actually belong to the Edge layer.
            boolean isInEdgeLayer = wy <= edgeTop && wy > edgeBottom;
            if (!isInEdgeLayer) {
                continue;
            }

            // Step 1 — effective void radius at this depth.
            // Depth below the top of the Edge layer (y = 0) in thousands of voxels.
            float depthBelow      = (float) (Layer.THE_EDGE.getYStart() - wy);
            float depthFactor     = depthBelow / 1000f;
            float effectiveRadius = VOID_RADIUS_AT_SURFACE + depthFactor * TAPER_PER_1000_DEPTH
                                    * VOID_RADIUS_AT_SURFACE;

            for (int lz = 0; lz < size; lz++) {
                int wz = worldBaseZ + lz;
                for (int lx = 0; lx < size; lx++) {
                    int wx = worldBaseX + lx;

                    float radialDist = (float) Math.sqrt((double) wx * wx + (double) wz * wz);

                    // Step 3 — void carve (checked first to avoid wasting work on wall logic).
                    // Gentle XZ Perlin jitter makes the edge non-circular.
                    float voidJitter      = voidJitterNoise.GetNoise(wx, wz) * VOID_JITTER_AMPLITUDE;
                    float jitteredRadius  = effectiveRadius + voidJitter;
                    boolean isInVoid      = radialDist < jitteredRadius;
                    if (isInVoid) {
                        data.setType(lx, ly, lz, VoxelType.AIR);
                        data.setDensity(lx, ly, lz, Math.min(data.getDensity(lx, ly, lz), -0.2f));
                        continue;
                    }

                    // Step 2 — solid fill with optional inner-face perturbation.
                    float distanceBeyondVoid = radialDist - jitteredRadius;
                    boolean isInInnerFaceBand = distanceBeyondVoid <= INNER_FACE_BAND;
                    if (isInInnerFaceBand) {
                        float perturb    = wallPerturbNoise.GetNoise(wx, wy, wz) * WALL_PERTURB_AMPLITUDE;
                        boolean carvedByPerturb = (radialDist + perturb) < jitteredRadius;
                        if (carvedByPerturb) {
                            data.setType(lx, ly, lz, VoxelType.AIR);
                            data.setDensity(lx, ly, lz, Math.min(data.getDensity(lx, ly, lz), -0.2f));
                            continue;
                        }
                    }

                    data.setType(lx, ly, lz, VoxelType.STONE);
                    if (data.getDensity(lx, ly, lz) <= 0f) {
                        data.setDensity(lx, ly, lz, 0.50f);
                    }
                }
            }
        }
    }
}