package com.example.abyss.world.gen;

import com.example.abyss.lib.FastNoiseLite;

/**
 * Evaluates the combined density function for the Abyss world.
 * <p>
 * Owns the four noise instances that drive the density formula:
 * <pre>
 *   density = terrainNoise − shaftTerm + boundaryTerm − caveTerm
 * </pre>
 * Discrete voxel-type assignment is handled separately by {@link VoxelTypeResolver}.
 * </p>
 * <p>
 * The two cache-population methods ({@link #populateColumnCache},
 * {@link #populateLerpCache}) prepare reusable scratch buffers so that the
 * hot voxel loop in {@link ProceduralGenerator} can call {@link #evaluateCached}
 * with only cheap interpolation arithmetic instead of full noise evaluations.
 * </p>
 */
public class DensityFunction {

    // ---------------------------------------------------------------
    // World-space constants
    // ---------------------------------------------------------------

    /** Margin (in voxels) over which the shaft edge fades via smoothstep. */
    public static final float SHAFT_MARGIN = 30f;

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

    // ---------------------------------------------------------------
    // Noise instances
    // ---------------------------------------------------------------

    private final FastNoiseLite terrainNoise;
    private final FastNoiseLite shaftNoise;
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
    // XZ column cache
    // ---------------------------------------------------------------

    /**
     * Pre-computes all XZ-only noise terms for an entire chunk face into {@code cache}.
     * Call once per chunk before the Y loop, then use {@link #evaluateCached} inside
     * the inner loop.
     * <p>
     * Also populates {@code typeJitter} used by {@link VoxelTypeResolver} — the column
     * cache is shared between both classes because both consume XZ-stable values.
     * </p>
     *
     * @param worldBaseX chunk origin world X (chunkX * 32)
     * @param worldBaseZ chunk origin world Z (chunkZ * 32)
     * @param cfg        representative config for this chunk (centre Y)
     * @param cache      scratch object owned by the calling thread
     */
    public void populateColumnCache(int worldBaseX, int worldBaseZ,
                                    LayerGenConfig cfg, ColumnNoiseCache cache) {
        float shaftRadius = cfg.shaftDiameter() / 2f;

        for (int lz = 0; lz < ColumnNoiseCache.SIZE; lz++) {
            int wz = worldBaseZ + lz;
            for (int lx = 0; lx < ColumnNoiseCache.SIZE; lx++) {
                int wx = worldBaseX + lx;
                int i  = ColumnNoiseCache.idx(lx, lz);

                float dist = (float) Math.sqrt((double) wx * wx + (double) wz * wz);
                cache.horizontalDist[i] = dist;

                // Shaft — sample at wy=0; across 32 voxels at freq 0.005 the Y variation
                // is 0.16 noise-units, visually indistinguishable from the XZ-only value.
                float radiusPerturbation = shaftNoise.GetNoise(wx, 0f, wz) * (shaftRadius * 0.25f);
                float effectiveRadius    = shaftRadius + radiusPerturbation;
                cache.shaftTerm[i] = 1f - smoothstep(effectiveRadius - SHAFT_MARGIN,
                                                      effectiveRadius + SHAFT_MARGIN,
                                                      dist);

                // Boundary — same rationale, freq 0.003.
                float boundaryPerturbation = boundaryNoise.GetNoise(wx, 0f, wz)
                                             * (ABYSS_BOUNDARY_RADIUS * 0.1f);
                float effBoundary          = ABYSS_BOUNDARY_RADIUS + boundaryPerturbation;
                cache.effectiveBoundary[i] = effBoundary;
                cache.boundaryTerm[i]      = smoothstep(effBoundary - BOUNDARY_MARGIN,
                                                        effBoundary + BOUNDARY_MARGIN,
                                                        dist);

                // typeJitter is consumed by VoxelTypeResolver — populated here because
                // typeNoise lives there and this is already the XZ sweep.
                // Left as zero; VoxelTypeResolver.populateTypeJitter fills it separately.
            }
        }
    }

    // ---------------------------------------------------------------
    // Trilinear lerp cache
    // ---------------------------------------------------------------

