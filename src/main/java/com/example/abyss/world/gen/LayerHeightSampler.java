package com.example.abyss.world.gen;

/**
 * Optional extension for {@link LayerFeatureProcessor} implementations that own their
 * own surface shape (e.g. the mountain ring on the Surface layer).
 * <p>
 * When a processor implements this interface, callers such as
 * {@link ProceduralGenerator#scanHeightmapColumn} can query the top solid Y directly
 * instead of scanning every voxel via the density function — which would miss solids
 * that the feature processor stamps in after the density pass.
 * </p>
 */
public interface LayerHeightSampler {

    /**
     * Returns the highest world Y that is solid for the given XZ column,
     * clamped to the layer's valid Y range.
     *
     * @param wx       world X
     * @param wz       world Z
     * @param resolver active config resolver (used for shaft diameter and other params)
     */
    int sampleTopSolidY(int wx, int wz, GenConfigResolver resolver);
}
