package com.example.abyss.world.gen;

/**
 * Functional interface that resolves a {@link LayerGenConfig} for a given
 * world-Y and primary layer.  Used by the debug tweaker to inject overridden
 * configs without touching the production code path.
 *
 * @see ProceduralGenerator
 */
@FunctionalInterface
public interface GenConfigResolver {

    LayerGenConfig resolve(int worldY, Layer primaryLayer);

    /**
     * The default resolver: uses baked {@link Layer} configs,
     * blending between layers when inside a transition zone.
     */
    GenConfigResolver DEFAULT = (worldY, primaryLayer) -> {
        Layer.TransitionInfo ti = Layer.getTransition(worldY);
        if (ti != null) {
            return ti.blendedGenConfig();
        }
        return primaryLayer.getGenConfig();
    };
}
