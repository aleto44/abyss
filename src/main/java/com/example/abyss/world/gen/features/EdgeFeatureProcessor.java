package com.example.abyss.world.gen.features;

import com.example.abyss.lib.FastNoiseLite;
import com.example.abyss.world.gen.ColumnVoxelEvaluator;
import com.example.abyss.world.gen.GenConfigResolver;
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
public class EdgeFeatureProcessor implements LayerFeatureProcessor, ColumnVoxelEvaluator {

    // ---------------------------------------------------------------
    // Wall geometry constants
    // ---------------------------------------------------------------

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

    /**
     * How many voxels below y=0 the rim erosion effect is active.
     * Lower → crumbled lip is shallower, transition is more abrupt.
     * Higher → eroded look extends further down the wall before fading out.
     */
    private static final float RIM_EROSION_DEPTH = 50f;

    /**
     * Maximum extra void radius (voxels) the erosion can eat into the wall at y=0.
     * Lower → subtle lip weathering only.
     * Higher → wall is chewed back more aggressively at the top.
     * Keep well below the ring inner radius (innerSlopeStart + RING_INNER_OFFSET_FROM_SLOPE_START) to avoid eating into the mountain foothills.
     */
    private static final float RIM_EROSION_AMPLITUDE = 20f;

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

    /**
     * Low-frequency 3D FBm that varies the rim erosion around the circumference.
     * Produces broad irregular bays rather than a uniform ring of widening.
     * Frequency (0.005): lower → wider bays; higher → more frequent smaller notches.
     */
    private final FastNoiseLite rimErosionNoise;

    public EdgeFeatureProcessor(long seed) {
        wallPerturbNoise = new FastNoiseLite((int) (seed ^ 0x574C_5052L)); // "WLPR"
        wallPerturbNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        wallPerturbNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        wallPerturbNoise.SetFractalOctaves(2);
        wallPerturbNoise.SetFrequency(0.003f);

        voidJitterNoise = new FastNoiseLite((int) (seed ^ EdgeVoidGeometry.VOID_JITTER_SEED_XOR));
        voidJitterNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        voidJitterNoise.SetFrequency(EdgeVoidGeometry.VOID_JITTER_FREQUENCY);

        rimErosionNoise = new FastNoiseLite((int) (seed ^ EdgeVoidGeometry.RIM_EROSION_SEED_XOR));
        rimErosionNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        rimErosionNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        rimErosionNoise.SetFractalOctaves(EdgeVoidGeometry.RIM_EROSION_OCTAVES);
        rimErosionNoise.SetFrequency(EdgeVoidGeometry.RIM_EROSION_FREQUENCY);
    }

    // ---------------------------------------------------------------
    // ColumnVoxelEvaluator — used by the heightmap fast path
    // ---------------------------------------------------------------

    /** Immutable XZ-stable state for one column: radial distance, jitter, and base void radius. */
    private record EdgeColumnContext(
            float radialDist,
            float voidJitter,
            float voidRadiusAtSurface
    ) implements ColumnVoxelEvaluator.ColumnContext {}

    @Override
    public ColumnVoxelEvaluator.ColumnContext prepareColumn(int wx, int wz, GenConfigResolver resolver) {
        float voidRadiusAtSurface = resolver.resolve(Layer.THE_EDGE.getYStart(), Layer.THE_EDGE).shaftDiameter() * 0.5f;
        float radialDist          = (float) Math.sqrt((double) wx * wx + (double) wz * wz);
        float voidJitter          = voidJitterNoise.GetNoise(wx, wz) * VOID_JITTER_AMPLITUDE;
        return new EdgeColumnContext(radialDist, voidJitter, voidRadiusAtSurface);
    }

