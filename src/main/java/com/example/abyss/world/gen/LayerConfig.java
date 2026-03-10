package com.example.abyss.world.gen;

import com.badlogic.gdx.graphics.Color;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Immutable configuration for a single abyss layer.
 * All parameters here feed directly into the procedural generation pipeline
 * and can be linearly interpolated during layer transitions.
 *
 * @param baseDensityThreshold noise threshold above which a voxel is solid (lower → more solid)
 * @param noiseFrequency       scale of terrain features (lower → larger formations)
 * @param noiseAmplitude       scales noise output (higher → more dramatic variation)
 * @param shaftDiameter        diameter of the main shaft in voxels for this layer
 * @param dominantVoxelTypes   primary voxel types assigned to solid voxels (picked via secondary noise)
 * @param hazardType           optional hazardous voxel type placed in pockets (null if none)
 * @param visibility           ambient visibility range in voxels (drives fog)
 * @param colorPalette         layer-specific color tints for rendering
 */
public record LayerConfig(
        float baseDensityThreshold,
        float noiseFrequency,
        float noiseAmplitude,
        float shaftDiameter,
        VoxelType[] dominantVoxelTypes,
        VoxelType hazardType,
        float visibility,
        Color[] colorPalette
) {

    /**
     * Linearly interpolate between two LayerConfigs.
     *
     * @param a config for the shallower layer
     * @param b config for the deeper layer
     * @param t blend factor 0.0 (= a) to 1.0 (= b)
     * @return blended config (voxel types and palette come from the dominant layer based on t > 0.5)
     */
    public static LayerConfig lerp(LayerConfig a, LayerConfig b, float t) {
        float invT = 1f - t;

        // Numeric parameters: linear interpolation
        float threshold = a.baseDensityThreshold * invT + b.baseDensityThreshold * t;
        float frequency = a.noiseFrequency * invT + b.noiseFrequency * t;
        float amplitude = a.noiseAmplitude * invT + b.noiseAmplitude * t;
        float shaft     = a.shaftDiameter * invT + b.shaftDiameter * t;
        float vis       = a.visibility * invT + b.visibility * t;

        // Discrete parameters: pick from the dominant layer
        boolean useB = t > 0.5f;
        VoxelType[] types   = useB ? b.dominantVoxelTypes : a.dominantVoxelTypes;
        VoxelType   hazard  = useB ? b.hazardType : a.hazardType;
        Color[]     palette = useB ? b.colorPalette : a.colorPalette;

        return new LayerConfig(threshold, frequency, amplitude, shaft, types, hazard, vis, palette);
    }
}
