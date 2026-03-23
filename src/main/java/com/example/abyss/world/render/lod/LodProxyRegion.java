package com.example.abyss.world.render.lod;

/**
 * Render-only far-field proxy region state.
 *
 * @param key stable proxy region identity
 * @param worldX world-space X origin
 * @param worldY world-space Y origin
 * @param worldZ world-space Z origin
 * @param meshState proxy mesh lifecycle state
 */
public record LodProxyRegion(
        LodRegionKey key,
        float worldX,
        float worldY,
        float worldZ,
        LodProxyMeshState meshState
) {
}
