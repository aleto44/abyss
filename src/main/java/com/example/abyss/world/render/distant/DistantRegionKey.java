package com.example.abyss.world.render.distant;

/**
 * Stable identity for a distant terrain background region.
 *
 * @param regionX region-space X coordinate
 * @param regionY region-space Y coordinate
 * @param regionZ region-space Z coordinate
 */
public record DistantRegionKey(
        int regionX,
        int regionY,
        int regionZ
) {
}
