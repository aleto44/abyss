package com.example.abyss.world.render.distant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory LinkedHashMap implementation of {@link HeightmapCache}. */
public class InMemoryHeightmapCache implements HeightmapCache {

    private final Map<MacroChunkKey, MacroChunkHeightmap> store = new LinkedHashMap<>();

    @Override
    public void put(MacroChunkKey key, MacroChunkHeightmap heightmap) {
        store.put(key, heightmap);
    }

    @Override
    public MacroChunkHeightmap get(MacroChunkKey key) {
        return store.get(key);
    }

    @Override
    public void remove(MacroChunkKey key) {
        store.remove(key);
    }

    @Override
    public List<MacroChunkKey> getAllKeys() {
        return new ArrayList<>(store.keySet());
    }
}
