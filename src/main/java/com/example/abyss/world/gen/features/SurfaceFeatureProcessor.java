package com.example.abyss.world.gen.features;

import com.example.abyss.lib.FastNoiseLite;
import com.example.abyss.world.gen.GenConfigResolver;
import com.example.abyss.world.gen.Layer;
import com.example.abyss.world.gen.LayerFeatureProcessor;
import com.example.abyss.world.voxel.VoxelData;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Generates the circular mountain ring that surrounds the abyss on the Surface layer.
 * <p>
 * Implements the four spec steps in order:
 * <ol>
 *   <li>Ring-biased base heightmap — low-freq FBm shaped by a radial envelope.</li>
 *   <li>Ridged peak detail — high-freq ridged noise added only where ringStrength is high.</li>
 *   <li>Inner slope carve — exponential falloff from the ring inner edge down to the shaft,
 *       producing the dramatic inward drop toward the cliff.</li>
 *   <li>Solid column fill — each column is filled solid from the layer floor to the
 *       computed height, air above.</li>
 * </ol>
 * No vegetation, plateau, or shrub logic — those are deprecated.
 * </p>
 */
public class SurfaceFeatureProcessor implements LayerFeatureProcessor {

    // ---- Ring geometry ----

    /**
     * Radial distance (voxels) at which the ring envelope begins to rise.
     * This is pulled back inside the slope zone so the ring foothills already have some
     * height while the inner slope multiplier is still ramping up — giving the slope
     * actual terrain to carve rather than multiplying zero and producing flat ground
     * followed by a sudden jump.
     * Must be less than RING_INNER_RADIUS by at least the width of the slope zone.
     */
    private static final float RING_RISE_START = 200f;

    /**
     * Radial distance (voxels from world origin) at which the inner slope finishes and
     * the ring foothills take over.  The ring envelope reaches full strength at RING_PEAK_RADIUS,
     * so terrain continues rising smoothly past this point.
     * Lower → ring inner edge closer to the shaft; smaller flat zone.
     * Higher → wider flat zone between the shaft lip and the ring base.
     */
    private static final float RING_INNER_RADIUS = 380f;

    /**
     * Radial distance at which the ring reaches its maximum height.
     * Lower → peak band is closer to the shaft, tighter ring.
     * Higher → peak band is pushed farther out; combine with RING_OUTER_RADIUS to keep
     *           the ring from becoming too thin.
     */
    private static final float RING_PEAK_RADIUS  = 650f;

    /**
     * Radial distance at which the ring has fully faded back to flat terrain.
     * Lower → ring is narrow and drops off quickly past the peaks.
     * Higher → ring has a long, gradual outer slope; terrain stays elevated farther out.
     */
    private static final float RING_OUTER_RADIUS  = 1000f;

    /**
     * Hard cap on mountain height, in voxels above the layer floor.
     * Lower → shorter mountains overall; the ring feels more like rolling hills.
     * Higher → taller, more dramatic peaks; make sure the layer ceiling has headroom.
     */
    private static final int MAX_MOUNTAIN_HEIGHT = 350;

    /**
     * Minimum ringStrength value at which ridged peak detail begins to blend in.
     * Range: 0.0–1.0. Detail reaches full weight at RIDGE_DETAIL_THRESHOLD + 0.2.
     * Lower → jaggedness starts appearing further down the slope toward the shaft.
     * Higher → sharp crests are confined to a tighter band around the ring peak.
     */
    private static final float RIDGE_DETAIL_THRESHOLD = 0.30f;

    /**
     * How much extra height the ridged detail layer can add on top of the base height,
     * expressed as a fraction of MAX_MOUNTAIN_HEIGHT.
     * Lower → smoother, more rounded peaks (less jagged variation at the crest).
     * Higher → more extreme height spikes at the peaks; can look very craggy.
     * At 0.35, the ridged layer can add up to 35% extra height at full ringStrength.
     */
    private static final float RIDGE_DETAIL_WEIGHT = 0.35f;

    /**
     * Headroom added to the Edge shaft radius when computing the inner slope start.
     * Matches RIM_EROSION_AMPLITUDE in EdgeFeatureProcessor — keeps the slope start
     * beyond the furthest the erosion can reach.
     * If RIM_EROSION_AMPLITUDE changes, update this value to match.
     */
    private static final float RIM_EROSION_AMPLITUDE_HEADROOM = 20f;

    /**
     * Maximum radial distance (voxels) that the inner slope start and end boundaries
     * can be displaced outward or inward by the slope variation noise.
     * Lower → subtle wobble; slope boundary stays close to a perfect circle.
     * Higher → dramatic lobes and bays; some columns stay elevated much closer to the shaft.
     */
    private static final float INNER_SLOPE_VARIATION_AMPLITUDE = 60f;

