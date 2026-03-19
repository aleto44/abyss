package com.example.abyss.world.gen.features;

import com.example.abyss.lib.FastNoiseLite;
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

    /** Radial distance at which the mountain ring begins to rise. */
    private static final float RING_INNER_RADIUS = 320f;
    /** Radial distance at which the ring reaches its maximum height. */
    private static final float RING_PEAK_RADIUS  = 650f;
    /** Radial distance at which the ring has fully faded back to flat. */
    private static final float RING_OUTER_RADIUS  = 1400f;

    /** Maximum height (in voxels) that mountains add above the layer floor. */
    private static final int MAX_MOUNTAIN_HEIGHT = 350;

    /**
     * ringStrength threshold above which the ridged peak detail is layered in.
     * Keeps sharp peaks confined to the actual ring zone.
     */
    private static final float RIDGE_DETAIL_THRESHOLD = 0.30f;

    /**
     * Weight of the ridged detail relative to the base height.
     * 0.35 adds up to 35% extra height variation at the peaks.
     */
    private static final float RIDGE_DETAIL_WEIGHT = 0.35f;

    /**
     * Where the inner slope begins — just clear of the density-function shaft edge.
     * Derived from the Surface layer's shaft diameter so it always tracks the shaft radius.
     */
    private static final float INNER_SLOPE_START =
            Layer.SURFACE.getGenConfig().shaftDiameter() * 0.5f + 10f;

    /**
     * Where the inner slope ends — matches RING_INNER_RADIUS so the ramp feeds
     * naturally into the base of the mountain ring.
     */
    private static final float INNER_SLOPE_END = RING_INNER_RADIUS;

    // ---- Noise ----

    /** Low-frequency FBm that defines the overall ring shape. */
    private final FastNoiseLite baseNoise;
    /** High-frequency ridged noise for jagged peak detail. */
    private final FastNoiseLite ridgedPeakNoise;

    @SuppressWarnings("unused") // seed intentionally ignored — surface uses fixed seeds for a shared world location
    public SurfaceFeatureProcessor(long seed) {
        // Fixed seeds — the surface is a shared static world location (future town hub),
        // so the mountain layout must be identical for every player regardless of world seed.
        baseNoise = new FastNoiseLite(0x53_55_52_46); // "SURF"
        baseNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        baseNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        baseNoise.SetFractalOctaves(4);
        baseNoise.SetFrequency(0.0018f);

        ridgedPeakNoise = new FastNoiseLite(0x52_49_44_47); // "RIDG"
        ridgedPeakNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        ridgedPeakNoise.SetFractalType(FastNoiseLite.FractalType.Ridged);
        ridgedPeakNoise.SetFractalOctaves(4);
        ridgedPeakNoise.SetFrequency(0.012f);
    }

    @Override
    public void process(VoxelData data, int worldBaseX, int worldBaseY, int worldBaseZ) {
        int size = VoxelData.SIZE;
        int layerFloor = Layer.SURFACE.getYEnd(); // == 0

        // If this chunk sits entirely below the Surface layer floor it has nothing to do.
        boolean chunkIsEntirelyBelowSurface = (worldBaseY + size - 1) < layerFloor;
        if (chunkIsEntirelyBelowSurface) {
            return;
        }

        for (int lz = 0; lz < size; lz++) {
            int wz = worldBaseZ + lz;
            for (int lx = 0; lx < size; lx++) {
                int wx = worldBaseX + lx;

                int targetHeight = computeColumnHeight(wx, wz);
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
    private int computeColumnHeight(int wx, int wz) {
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
        float innerSlopeFactor = innerSlopeMultiplier(radialDist);
        float finalHeight      = baseHeight * innerSlopeFactor;

        int heightVoxels   = Math.round(finalHeight);
        int layerFloor     = Layer.SURFACE.getYEnd();
        int layerCeiling   = Layer.SURFACE.getYStart();
        int clampedHeight  = Math.max(0, Math.min(layerCeiling - layerFloor, heightVoxels));
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
    private static float innerSlopeMultiplier(float r) {
        boolean isInsideShaft = r <= INNER_SLOPE_START;
        if (isInsideShaft) {
            return 0f;
        }
        boolean isPastSlope = r >= INNER_SLOPE_END;
        if (isPastSlope) {
            return 1f;
        }
        return smoothstep(INNER_SLOPE_START, INNER_SLOPE_END, r);
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