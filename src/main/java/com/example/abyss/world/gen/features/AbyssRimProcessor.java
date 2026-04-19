package com.example.abyss.world.gen.features;

import com.example.abyss.lib.FastNoiseLite;
import com.example.abyss.world.gen.ColumnVoxelEvaluator;
import com.example.abyss.world.gen.GenConfigResolver;
import com.example.abyss.world.gen.Layer;
import com.example.abyss.world.gen.LayerTransitionFeatureProcessor;
import com.example.abyss.world.voxel.VoxelData;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Post-process trim pass that removes overhanging lips at the Surface↔Edge boundary.
 * <p>
 * {@link SurfaceFeatureProcessor} stamps mountain geometry down to y=0 but only runs
 * on chunks whose centre is in the Surface layer.  {@link EdgeFeatureProcessor} carves
 * the void upward from y=0 but only runs on chunks whose centre is in the Edge layer.
 * In the chunk straddling y=0 (centre in Surface), the Edge void carve never fires —
 * leaving any solid voxels at the very bottom of the Surface layer that fall inside
 * the void radius as overhanging lips.
 * </p>
 * <p>
 * This processor replicates Edge's exact void carve formula for the bottom
 * {@value #TRIM_DEPTH} voxels of the Surface layer, using identical noise instances
 * (via {@link EdgeVoidGeometry}) so the carved edge is geometrically continuous with
 * what Edge produces just below y=0.  The trim is strictly bounded: the outer cap is
 * {@code innerSlopeStart} (= Edge shaft radius + {@link SurfaceFeatureProcessor#RIM_EROSION_AMPLITUDE_HEADROOM}),
 * which is the radius at which the Surface inner slope begins — nothing beyond that
 * is a lip, it is legitimate mountain terrain.
 * </p>
 * <p>
 * <b>Operating bounds</b> (enforced by {@link #shouldProcess}):
 * <ul>
 *   <li><b>Y:</b> {@code [SURFACE.getYEnd(), SURFACE.getYEnd() + TRIM_DEPTH]} only.</li>
 *   <li><b>XZ:</b> radial distance ≤ {@code innerSlopeStart} — derived at runtime from
 *       the resolver so debug overrides to the shaft diameter are respected.</li>
 * </ul>
 * </p>
 */
public class AbyssRimProcessor implements LayerTransitionFeatureProcessor, ColumnVoxelEvaluator {

    /**
     * How many voxels above y=0 the trim pass reaches.
     * Should be at least as large as {@link SurfaceFeatureProcessor}'s
     * {@code INNER_SLOPE_HEIGHT_BUMP} (40 voxels) to catch the worst-case overhang.
     */
    public static final int TRIM_DEPTH = 48;

    /**
     * Replicates {@link EdgeFeatureProcessor}'s void-jitter noise — same seed XOR,
     * type, and frequency via {@link EdgeVoidGeometry}.
     */
    private final FastNoiseLite voidJitterNoise;

    /**
     * Replicates {@link EdgeFeatureProcessor}'s rim-erosion noise — same seed XOR,
     * type, frequency, and octaves via {@link EdgeVoidGeometry}.
     */
    private final FastNoiseLite rimErosionNoise;

    public AbyssRimProcessor(long seed) {
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

    /** Immutable per-column state for the rim-trim zone evaluation. */
    private record RimColumnContext(
            float radialDist,
            float jitteredRadius,
            float innerSlopeStart,
            int rimY,
            int trimTop
    ) implements ColumnVoxelEvaluator.ColumnContext {}

    @Override
    public ColumnVoxelEvaluator.ColumnContext prepareColumn(int wx, int wz, GenConfigResolver resolver) {
        int   rimY            = Layer.SURFACE.getYEnd();
        int   trimTop         = rimY + TRIM_DEPTH;
        float voidRadiusAtRim = resolver.resolve(Layer.THE_EDGE.getYStart(), Layer.THE_EDGE).shaftDiameter() * 0.5f;
        float innerSlopeStart = resolveInnerSlopeStart(resolver);
        float radialDist      = (float) Math.sqrt((double) wx * wx + (double) wz * wz);
        // Mirror AbyssRimProcessor.process: rim erosion sampled at y=0 (XZ-stable at the rim).
        float voidJitter      = voidJitterNoise.GetNoise(wx, wz) * EdgeVoidGeometry.VOID_JITTER_AMPLITUDE;
        float erosionSample   = rimErosionNoise.GetNoise(wx, 0f, wz) * 0.5f + 0.5f;
        float rimErosion      = erosionSample * EdgeVoidGeometry.RIM_EROSION_AMPLITUDE;
        float jitteredRadius  = voidRadiusAtRim + voidJitter + rimErosion;
        return new RimColumnContext(radialDist, jitteredRadius, innerSlopeStart, rimY, trimTop);
    }

    /**
     * Returns {@link VoxelType#AIR} for voxels in the rim-trim band that fall inside
     * the jittered void radius, otherwise {@code null} (the caller handles them).
     * Mirrors the carve condition in {@link #process} exactly.
     */
    @Override
    public VoxelType evaluateVoxelType(int wx, int wy, int wz, ColumnVoxelEvaluator.ColumnContext ctx) {
        RimColumnContext rim     = (RimColumnContext) ctx;
        boolean isInTrimBand    = wy >= rim.rimY() && wy <= rim.trimTop();
        if (!isInTrimBand) {
            return null;
        }
        boolean isWithinLipZone = rim.radialDist() <= rim.innerSlopeStart();
        if (!isWithinLipZone) {
            return null;
        }
        boolean isInsideVoid = rim.radialDist() < rim.jitteredRadius();
        if (isInsideVoid) {
            return VoxelType.AIR;
        }
        return null;
    }

    // ---------------------------------------------------------------
    // LayerTransitionFeatureProcessor
    // ---------------------------------------------------------------

    @Override
    public boolean shouldProcess(int worldBaseX, int worldBaseY, int worldBaseZ, GenConfigResolver resolver) {
        int size    = VoxelData.SIZE;
        int rimY    = Layer.SURFACE.getYEnd();
        int trimTop = rimY + TRIM_DEPTH;

        boolean chunkOverlapsTrimBand = worldBaseY <= trimTop && (worldBaseY + size - 1) >= rimY;
        if (!chunkOverlapsTrimBand) {
            return false;
        }

        // The outer cap is innerSlopeStart — the radius at which the Surface slope begins.
        // Any column beyond this is mountain terrain, not a lip candidate.
        float innerSlopeStart    = resolveInnerSlopeStart(resolver);
        float chunkClosestX      = Math.max(worldBaseX, Math.min(0, worldBaseX + size - 1));
        float chunkClosestZ      = Math.max(worldBaseZ, Math.min(0, worldBaseZ + size - 1));
        float chunkMinRadialDist = (float) Math.sqrt((double) chunkClosestX * chunkClosestX
                                                   + (double) chunkClosestZ * chunkClosestZ);
        boolean chunkIsWithinLipZone = chunkMinRadialDist <= innerSlopeStart;
        return chunkIsWithinLipZone;
    }

    @Override
    public void process(VoxelData data, int worldBaseX, int worldBaseY, int worldBaseZ, GenConfigResolver resolver) {
        int size    = VoxelData.SIZE;
        int rimY    = Layer.SURFACE.getYEnd();
        int trimTop = rimY + TRIM_DEPTH;

        float voidRadiusAtRim = resolver.resolve(Layer.THE_EDGE.getYStart(), Layer.THE_EDGE).shaftDiameter() * 0.5f;

        // The outer cap: innerSlopeStart is where the Surface mountain slope begins.
        // Nothing beyond this radius is a lip — do not touch it.
        float innerSlopeStart = resolveInnerSlopeStart(resolver);

        for (int lz = 0; lz < size; lz++) {
            int wz = worldBaseZ + lz;
            for (int lx = 0; lx < size; lx++) {
                int wx = worldBaseX + lx;

                float radialDist = (float) Math.sqrt((double) wx * wx + (double) wz * wz);

                // Skip columns that are already beyond the lip zone entirely.
                boolean columnIsOutsideLipZone = radialDist > innerSlopeStart;
                if (columnIsOutsideLipZone) {
                    continue;
                }

                // Replicate Edge's exact void carve at rimErosionFactor = 1.0 (y=0 strength).
                float voidJitter     = voidJitterNoise.GetNoise(wx, wz) * EdgeVoidGeometry.VOID_JITTER_AMPLITUDE;
                float erosionSample  = rimErosionNoise.GetNoise(wx, 0f, wz) * 0.5f + 0.5f; // 0..1, same as Edge at wy=0
                float rimErosion     = erosionSample * EdgeVoidGeometry.RIM_EROSION_AMPLITUDE;
                float jitteredRadius = voidRadiusAtRim + voidJitter + rimErosion;

                for (int ly = 0; ly < size; ly++) {
                    int wy = worldBaseY + ly;

                    boolean isInTrimBand = wy >= rimY && wy <= trimTop;
                    if (!isInTrimBand) {
                        continue;
                    }

                    boolean isSolid = data.getType(lx, ly, lz) != VoxelType.AIR;
                    if (!isSolid) {
                        continue;
                    }

                    boolean isInsideVoid = radialDist < jitteredRadius;
                    if (isInsideVoid) {
                        data.setType(lx, ly, lz, VoxelType.AIR);
                        data.setDensity(lx, ly, lz, Math.min(data.getDensity(lx, ly, lz), -0.2f));
                    }
                }
            }
        }
    }

    private static float resolveInnerSlopeStart(GenConfigResolver resolver) {
        float edgeShaftRadius = resolver.resolve(Layer.THE_EDGE.getYStart(), Layer.THE_EDGE).shaftDiameter() * 0.5f;
        return edgeShaftRadius + SurfaceFeatureProcessor.RIM_EROSION_AMPLITUDE_HEADROOM;
    }
}