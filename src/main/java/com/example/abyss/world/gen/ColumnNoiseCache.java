package com.example.abyss.world.gen;

import com.example.abyss.world.voxel.VoxelData;

/**
 * Reusable scratch cache for per-XZ-column noise values computed once before
 * the inner Y loop during chunk generation.
 * <p>
 * For a 32×32×32 chunk, several inputs to {@link DensityFunction#evaluateCached} depend
 * only on world X and Z, or vary so slowly in Y across 32 voxels that caching at
 * the XZ plane is a safe approximation:
 * <ul>
 *   <li>{@code horizontalDist} — pure XZ, contains an expensive {@code Math.sqrt}</li>
 *   <li>{@code shaftNoise} — frequency 0.005, moves only 0.16 noise-units across 32 Y voxels</li>
 *   <li>{@code boundaryNoise} — frequency 0.003, moves only 0.096 noise-units across 32 Y voxels</li>
 *   <li>{@code typeNoise} — frequency 0.04, XZ-dominant jitter for voxel type banding</li>
 * </ul>
 * Populating this cache once per chunk replaces 32,768 calls to those four sources
 * with 1,024, eliminating ~95,000 noise evaluations and the per-voxel sqrt.
 * </p>
 * <p>
 * Instances live on {@link ProceduralGenerator}, which is stored in a
 * {@link ThreadLocal} inside {@link com.example.abyss.world.ChunkManager} —
 * so these arrays are never shared across threads.
 * </p>
 */
public class ColumnNoiseCache {

    static final int SIZE = VoxelData.SIZE; // 32
    private static final int AREA = SIZE * SIZE; // 1024

    /** Precomputed {@code horizontalDist = sqrt(wx² + wz²)} per XZ column. */
    final float[] horizontalDist = new float[AREA];

    /**
     * Precomputed shaft term {@code (1 - smoothstep(effectiveRadius ± SHAFT_MARGIN, dist))}
     * per XZ column.
     */
    final float[] shaftTerm = new float[AREA];

    /**
     * Precomputed boundary term {@code smoothstep(effectiveBoundary ± BOUNDARY_MARGIN, dist)}
     * per XZ column.
     */
    final float[] boundaryTerm = new float[AREA];

    /**
     * Precomputed {@code effectiveBoundary = ABYSS_BOUNDARY_RADIUS + perturbation} per XZ column.
     * Needed separately by the surface-ocean check in {@link VoxelTypeResolver#resolveCached}.
     */
    final float[] effectiveBoundary = new float[AREA];

    /**
     * Precomputed type jitter {@code typeNoise * (SURFACE_SHELL_DEPTH * 0.4f)} per XZ column.
     * Used by the solid-type picker to organically vary shell-band edges.
     */
    final float[] typeJitter = new float[AREA];

    /** Flat index into the 32×32 XZ grid. */
    static int idx(int lx, int lz) {
        return lz * SIZE + lx;
    }
}