    /**
     * Maximum additional height (voxels) that the slope variation noise can add inside
     * the slope zone, creating rocky knolls and ledges partway down the inner face.
     * Lower → slope is non-circular but smooth.
     * Higher → noticeable elevated patches and ridges on the inner slope face.
     */
    private static final float INNER_SLOPE_HEIGHT_BUMP = 40f;

    // ---- Noise ----

    /**
     * Low-frequency FBm noise that drives the broad mountain shape.
     * Frequency (0.0018): lower → bigger, smoother blobs; higher → smaller, more chaotic hills.
     * Octaves (4): more octaves add finer detail but cost more compute.
     */
    private final FastNoiseLite baseNoise;

    /**
     * High-frequency ridged noise for jagged crest detail, applied only inside the ring zone.
     * Frequency (0.012): lower → wider ridges; higher → tighter, more numerous spikes.
     * Octaves (4): more octaves add finer crinkle detail to the ridges.
     */
    private final FastNoiseLite ridgedPeakNoise;

    /**
     * Medium-frequency FBm noise that breaks the circular symmetry of the inner slope.
     * Shifts the effective slope-start and slope-end radii per-column and adds small
     * height bumps, so the descent toward the shaft looks like real eroded terrain
     * rather than a uniform radial ramp.
     * Frequency (0.004): lower → broader lobes; higher → tighter, choppier variation.
     * Octaves (3): enough detail for naturalistic variation without over-complicating the shape.
     */
    private final FastNoiseLite slopeVariationNoise;

    @SuppressWarnings("unused") // seed intentionally ignored — surface uses fixed seeds for a shared world location
    public SurfaceFeatureProcessor(long seed) {
        // Fixed seeds — the surface is a shared static world location (future town hub),
        // so the mountain layout must be identical for every player regardless of world seed.
        baseNoise = new FastNoiseLite(0x53_55_52_46); // "SURF"
        baseNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        baseNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        baseNoise.SetFractalOctaves(4);
        baseNoise.SetFrequency(0.0018f); // lower = larger smooth features; higher = smaller noisy bumps

        ridgedPeakNoise = new FastNoiseLite(0x52_49_44_47); // "RIDG"
        ridgedPeakNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        ridgedPeakNoise.SetFractalType(FastNoiseLite.FractalType.Ridged);
        ridgedPeakNoise.SetFractalOctaves(4);
        ridgedPeakNoise.SetFrequency(0.002f); // lower = wider ridges; higher = tighter/more frequent spikes

        slopeVariationNoise = new FastNoiseLite(0x53_4C_50_56); // "SLPV"
        slopeVariationNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        slopeVariationNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        slopeVariationNoise.SetFractalOctaves(3);
        slopeVariationNoise.SetFrequency(0.004f);
    }

    private static float innerSlopeStart(GenConfigResolver resolver) {
        float edgeShaftRadius = resolver.resolve(Layer.THE_EDGE.getYStart(), Layer.THE_EDGE).shaftDiameter() * 0.5f;
        return edgeShaftRadius + RIM_EROSION_AMPLITUDE_HEADROOM;
    }

    @Override
    public void process(VoxelData data, int worldBaseX, int worldBaseY, int worldBaseZ, GenConfigResolver resolver) {
        int size = VoxelData.SIZE;
        int layerFloor = Layer.SURFACE.getYEnd();

        boolean chunkIsEntirelyBelowSurface = (worldBaseY + size - 1) < layerFloor;
        if (chunkIsEntirelyBelowSurface) {
            return;
        }

        float innerSlopeStart = innerSlopeStart(resolver);

        for (int lz = 0; lz < size; lz++) {
            int wz = worldBaseZ + lz;
            for (int lx = 0; lx < size; lx++) {
                int wx = worldBaseX + lx;

                int targetHeight = computeColumnHeight(wx, wz, innerSlopeStart);
                fillColumn(data, lx, lz, worldBaseY, targetHeight, layerFloor);
            }
        }
    }

    // ---------------------------------------------------------------
    // Height computation — spec steps 1–3
    // ---------------------------------------------------------------

