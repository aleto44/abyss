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
     * Radial distance (voxels from world origin) at which the mountain ring begins to rise.
     * Also serves as INNER_SLOPE_END — the point where the inner slope finishes and terrain
     * reaches full height, feeding naturally into the ring foothills.
     * Lower → ring starts closer to the shaft, smaller flat zone; must stay > INNER_SLOPE_START.
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
     * Minimum ringStrength value required before ridged peak detail is applied.
     * Range: 0.0–1.0 (ringStrength is the output of {@link #ringEnvelope}).
     * Lower → ridged jaggedness bleeds further down the slopes and into the foothills.
     * Higher → sharp peaks are confined to a tighter band around the ring crest.
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
     * World-space radius at which the inner slope begins.
     * <p>
     * Computed at runtime from the resolver rather than stored as a static field, so
     * debug overrides to {@code THE_EDGE}'s shaft diameter are respected.
     * Formula: {@code THE_EDGE} shaft radius + {@code RIM_EROSION_AMPLITUDE_HEADROOM},
     * keeping the slope start beyond the furthest the rim erosion can reach.
     * </p>
     * Lower → slope starts closer to the shaft; risk of flat ground over the void.
     * Higher → slope starts further out, eating into the flat zone near the ring base.
     */
    private static float innerSlopeStart(GenConfigResolver resolver) {
        float edgeShaftRadius = resolver.resolve(Layer.THE_EDGE.getYStart(), Layer.THE_EDGE).shaftDiameter() * 0.5f;
        return edgeShaftRadius + RIM_EROSION_AMPLITUDE_HEADROOM;
    }

    /**
     * Headroom added to the Edge shaft radius when computing the inner slope start.
     * Matches RIM_EROSION_AMPLITUDE in EdgeFeatureProcessor — keeps the slope start
     * beyond the furthest the erosion can reach.
     * If RIM_EROSION_AMPLITUDE changes, update this value to match.
     */
    private static final float RIM_EROSION_AMPLITUDE_HEADROOM = 20f;

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

        // Step 2 — ridged peak detail, only inside the ring zone
        boolean inRingZone = ringStrength > RIDGE_DETAIL_THRESHOLD;
        if (inRingZone) {
            float ridgeDetail = ridgedPeakNoise.GetNoise(wx, wz) * 0.5f + 0.5f; // 0..1
            baseHeight += ridgeDetail * ringStrength * MAX_MOUNTAIN_HEIGHT * RIDGE_DETAIL_WEIGHT;
        }

        // Step 3 — inner slope carve toward the shaft
        float innerSlopeFactor = innerSlopeMultiplier(radialDist, innerSlopeStart);
        float finalHeight      = baseHeight * innerSlopeFactor;

        int heightVoxels  = Math.round(finalHeight);
        int layerFloor    = Layer.SURFACE.getYEnd();
        int layerCeiling  = Layer.SURFACE.getYStart();
        int clampedHeight = Math.max(0, Math.min(layerCeiling - layerFloor, heightVoxels));
        return layerFloor + clampedHeight;
    }

    /**
     * Radial envelope that shapes heights into a ring: 0 inside the shaft, rises
     * smoothly to 1 at the peak radius, then fades back to 0 at the outer edge.
     */
    private static float ringEnvelope(float r) {
        float rise = smoothstep(RING_INNER_RADIUS, RING_PEAK_RADIUS, r);
        float fade = smoothstep(RING_PEAK_RADIUS,  RING_OUTER_RADIUS, r);
        return rise * (1f - fade);
    }

    /**
     * Multiplier in [0..1] that carves the steep inner slope between the shaft
     * opening and the foot of the ring.  Uses a smoothstep so the drop is smooth
     * rather than a hard cliff.
     */
    private static float innerSlopeMultiplier(float r, float innerSlopeStart) {
        boolean isInsideShaft = r <= innerSlopeStart;
        if (isInsideShaft) {
            return 0f;
        }
        boolean isPastSlope = r >= RING_INNER_RADIUS;
        if (isPastSlope) {
            return 1f;
        }
        return smoothstep(innerSlopeStart, RING_INNER_RADIUS, r);
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