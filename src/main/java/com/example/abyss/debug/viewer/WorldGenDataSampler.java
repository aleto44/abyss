package com.example.abyss.debug.viewer;

import com.badlogic.gdx.graphics.Color;

/**
 * Debug-facing abstraction for sampling deterministic worldgen data without
 * coupling the viewer to chunk streaming or generator internals.
 */
public interface WorldGenDataSampler {

    void sampleColor(int worldX,
                     int worldY,
                     int worldZ,
                     WorldGenColorResolver colorResolver,
                     Color outColor);
}