    /**
     * Computes the world-Y height that this XZ column should be filled up to.
     * Returns a value in [Layer.SURFACE.getYEnd(), Layer.SURFACE.getYStart()].
     */
    private int computeColumnHeight(int wx, int wz, float innerSlopeStart) {
        float radialDist = (float) Math.sqrt((double) wx * wx + (double) wz * wz);

        // Step 1 — ring-biased base height
        float ringStrength = ringEnvelope(radialDist);
        float base         = (baseNoise.GetNoise(wx, wz) * 0.5f + 0.5f); // 0..1
        float baseHeight   = base * ringStrength * MAX_MOUNTAIN_HEIGHT;

        // Step 2 — ridged peak detail, blended in smoothly past the threshold.
        // smoothstep instead of a hard boolean prevents a visible height-step at the
        // exact radius where ridgeDetail would otherwise snap on all at once.
        float ridgeBlend = smoothstep(RIDGE_DETAIL_THRESHOLD, RIDGE_DETAIL_THRESHOLD + 0.2f, ringStrength);
        boolean hasRidgeContribution = ridgeBlend > 0f;
        if (hasRidgeContribution) {
            float ridgeDetail = ridgedPeakNoise.GetNoise(wx, wz) * 0.5f + 0.5f; // 0..1
            baseHeight += ridgeDetail * ringStrength * MAX_MOUNTAIN_HEIGHT * RIDGE_DETAIL_WEIGHT * ridgeBlend;
        }

        // Step 3 — inner slope carve toward the shaft, with per-column variation to
        // break the perfect circular symmetry of the slope boundary.
        float slopeVariation   = slopeVariationNoise.GetNoise(wx, wz);                    // -1..1
        float slopeBumpNoise   = slopeVariationNoise.GetNoise(wx * 1.7f, wz * 1.7f);     // -1..1, uncorrelated detail
        float variedSlopeStart = innerSlopeStart + slopeVariation * INNER_SLOPE_VARIATION_AMPLITUDE;
        float variedSlopeEnd   = RING_INNER_RADIUS + slopeVariation * (INNER_SLOPE_VARIATION_AMPLITUDE * 0.5f);
        float innerSlopeFactor = innerSlopeMultiplier(radialDist, variedSlopeStart, variedSlopeEnd);
        // Height bump peaks mid-slope and fades to zero at either end of the transition zone.
        float slopeZoneWeight  = 1f - Math.abs(innerSlopeFactor - 0.5f) * 2f;
        float heightBump       = (slopeBumpNoise * 0.5f + 0.5f) * slopeZoneWeight * INNER_SLOPE_HEIGHT_BUMP;
        float finalHeight      = baseHeight * innerSlopeFactor + heightBump;

        int heightVoxels  = Math.round(finalHeight);
        int layerFloor    = Layer.SURFACE.getYEnd();
        int layerCeiling  = Layer.SURFACE.getYStart();
        int clampedHeight = Math.max(0, Math.min(layerCeiling - layerFloor, heightVoxels));
        return layerFloor + clampedHeight;
    }

    /**
     * Radial envelope that shapes heights into a ring: 0 near the shaft, rises
     * smoothly from RING_RISE_START to full strength at RING_PEAK_RADIUS, then
     * fades back to 0 at the outer edge. Rising earlier than RING_INNER_RADIUS
     * ensures the slope zone has real base height to carve into rather than zero.
     */
    private static float ringEnvelope(float r) {
        float rise = smoothstep(RING_RISE_START, RING_PEAK_RADIUS, r);
        float fade = smoothstep(RING_PEAK_RADIUS, RING_OUTER_RADIUS, r);
        return rise * (1f - fade);
    }

    /**
     * Multiplier in [0..1] that carves the steep inner slope between the shaft
     * opening and the foot of the ring. Both boundary radii are passed in so
     * callers can perturb them per-column for naturalistic variation.
     */
    private static float innerSlopeMultiplier(float r, float slopeStart, float slopeEnd) {
        boolean isInsideShaft = r <= slopeStart;
        if (isInsideShaft) {
            return 0f;
        }
        boolean isPastSlope = r >= slopeEnd;
        if (isPastSlope) {
            return 1f;
        }
        return smoothstep(slopeStart, slopeEnd, r);
    }

    // ---------------------------------------------------------------
    // Column fill — spec step 4
    // ---------------------------------------------------------------

    /**
     * Stamps the correct solid/air state into this XZ column for all voxels at or above
     * the Surface layer floor.
     * <p>
     * The density function fills the entire Surface layer with noisy terrain, so this
     * processor must override every voxel above {@code layerFloor} — not just mountain columns.
     * Columns with no mountain height are cleared to air (opening the interior void and outer
     * flat zone). Voxels below {@code layerFloor} are never touched (they belong to the Edge
     * layer and were filled by the density function).
     * </p>
     */
    private static void fillColumn(VoxelData data, int lx, int lz, int worldBaseY, int targetWorldY, int layerFloor) {
        int size = VoxelData.SIZE;
        // targetWorldY == layerFloor means zero mountain height — clear everything above the floor to air.
        boolean hasNoMountain = targetWorldY <= layerFloor;
        for (int ly = 0; ly < size; ly++) {
            int wy = worldBaseY + ly;
            boolean isBelowLayerFloor = wy < layerFloor;
            if (isBelowLayerFloor) {
                continue; // belongs to the Edge layer — never touch
            }
            boolean isSolidMountain = !hasNoMountain && wy <= targetWorldY;
            if (isSolidMountain) {
                data.setType(lx, ly, lz, VoxelType.STONE);
                if (data.getDensity(lx, ly, lz) <= 0f) {
                    data.setDensity(lx, ly, lz, 0.40f);
                }
            } else {
                data.setType(lx, ly, lz, VoxelType.AIR);
                data.setDensity(lx, ly, lz, Math.min(data.getDensity(lx, ly, lz), -0.2f));
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
