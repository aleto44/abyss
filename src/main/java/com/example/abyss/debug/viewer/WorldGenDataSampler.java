package com.example.abyss.debug.viewer;

import com.badlogic.gdx.graphics.Color;

/**
 * Debug-facing abstraction for sampling deterministic worldgen data without
 * coupling the viewer to chunk streaming or generator internals.
 */
public interface WorldGenDataSampler {

    /**
     * Writes a resolved color for the given world position into {@code outColor}.
     *
     * @return {@code true} if real data was available and written;
     *         {@code false} if the data was not yet available (a fallback color
     *         was written and the caller should retry on the next frame).
     */
    boolean sampleColor(int worldX,
                        int worldY,
                        int worldZ,
                        WorldGenColorResolver colorResolver,
                        Color outColor);
}