    /**
     * Samples terrain and cave noise on a coarse 4-voxel grid and stores the results
     * in {@code lerpCache}. Call once per chunk before the voxel loop.
     *
     * @param worldBaseX chunk origin world X
     * @param worldBaseY chunk origin world Y
     * @param worldBaseZ chunk origin world Z
     * @param resolver   used to look up per-Y {@link LayerGenConfig}
     * @param lerpCache  scratch object owned by the calling thread
     */
    public void populateLerpCache(int worldBaseX, int worldBaseY, int worldBaseZ,
                                  GenConfigResolver resolver, DensityLerpCache lerpCache) {
        float lastFrequency = -1f;

        for (int iy = 0; iy < DensityLerpCache.NODES; iy++) {
            int ly = iy * DensityLerpCache.STEP;
            int wy = worldBaseY + ly;

            Layer layer        = Layer.forY(wy);
            LayerGenConfig cfg = resolver.resolve(wy, layer);

            float freq = cfg.noiseFrequency();
            if (freq != lastFrequency) {
                terrainNoise.SetFrequency(freq);
                lastFrequency = freq;
            }

            float amplitude = cfg.noiseAmplitude();
            float threshold = cfg.baseDensityThreshold();

            for (int iz = 0; iz < DensityLerpCache.NODES; iz++) {
                int lz = iz * DensityLerpCache.STEP;
                int wz = worldBaseZ + lz;
                for (int ix = 0; ix < DensityLerpCache.NODES; ix++) {
                    int lx = ix * DensityLerpCache.STEP;
                    int wx = worldBaseX + lx;
                    int i  = DensityLerpCache.idx(ix, iy, iz);

                    lerpCache.terrain[i] = terrainNoise.GetNoise(wx, wy, wz) * amplitude + threshold;
                    lerpCache.cave[i]    = caveTerm(caveNoise.GetNoise(wx, wy, wz));
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Cached hot-path (use inside the voxel loop)
    // ---------------------------------------------------------------

    /**
     * Density evaluation using pre-computed column cache (shaft/boundary/sqrt) and
     * trilinear interpolation of terrain and cave noise from the lerp cache.
     *
     * @param colCache  populated by {@link #populateColumnCache}
     * @param lerpCache populated by {@link #populateLerpCache}
     * @param lx        local X in chunk (0–31)
     * @param ly        local Y in chunk (0–31)
     * @param lz        local Z in chunk (0–31)
     */
    public float evaluateCached(ColumnNoiseCache colCache, DensityLerpCache lerpCache,
                                int lx, int ly, int lz) {
        int colIdx = ColumnNoiseCache.idx(lx, lz);

        float terrainDensity = trilinear(lerpCache.terrain, lx, ly, lz);
        float caveTerm       = trilinear(lerpCache.cave,    lx, ly, lz);

        return terrainDensity - colCache.shaftTerm[colIdx] + colCache.boundaryTerm[colIdx] - caveTerm;
    }

    // ---------------------------------------------------------------
    // Uncached overload (preserved for tests and one-off callers)
    // ---------------------------------------------------------------

    /**
     * Computes the combined density value for a single voxel.
     * {@code density > 0} → solid; {@code density ≤ 0} → air/fluid.
     *
     * @param wx  world X
     * @param wy  world Y
     * @param wz  world Z
     * @param cfg resolved gen config for this Y position
     */
    public float evaluate(int wx, int wy, int wz, LayerGenConfig cfg) {
        float terrainDensity = terrainNoise.GetNoise(wx, wy, wz) * cfg.noiseAmplitude()
                + cfg.baseDensityThreshold();

        float shaftRadius          = cfg.shaftDiameter() / 2f;
        float radiusPerturbation   = shaftNoise.GetNoise(wx, wy, wz) * (shaftRadius * 0.25f);
        float effectiveRadius      = shaftRadius + radiusPerturbation;
        float horizontalDist       = (float) Math.sqrt((double) wx * wx + (double) wz * wz);
        float shaftTerm            = 1f - smoothstep(effectiveRadius - SHAFT_MARGIN,
                                                     effectiveRadius + SHAFT_MARGIN,
                                                     horizontalDist);

        float boundaryPerturbation = boundaryNoise.GetNoise(wx, wy, wz) * (ABYSS_BOUNDARY_RADIUS * 0.1f);
        float effectiveBoundary    = ABYSS_BOUNDARY_RADIUS + boundaryPerturbation;
        float boundaryTerm         = smoothstep(effectiveBoundary - BOUNDARY_MARGIN,
                                                effectiveBoundary + BOUNDARY_MARGIN,
                                                horizontalDist);

        float caveTerm = caveTerm(caveNoise.GetNoise(wx, wy, wz));

        return terrainDensity - shaftTerm + boundaryTerm - caveTerm;
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

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

    /**
     * Trilinear interpolation of a {@link DensityLerpCache} sample grid.
     *
     * @param grid flat array indexed by {@link DensityLerpCache#idx}
     * @param lx   local X voxel (0–31)
     * @param ly   local Y voxel (0–31)
     * @param lz   local Z voxel (0–31)
     */
    private static float trilinear(float[] grid, int lx, int ly, int lz) {
        int step = DensityLerpCache.STEP;

        int ix0 = lx / step;
        int iy0 = ly / step;
        int iz0 = lz / step;
        int ix1 = ix0 + 1;
        int iy1 = iy0 + 1;
        int iz1 = iz0 + 1;

        float tx = (float) (lx % step) / step;
        float ty = (float) (ly % step) / step;
        float tz = (float) (lz % step) / step;

        float c000 = grid[DensityLerpCache.idx(ix0, iy0, iz0)];
        float c100 = grid[DensityLerpCache.idx(ix1, iy0, iz0)];
        float c010 = grid[DensityLerpCache.idx(ix0, iy1, iz0)];
        float c110 = grid[DensityLerpCache.idx(ix1, iy1, iz0)];
        float c001 = grid[DensityLerpCache.idx(ix0, iy0, iz1)];
        float c101 = grid[DensityLerpCache.idx(ix1, iy0, iz1)];
        float c011 = grid[DensityLerpCache.idx(ix0, iy1, iz1)];
        float c111 = grid[DensityLerpCache.idx(ix1, iy1, iz1)];

        float c00 = c000 + tx * (c100 - c000);
        float c10 = c010 + tx * (c110 - c010);
        float c01 = c001 + tx * (c101 - c001);
        float c11 = c011 + tx * (c111 - c011);

        float c0 = c00 + ty * (c10 - c00);
        float c1 = c01 + ty * (c11 - c01);

        return c0 + tz * (c1 - c0);
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