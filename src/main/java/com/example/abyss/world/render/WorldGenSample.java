package com.example.abyss.world.render;

import com.example.abyss.world.gen.Layer;
import com.example.abyss.world.gen.LayerConfig;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Immutable render-facing sample for a single world-space position.
 * <p>
 * Keeps visualization logic decoupled from the generation internals while still
 * exposing the data needed for deterministic debug rendering.
 * </p>
 *
 * @param worldX world X coordinate
 * @param worldY world Y coordinate
 * @param worldZ world Z coordinate
 * @param density sampled continuous density value
 * @param voxelType sampled discrete voxel type
 * @param layer primary layer at {@code worldY}
 * @param effectiveConfig resolved layer config, including transition blending when applicable
 * @param inTransition true when the sample lies inside a layer transition zone
 * @param transitionBlendFactor 0.0 = upper layer, 1.0 = lower layer, -1.0 when not in transition
 */
public record WorldGenSample(
        int worldX,
        int worldY,
        int worldZ,
        float density,
        VoxelType voxelType,
        Layer layer,
        LayerConfig effectiveConfig,
        boolean inTransition,
        float transitionBlendFactor
) {
}
