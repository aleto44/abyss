package com.example.abyss.world.gen;

import com.example.abyss.lib.FastNoiseLite;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Resolves the discrete {@link VoxelType} for a voxel given its density and layer context.
 * <p>
 * This class is responsible solely for the type-assignment pipeline:
 * <ul>
 *   <li>Solid voxels → shell-band type selection via {@code typeNoise} jitter</li>
 *   <li>Air voxels in the SURFACE layer beyond the abyss boundary → WATER (surface ocean)</li>
 *   <li>Air voxels in OCEANIA below the waterline → WATER</li>
 *   <li>Everything else → AIR</li>
 * </ul>
 * It owns {@code typeNoise} and all shell-depth constants.
 * It has no knowledge of the density formula — that lives in {@link DensityFunction}.
 * </p>
 */
public class VoxelTypeResolver {

    // ---------------------------------------------------------------
    // Shell-depth and type constants
    // ---------------------------------------------------------------

    static final float SURFACE_SHELL_DEPTH = 0.35f;
    static final float MID_SHELL_DEPTH     = 0.75f;
    static final int   OCEANIA_WATERLINE_Y = -4600;
    static final int   SURFACE_SEA_LEVEL_Y = 48;

    // ---------------------------------------------------------------
    // Noise instances
    // ---------------------------------------------------------------

    private final FastNoiseLite typeNoise;

    // ---------------------------------------------------------------
    // Construction
    // ---------------------------------------------------------------

    public VoxelTypeResolver(long seed) {
        typeNoise = buildNoise((int) (seed ^ 0xDEAD_FACEL), 0.04f);
    }

    // ---------------------------------------------------------------
    // Column cache population
    // ---------------------------------------------------------------

    /**
     * Fills the {@code typeJitter} slot of the shared {@link ColumnNoiseCache}.
     * Call once per chunk alongside {@link DensityFunction#populateColumnCache},
     * before the voxel loop.
     *
     * @param worldBaseX chunk origin world X (chunkX * 32)
     * @param worldBaseZ chunk origin world Z (chunkZ * 32)
     * @param cache      the same cache passed to {@link DensityFunction#populateColumnCache}
     */
    public void populateTypeJitter(int worldBaseX, int worldBaseZ, ColumnNoiseCache cache) {
        for (int lz = 0; lz < ColumnNoiseCache.SIZE; lz++) {
            int wz = worldBaseZ + lz;
            for (int lx = 0; lx < ColumnNoiseCache.SIZE; lx++) {
                int wx = worldBaseX + lx;
                int i  = ColumnNoiseCache.idx(lx, lz);
                cache.typeJitter[i] = typeNoise.GetNoise(wx, 0f, wz) * (SURFACE_SHELL_DEPTH * 0.4f);
            }
        }
    }

    // ---------------------------------------------------------------
    // Cached hot-path (use inside the per-voxel loop)
    // ---------------------------------------------------------------

    /**
     * Resolves the voxel type using pre-computed XZ column values from
     * {@link DensityFunction#populateColumnCache} and {@link #populateTypeJitter}.
     *
     * @param wy      world Y
     * @param density density from {@link DensityFunction#evaluateCached}
     * @param cfg     resolved gen config for this Y slice
     * @param layer   primary layer at {@code wy}
     * @param cache   column cache populated before this chunk's voxel loop
     * @param lx      local X in chunk (0–31)
     * @param lz      local Z in chunk (0–31)
     */
    public VoxelType resolveCached(int wy, float density,
                                   LayerGenConfig cfg, Layer layer,
                                   ColumnNoiseCache cache, int lx, int lz) {
        boolean isSolid = density > 0f;
        if (isSolid) {
            return pickSolidTypeCached(density, cfg, cache, lx, lz);
        }

        VoxelType surfaceWater = checkSurfaceOceanCached(wy, layer, cache, lx, lz);
        if (surfaceWater != null) {
            return surfaceWater;
        }

        boolean isOceaniaWater = layer == Layer.OCEANIA && wy < OCEANIA_WATERLINE_Y;
        if (isOceaniaWater) {
            return VoxelType.WATER;
        }

        return VoxelType.AIR;
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

    private VoxelType pickSolidTypeCached(float density, LayerGenConfig cfg,
                                          ColumnNoiseCache cache, int lx, int lz) {
        VoxelType[] types = cfg.dominantVoxelTypes();
        if (types.length == 1) {
            return types[0];
        }

        float jitter                = cache.typeJitter[ColumnNoiseCache.idx(lx, lz)];
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

    private VoxelType checkSurfaceOceanCached(int wy, Layer layer,
                                              ColumnNoiseCache cache, int lx, int lz) {
        if (layer != Layer.SURFACE) {
            return null;
        }
        int i = ColumnNoiseCache.idx(lx, lz);
        boolean isBeyondBoundary = cache.horizontalDist[i] > cache.effectiveBoundary[i];
        boolean isBelowSeaLevel  = wy <= SURFACE_SEA_LEVEL_Y;
        if (isBeyondBoundary && isBelowSeaLevel) {
            return VoxelType.WATER;
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Noise factory
    // ---------------------------------------------------------------

    private static FastNoiseLite buildNoise(int seed, float frequency) {
        FastNoiseLite noise = new FastNoiseLite(seed);
        noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        noise.SetFrequency(frequency);
        return noise;
    }
}
