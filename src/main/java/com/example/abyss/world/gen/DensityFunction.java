package com.example.abyss.world.gen;

import com.example.abyss.lib.FastNoiseLite;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Evaluates the combined density function and assigns discrete {@link VoxelType}s
 * for the Abyss world.
 * <p>
 * This class owns all six noise instances and implements the three-term density
 * formula used during generation:
 * <pre>
 *   density = terrainNoise − shaftTerm + boundaryTerm − caveTerm
 * </pre>
 * It is intentionally stateless with respect to chunks — all methods are
 * pure functions of world coordinates and a {@link LayerGenConfig}.  The only
 * mutable state is the per-call {@code terrainNoise} frequency, which
 * {@link com.example.abyss.world.gen.ProceduralGenerator} manages externally
 * via {@link #setTerrainFrequency(float)}.
 * </p>
 */
public class DensityFunction {

    // ---------------------------------------------------------------
    // World-space constants
    // ---------------------------------------------------------------

    /** Margin (in voxels) over which the shaft edge fades via smoothstep. */
    static final float SHAFT_MARGIN = 30f;

    /**
     * Nominal radius of the abyss in voxels (soft horizontal boundary).
     * Terrain density increases sharply beyond this distance from centre,
     * forming impassable walls (~5 km radius at the surface).
     */
    static final float ABYSS_BOUNDARY_RADIUS = 5000f;

    /** Smoothstep margin for the boundary wall (in voxels). */
    static final float BOUNDARY_MARGIN = 200f;

    /** Cave noise threshold — voxels with cave noise above this are hollowed out. */
    static final float CAVE_THRESHOLD = 0.45f;

    /**
     * Density threshold below which a solid voxel is considered part of the
     * surface shell (exposed or near-exposed face). Types[0] from the layer's
     * {@code dominantVoxelTypes} is used here — typically dirt or the soft
     * surface material.
     */
    static final float SURFACE_SHELL_DEPTH = 0.35f;

    /**
     * Density threshold below which a solid voxel is in the mid-shell band.
     * Types[1] is used here when the layer has three or more types — typically
     * the transitional material such as dirt beneath a stone core.
     */
    static final float MID_SHELL_DEPTH = 0.75f;

    /** Hazard-pocket noise threshold — air voxels above this become hazardous. */
    static final float HAZARD_THRESHOLD = 0.55f;

    /** Waterline Y for the Oceania layer (everything below this is water if air). */
    static final int OCEANIA_WATERLINE_Y = -4600;

    /**
     * Waterline Y for the surface ocean that fills the outer perimeter beyond the abyss
     * boundary. Voxels in the SURFACE layer that are outside the boundary and below this Y
     * become WATER.
     */
    static final int SURFACE_SEA_LEVEL_Y = 48;

    // ---------------------------------------------------------------
    // Noise instances
    // ---------------------------------------------------------------

    private final FastNoiseLite terrainNoise;
    private final FastNoiseLite shaftNoise;
    private final FastNoiseLite typeNoise;
    private final FastNoiseLite hazardNoise;
    private final FastNoiseLite caveNoise;
    private final FastNoiseLite boundaryNoise;

    // ---------------------------------------------------------------
    // Construction
    // ---------------------------------------------------------------

    public DensityFunction(long seed) {
        terrainNoise  = buildNoise((int) seed,
                FastNoiseLite.NoiseType.OpenSimplex2, FastNoiseLite.FractalType.FBm, 4, -1f);
        shaftNoise    = buildNoise((int) (seed ^ 0xBEEF_CAFEL),
                FastNoiseLite.NoiseType.OpenSimplex2, null, -1, 0.005f);
        typeNoise     = buildNoise((int) (seed ^ 0xDEAD_FACEL),
                FastNoiseLite.NoiseType.OpenSimplex2, null, -1, 0.04f);
        hazardNoise   = buildNoise((int) (seed ^ 0xCAFE_BABEL),
                FastNoiseLite.NoiseType.OpenSimplex2, null, -1, 0.03f);
        caveNoise     = buildNoise((int) (seed ^ 0xF00D_BA11L),
                FastNoiseLite.NoiseType.OpenSimplex2, FastNoiseLite.FractalType.Ridged, 2, 0.006f);
        boundaryNoise = buildNoise((int) (seed ^ 0xABCD_1234L),
                FastNoiseLite.NoiseType.OpenSimplex2, null, -1, 0.003f);
    }

    // ---------------------------------------------------------------
    // Frequency control (called per Y-slice by ProceduralGenerator)
    // ---------------------------------------------------------------

    /**
     * Updates the terrain noise frequency.
     * {@link ProceduralGenerator} calls this once per Y-slice when the
     * resolved {@link LayerGenConfig#noiseFrequency()} changes, avoiding
     * redundant setter calls inside the hot voxel loop.
     */
    public void setTerrainFrequency(float frequency) {
        terrainNoise.SetFrequency(frequency);
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    /**
     * Computes the combined density value for a single voxel.
     * <p>
     * {@code density > 0} → solid; {@code density ≤ 0} → air/fluid.
     * </p>
     *
     * @param wx  world X
     * @param wy  world Y
     * @param wz  world Z
     * @param cfg resolved gen config for this Y position
     * @return continuous density value
     */
    public float evaluate(int wx, int wy, int wz, LayerGenConfig cfg) {
        float terrainDensity = terrainNoise.GetNoise(wx, wy, wz) * cfg.noiseAmplitude()
                + cfg.baseDensityThreshold();

        // Shaft term — radial distance from the centre axis (x=0, z=0)
        float shaftRadius          = cfg.shaftDiameter() / 2f;
        float radiusPerturbation   = shaftNoise.GetNoise(wx, wy, wz) * (shaftRadius * 0.25f);
        float effectiveRadius      = shaftRadius + radiusPerturbation;
        float horizontalDist       = (float) Math.sqrt((double) wx * wx + (double) wz * wz);
        float shaftTerm            = 1f - smoothstep(effectiveRadius - SHAFT_MARGIN,
                                                     effectiveRadius + SHAFT_MARGIN,
                                                     horizontalDist);

        // Boundary term — push density high beyond the abyss perimeter
        float boundaryPerturbation = boundaryNoise.GetNoise(wx, wy, wz) * (ABYSS_BOUNDARY_RADIUS * 0.1f);
        float effectiveBoundary    = ABYSS_BOUNDARY_RADIUS + boundaryPerturbation;
        float boundaryTerm         = smoothstep(effectiveBoundary - BOUNDARY_MARGIN,
                                                effectiveBoundary + BOUNDARY_MARGIN,
                                                horizontalDist);

        // Cave term — ridged noise carves tunnels through solid terrain
        float caveTerm = caveTerm(caveNoise.GetNoise(wx, wy, wz));

        return terrainDensity - shaftTerm + boundaryTerm - caveTerm;
    }

    /**
     * Assigns a discrete {@link VoxelType} to a voxel given its density and layer context.
     *
     * @param wx      world X
     * @param wy      world Y
     * @param wz      world Z
     * @param density density value already computed by {@link #evaluate}
     * @param cfg     resolved gen config for this Y position
     * @param layer   primary layer at {@code wy}
     * @return the voxel type to place
     */
    public VoxelType assignType(int wx, int wy, int wz, float density, LayerGenConfig cfg, Layer layer) {
        boolean isSolid = density > 0f;
        if (isSolid) {
            return pickSolidType(wx, wy, wz, density, cfg);
        }

        VoxelType surfaceWater = checkSurfaceOcean(wx, wy, wz, layer);
        if (surfaceWater != null) {
            return surfaceWater;
        }

        boolean isOceaniaWater = layer == Layer.OCEANIA && wy < OCEANIA_WATERLINE_Y;
        if (isOceaniaWater) {
            return VoxelType.WATER;
        }

        VoxelType hazard = checkHazardPocket(wx, wy, wz, cfg);
        if (hazard != null) {
            return hazard;
        }

        return VoxelType.AIR;
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

    /**
     * Picks the solid voxel type for this position using density as a proxy for
     * depth inside the solid mass.
     * <p>
     * The layer's {@code dominantVoxelTypes} are mapped to depth bands:
     * <ul>
     *   <li>types[0] — near-surface shell (low density, just above 0)</li>
     *   <li>types[1] — mid shell (medium density), when the array has ≥ 2 entries</li>
     *   <li>types[last] — deep core (high density), when the array has ≥ 2 entries</li>
     * </ul>
     * A small noise jitter is added to the thresholds so the band boundaries
     * are organic-looking rather than perfectly flat.
     * </p>
     */
    private VoxelType pickSolidType(int wx, int wy, int wz, float density, LayerGenConfig cfg) {
        VoxelType[] types = cfg.dominantVoxelTypes();
        if (types.length == 1) {
            return types[0];
        }

        // typeNoise in [-1,1]; scale it to a small fraction of the shell depths so the
        // band edges jitter naturally without completely inverting the layering order.
        float jitter = typeNoise.GetNoise(wx, wy, wz) * (SURFACE_SHELL_DEPTH * 0.4f);
        float effectiveSurfaceDepth = SURFACE_SHELL_DEPTH + jitter;
        boolean isInSurfaceShell    = density < effectiveSurfaceDepth;
        if (isInSurfaceShell) {
            return types[0];
        }

        if (types.length == 2) {
            return types[1];
        }

        float effectiveMidDepth = MID_SHELL_DEPTH + jitter;
        boolean isInMidShell    = density < effectiveMidDepth;
        if (isInMidShell) {
            return types[1];
        }

        return types[types.length - 1];
    }

    /**
     * Returns {@link VoxelType#WATER} if this air voxel sits in the surface outer ocean
     * (beyond the abyss boundary and below sea level), or {@code null} otherwise.
     */
    private VoxelType checkSurfaceOcean(int wx, int wy, int wz, Layer layer) {
        if (layer != Layer.SURFACE) {
            return null;
        }
        float horizontalDist       = (float) Math.sqrt((double) wx * wx + (double) wz * wz);
        float boundaryPerturbation = boundaryNoise.GetNoise(wx, wy, wz) * (ABYSS_BOUNDARY_RADIUS * 0.1f);
        float effectiveBoundary    = ABYSS_BOUNDARY_RADIUS + boundaryPerturbation;
        boolean isBeyondBoundary   = horizontalDist > effectiveBoundary;
        boolean isBelowSeaLevel    = wy <= SURFACE_SEA_LEVEL_Y;
        if (isBeyondBoundary && isBelowSeaLevel) {
            return VoxelType.WATER;
        }
        return null;
    }

    /**
     * Returns the layer's hazard type if the hazard noise exceeds the threshold,
     * or {@code null} if this voxel is plain air.
     */
    private VoxelType checkHazardPocket(int wx, int wy, int wz, LayerGenConfig cfg) {
        boolean hasHazard = cfg.hazardType() != null;
        if (!hasHazard) {
            return null;
        }
        float h = hazardNoise.GetNoise(wx, wy, wz);
        boolean isHazardPocket = h > HAZARD_THRESHOLD;
        if (isHazardPocket) {
            return cfg.hazardType();
        }
        return null;
    }

    /**
     * Maps a raw cave noise sample to a [0..1] carving strength.
     * Returns 0 below the threshold, ramps linearly to 1 at the noise maximum.
     */
    private static float caveTerm(float caveSample) {
        boolean isBelowThreshold = caveSample <= CAVE_THRESHOLD;
        if (isBelowThreshold) {
            return 0f;
        }
        return (caveSample - CAVE_THRESHOLD) / (1f - CAVE_THRESHOLD);
    }

    /** Standard smoothstep: 0 when x ≤ edge0, 1 when x ≥ edge1, smooth in between. */
    static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }

    // ---------------------------------------------------------------
    // Noise factory
    // ---------------------------------------------------------------

    private static FastNoiseLite buildNoise(int seed,
                                            FastNoiseLite.NoiseType noiseType,
                                            FastNoiseLite.FractalType fractalType,
                                            int octaves,
                                            float frequency) {
        FastNoiseLite noise = new FastNoiseLite(seed);
        noise.SetNoiseType(noiseType);
        if (fractalType != null) {
            noise.SetFractalType(fractalType);
            noise.SetFractalOctaves(octaves);
        }
        if (frequency > 0f) {
            noise.SetFrequency(frequency);
        }
        return noise;
    }
}
