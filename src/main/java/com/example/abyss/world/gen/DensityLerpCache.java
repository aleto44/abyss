package com.example.abyss.world.gen;

/**
 * Reusable scratch cache for trilinear-interpolation of terrain and cave noise.
 * <p>
 * Instead of calling {@code terrainNoise.GetNoise} and {@code caveNoise.GetNoise}
 * for every one of the 32,768 voxels in a chunk, we sample both noise functions
 * on a coarse 4-voxel grid and linearly interpolate between the samples.
 * </p>
 *
 * <h3>Grid layout</h3>
 * A 32-voxel axis has 8 intervals of 4 voxels each, requiring 9 sample points
 * (0, 4, 8, 12, 16, 20, 24, 28, 32).  The three axes therefore produce a
 * 9×9×9 = 729-entry sample grid.  Between any two adjacent samples, all
 * intermediate voxels are filled by trilinear interpolation — a multiply-add
 * per voxel per axis, replacing an OpenSimplex2 FBm evaluation.
 * </p>
 *
 * <h3>Safety of the approximation</h3>
 * <ul>
 *   <li>Terrain noise: frequency 0.0045–0.020.  At step=4 the fastest layer
 *       moves {@code 4 × 0.020 = 0.08} noise-units between samples — well inside
 *       the smooth region of OpenSimplex2 FBm.  Linear interpolation is
 *       visually indistinguishable from the true value.</li>
 *   <li>Cave noise: frequency 0.006, 2-octave Ridged.  Step=4 moves
 *       {@code 4 × 0.006 = 0.024} noise-units.  Ridged noise is C1-continuous
 *       away from its ridge peaks, so lerp error is sub-voxel.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * Instances live on {@link ProceduralGenerator}, which is stored in a
 * {@link ThreadLocal} — never shared across threads.
 */
public class DensityLerpCache {

    /** Number of voxels between sample points. */
    static final int STEP = 4;

    /** Number of sample points per axis: (32 / 4) + 1 = 9. */
    static final int NODES = (32 / STEP) + 1; // 9

    private static final int VOLUME = NODES * NODES * NODES; // 729

    /**
     * Sampled terrain noise values at each grid node.
     * Includes the {@code amplitude} and {@code baseDensityThreshold} baked in,
     * so the interpolated result is directly the terrain density contribution.
     * Index: {@code iy * NODES * NODES + iz * NODES + ix}.
     */
    final float[] terrain = new float[VOLUME];

    /**
     * Sampled cave term values at each grid node, after applying the cave threshold
     * ramp so the interpolated value is directly the cave density subtraction term.
     * Index: {@code iy * NODES * NODES + iz * NODES + ix}.
     */
    final float[] cave = new float[VOLUME];

    static int idx(int ix, int iy, int iz) {
        return iy * NODES * NODES + iz * NODES + ix;
    }
}
