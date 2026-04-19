package com.example.abyss.world.gen;

import com.example.abyss.world.voxel.VoxelType;

/**
 * Optional extension for {@link LayerFeatureProcessor} implementations whose voxel output
 * can be determined per-column without generating a full 32³ chunk.
 * <p>
 * The heightmap fast path ({@link ProceduralGenerator#scanHeightmapColumn}) uses this
 * interface to get accurate voxel data for processors that override the density pass
 * (e.g. {@code SurfaceFeatureProcessor} fills columns via a height function;
 * {@code HangingGardensFeatureProcessor} places floating geometry).
 * Without this interface the scan falls back to the raw density function, which is
 * correct for density-only layers but silently wrong for feature-driven ones.
 * </p>
 * <h3>Contract</h3>
 * <ol>
 *   <li>{@link #prepareColumn} is called <em>once</em> per XZ column before the Y sweep.
 *       Expensive XZ-stable computation (ring height, radial envelope, placement noise)
 *       belongs here so it is not repeated per voxel.</li>
 *   <li>{@link #evaluateVoxelType} is called for each sampled Y within the column.
 *       It must return {@code null} for any voxel this processor does not own — the caller
 *       will fall through to the density function for those positions.</li>
 * </ol>
 */
public interface ColumnVoxelEvaluator {

    /**
     * Pre-computes any XZ-stable state needed for the Y scan.
     * Called once per (wx, wz) column, before any {@link #evaluateVoxelType} calls.
     *
     * @param wx       world X of the column
     * @param wz       world Z of the column
     * @param resolver active config resolver
     * @return opaque per-column state passed to every {@link #evaluateVoxelType} call
     */
    ColumnContext prepareColumn(int wx, int wz, GenConfigResolver resolver);

    /**
     * Returns the {@link VoxelType} this processor would stamp at {@code (wx, wy, wz)},
     * or {@code null} if this processor does not affect that voxel (caller falls through
     * to the density function).
     *
     * @param wx  world X
     * @param wy  world Y
     * @param wz  world Z
     * @param ctx the state object returned by {@link #prepareColumn} for this column
     */
    VoxelType evaluateVoxelType(int wx, int wy, int wz, ColumnContext ctx);

    /** Opaque marker for per-column pre-computed state. */
    interface ColumnContext {}
}
