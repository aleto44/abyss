package com.example.abyss.world.gen;

import com.badlogic.gdx.graphics.Color;

/**
 * Immutable rendering parameters for a single abyss layer.
 * <p>
 * These fields are consumed by the debug viewer and eventually by the main
 * renderer (fog, ambient tint, etc.).  Generation code should never need to
 * touch this record — see {@link LayerGenConfig} for the gen-side parameters.
 * </p>
 *
 * @param visibility   ambient visibility range in voxels (drives fog density)
 * @param colorPalette layer-specific color tints; index 0 is the primary air/atmosphere
 *                     color, index 1 is the secondary tint used for solid surfaces
 */
public record LayerRenderConfig(
        float visibility,
        Color[] colorPalette
) {

    /**
     * Linearly interpolate between two {@link LayerRenderConfig}s.
     *
     * @param a config for the shallower layer
     * @param b config for the deeper layer
     * @param t blend factor 0.0 (= a) to 1.0 (= b)
     * @return blended config — visibility interpolated, palette taken from the dominant layer
     */
    public static LayerRenderConfig lerp(LayerRenderConfig a, LayerRenderConfig b, float t) {
        float visibility = a.visibility * (1f - t) + b.visibility * t;
        Color[] palette  = t > 0.5f ? b.colorPalette : a.colorPalette;
        return new LayerRenderConfig(visibility, palette);
    }
}