    /**
     * Replicates the void-carve logic from {@link #process} on a per-voxel basis.
     * Returns {@code null} for Y values outside {@code THE_EDGE} layer bounds so the
     * caller can fall through to the density function for those positions.
     */
    @Override
    public VoxelType evaluateVoxelType(int wx, int wy, int wz, ColumnVoxelEvaluator.ColumnContext ctx) {
        int edgeTop    = Layer.THE_EDGE.getYStart();
        int edgeBottom = Layer.THE_EDGE.getYEnd();
        boolean isInEdgeLayer = wy <= edgeTop && wy > edgeBottom;
        if (!isInEdgeLayer) {
            return null;
        }

        EdgeColumnContext edge = (EdgeColumnContext) ctx;

        float depthBelow      = (float) (edgeTop - wy);
        float depthFactor     = depthBelow / 1000f;
        float effectiveRadius = edge.voidRadiusAtSurface() + depthFactor * TAPER_PER_1000_DEPTH
                                * edge.voidRadiusAtSurface();

        float rimErosionFactor = 1f - smoothstep(0f, RIM_EROSION_DEPTH, depthBelow);
        float erosionSample    = rimErosionNoise.GetNoise(wx, wy, wz) * 0.5f + 0.5f;
        float rimErosion       = erosionSample * RIM_EROSION_AMPLITUDE * rimErosionFactor;

        float jitteredRadius = effectiveRadius + edge.voidJitter() + rimErosion;

        boolean isInVoid = edge.radialDist() < jitteredRadius;
        if (isInVoid) {
            return VoxelType.AIR;
        }

        float distanceBeyondVoid  = edge.radialDist() - jitteredRadius;
        boolean isInInnerFaceBand = distanceBeyondVoid <= INNER_FACE_BAND;
        if (isInInnerFaceBand) {
            float perturb           = wallPerturbNoise.GetNoise(wx, wy, wz) * WALL_PERTURB_AMPLITUDE;
            boolean carvedByPerturb = (edge.radialDist() + perturb) < jitteredRadius;
            if (carvedByPerturb) {
                return VoxelType.AIR;
            }
        }

        return VoxelType.STONE;
    }

    // ---------------------------------------------------------------
    // Process
    // ---------------------------------------------------------------

    @Override
    public void process(VoxelData data, int worldBaseX, int worldBaseY, int worldBaseZ, GenConfigResolver resolver) {
        int size = VoxelData.SIZE;
        int edgeTop    = Layer.THE_EDGE.getYStart();
        int edgeBottom = Layer.THE_EDGE.getYEnd();

        // Resolve shaft diameter via the resolver so debug overrides are respected.
        float voidRadiusAtSurface = resolver.resolve(edgeTop, Layer.THE_EDGE).shaftDiameter() * 0.5f;

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
            float effectiveRadius = voidRadiusAtSurface + depthFactor * TAPER_PER_1000_DEPTH
                                    * voidRadiusAtSurface;

            // Rim erosion: strongest at y=0, fades smoothly to zero at y=-RIM_EROSION_DEPTH.
            float rimErosionFactor = 1f - smoothstep(0f, RIM_EROSION_DEPTH, depthBelow);

            for (int lz = 0; lz < size; lz++) {
                int wz = worldBaseZ + lz;
                for (int lx = 0; lx < size; lx++) {
                    int wx = worldBaseX + lx;

                    float radialDist = (float) Math.sqrt((double) wx * wx + (double) wz * wz);

                    // Step 3 — void carve (checked first to avoid wasting work on wall logic).
                    // Gentle XZ Perlin jitter makes the edge non-circular.
                    // Near y=0, rim erosion noise eats further into the wall to mimic weathering.
                    float voidJitter     = voidJitterNoise.GetNoise(wx, wz) * VOID_JITTER_AMPLITUDE;
                    float erosionSample  = rimErosionNoise.GetNoise(wx, wy, wz) * 0.5f + 0.5f; // 0..1
                    float rimErosion     = erosionSample * RIM_EROSION_AMPLITUDE * rimErosionFactor;
                    float jitteredRadius = effectiveRadius + voidJitter + rimErosion;

                    boolean isInVoid = radialDist < jitteredRadius;
                    if (isInVoid) {
                        data.setType(lx, ly, lz, VoxelType.AIR);
                        data.setDensity(lx, ly, lz, Math.min(data.getDensity(lx, ly, lz), -0.2f));
                        continue;
                    }

                    // Step 2 — solid fill with optional inner-face perturbation.
                    float distanceBeyondVoid  = radialDist - jitteredRadius;
                    boolean isInInnerFaceBand = distanceBeyondVoid <= INNER_FACE_BAND;
                    if (isInInnerFaceBand) {
                        float perturb           = wallPerturbNoise.GetNoise(wx, wy, wz) * WALL_PERTURB_AMPLITUDE;
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

    // ---------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------

    /** Standard smoothstep: 0 when x ≤ edge0, 1 when x ≥ edge1, smooth curve between. */
    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }
}