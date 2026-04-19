package com.example.abyss.world.render.distant;

import java.util.List;

/** Storage contract for distant terrain macro-chunk heightmap data. */
public interface HeightmapCache {

    void put(MacroChunkKey key, MacroChunkHeightmap heightmap);

    MacroChunkHeightmap get(MacroChunkKey key);

    void remove(MacroChunkKey key);

    /** Returns all currently stored keys. */
    List<MacroChunkKey> getAllKeys();
}
