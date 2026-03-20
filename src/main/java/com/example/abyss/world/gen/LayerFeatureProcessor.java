package com.example.abyss.world.gen;

import com.example.abyss.world.voxel.VoxelData;

/**
 * Post-processes a generated chunk to add layer-specific structures.
 * <p>
 * This is generation step 4 from the world-gen spec: after the density
 * function and voxel-type assignment, each layer can stamp additional
 * features into the chunk (giant mushrooms, floating islands, etc.).
 * </p>
 */
public interface LayerFeatureProcessor {

    /**
     * Modify {@code data} in-place to add layer-specific features.
     *
     * @param data       the chunk's voxel data (already populated by the density pass)
     * @param worldBaseX world X of the chunk origin
     * @param worldBaseY world Y of the chunk origin
     * @param worldBaseZ world Z of the chunk origin
     * @param resolver   the active config resolver — use this to read shaft diameters and
     *                   other layer params so debug overrides are respected
     */
    void process(VoxelData data, int worldBaseX, int worldBaseY, int worldBaseZ, GenConfigResolver resolver);
}