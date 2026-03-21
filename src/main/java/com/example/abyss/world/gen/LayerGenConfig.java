package com.example.abyss.world.gen;

import com.example.abyss.world.voxel.VoxelType;

/**
 * Immutable generation parameters for a single abyss layer.
 * <p>
 * These fields feed directly into the procedural density pipeline and can be
 * linearly interpolated during layer transitions.  Rendering concerns
 * ({@code visibility}, {@code colorPalette}) live separately in
 * {@link LayerRenderConfig}.
 * </p>
 *
 * @param baseDensityThreshold noise threshold above which a voxel is solid (lower → more solid)
 * @param noiseFrequency       scale of terrain features (lower → larger formations)
 * @param noiseAmplitude       scales noise output (higher → more dramatic variation)
 * @param shaftDiameter        diameter of the main shaft in voxels for this layer
 * @param dominantVoxelTypes   primary voxel types assigned to solid voxels (picked via secondary noise)
 */
public record LayerGenConfig(
        float baseDensityThreshold,
        float noiseFrequency,
        float noiseAmplitude,
        float shaftDiameter,
        VoxelType[] dominantVoxelTypes
) {

    /**
     * Linearly interpolate between two {@link LayerGenConfig}s.
     *
     * @param a config for the shallower layer
     * @param b config for the deeper layer
     * @param t blend factor 0.0 (= a) to 1.0 (= b)
     * @return blended config — numeric fields interpolated, discrete fields taken from the dominant layer
     */
    public static LayerGenConfig lerp(LayerGenConfig a, LayerGenConfig b, float t) {
        float invT = 1f - t;

        float threshold = a.baseDensityThreshold * invT + b.baseDensityThreshold * t;
        float frequency = a.noiseFrequency       * invT + b.noiseFrequency       * t;
        float amplitude = a.noiseAmplitude       * invT + b.noiseAmplitude       * t;
        float shaft     = a.shaftDiameter        * invT + b.shaftDiameter        * t;

        boolean useB       = t > 0.5f;
        VoxelType[] types  = useB ? b.dominantVoxelTypes : a.dominantVoxelTypes;

        return new LayerGenConfig(threshold, frequency, amplitude, shaft, types);
    }
}