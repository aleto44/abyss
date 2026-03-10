package com.example.abyss.world;

import com.example.abyss.world.gen.ProceduralGenerator;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages the set of currently-loaded {@link VoxelChunk}s.
 * <p>
 * Chunks are lazily generated via the {@link ProceduralGenerator} on first
 * access and cached in a hash map keyed by {@link ChunkKey}.
 * </p>
 */
public class ChunkManager {

    private final Map<ChunkKey, VoxelChunk> loaded = new HashMap<>();
    private final ProceduralGenerator generator;

    public ChunkManager(ProceduralGenerator generator) {
        this.generator = generator;
    }

    /**
     * Returns the chunk at the given chunk-space coordinates, generating it
     * if it has not been loaded yet.
     */
    public VoxelChunk getOrGenerateChunk(int cx, int cy, int cz) {
        ChunkKey key = new ChunkKey(cx, cy, cz);
        return loaded.computeIfAbsent(key, k ->
                new VoxelChunk(cx, cy, cz, generator.generateChunk(cx, cy, cz)));
    }

    /** Unload a chunk, freeing its data from memory. */
    public void unloadChunk(int cx, int cy, int cz) {
        loaded.remove(new ChunkKey(cx, cy, cz));
    }

    /** Returns {@code true} if the chunk is currently in memory. */
    public boolean isLoaded(int cx, int cy, int cz) {
        return loaded.containsKey(new ChunkKey(cx, cy, cz));
    }

    /** All chunks currently in memory (read-only view). */
    public Collection<VoxelChunk> getLoadedChunks() {
        return loaded.values();
    }

    /** Number of loaded chunks. */
    public int getLoadedCount() {
        return loaded.size();
    }
}
