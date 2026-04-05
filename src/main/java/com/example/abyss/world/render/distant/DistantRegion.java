package com.example.abyss.world.render.distant;

/**
 * Render-only distant terrain background region state.
 *
 * @param key      stable region identity
 * @param worldX   world-space X origin
 * @param worldY   world-space Y origin
 * @param worldZ   world-space Z origin
 * @param meshState mesh lifecycle state
 */
public record DistantRegion(
        DistantRegionKey key,
        float worldX,
        float worldY,
        float worldZ,
        DistantMeshState meshState
) {
}
