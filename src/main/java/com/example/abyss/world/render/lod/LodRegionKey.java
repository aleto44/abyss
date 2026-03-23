package com.example.abyss.world.render.lod;

/**
 * Stable identity for a far-field proxy region.
 *
 * @param regionX region-space X coordinate
 * @param regionY region-space Y coordinate
 * @param regionZ region-space Z coordinate
 * @param lodLevel proxy detail level
 */
public record LodRegionKey(
        int regionX,
        int regionY,
        int regionZ,
        int lodLevel
) {
}
