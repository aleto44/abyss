package com.example.abyss.world.gen;

/**
 * A {@link LayerFeatureProcessor} that operates on the boundary zone between two
 * adjacent layers rather than the interior of a single layer.
 * <p>
 * Implementations declare their operating bounds via {@link #shouldProcess}, which
 * {@link ProceduralGenerator} calls before {@link #process} — if {@code shouldProcess}
 * returns {@code false} the chunk is skipped entirely without allocating any work.
 * This separates the "does this chunk need me?" question from the "do the work" logic,
 * keeping both readable and independently testable.
 * </p>
 */
public interface LayerTransitionFeatureProcessor extends LayerFeatureProcessor {

    /**
     * Returns {@code true} if this processor has any work to do for the given chunk.
     * <p>
     * Implementations should check all relevant bounds here — Y-band overlap, XZ
     * radial reach, or any other spatial condition — so that {@link #process} can
     * assume it is operating within its valid range and skip redundant guard logic.
     * </p>
     *
     * @param worldBaseX world X of the chunk origin
     * @param worldBaseY world Y of the chunk origin
     * @param worldBaseZ world Z of the chunk origin
     * @param resolver   the active config resolver — use this to read any
     *                   resolver-dependent bounds (e.g. shaft diameter)
     * @return {@code true} if {@link #process} should be called for this chunk
     */
    boolean shouldProcess(int worldBaseX, int worldBaseY, int worldBaseZ, GenConfigResolver resolver);
}
