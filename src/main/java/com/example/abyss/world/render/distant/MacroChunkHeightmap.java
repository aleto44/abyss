package com.example.abyss.world.render.distant;

/**
 * Heightmap data for one distant terrain macro-chunk slab.
 * The vertical band is identified by {@link MacroChunkKey#macroY()};
 * this record carries exactly one band's worth of data.
 *
 * @param key  macro-chunk identity (XYZ, where Y encodes the band index)
 * @param band heightmap data for the single vertical band encoded in {@code key}
 */
public record MacroChunkHeightmap(MacroChunkKey key, HeightmapBand band) {}
