package com.example.abyss.world;

import com.example.abyss.world.meshing.ChunkMeshState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe storage for the set of currently-loaded {@link LoadedChunk}s.
 * <p>
 * Provides chunk lookup, insertion, removal, and snapshot queries.
 * All mutation is safe to call from background generation threads;
 * the underlying map is a {@link ConcurrentHashMap}.
 * </p>
 */
public class ChunkStore {

    private final Map<ChunkKey, LoadedChunk> loaded = new ConcurrentHashMap<>();

    // ----------------------------------------------------------------
    // Storage operations
    // ----------------------------------------------------------------

    /**
     * Inserts a {@link VoxelChunk} into the store, wrapping it in a fresh
     * {@link LoadedChunk} with an empty {@link ChunkMeshState}.
     *
     * @param chunk the chunk to store
     * @return the newly created {@link LoadedChunk} record
     */
    public LoadedChunk put(VoxelChunk chunk) {
        ChunkKey key = new ChunkKey(chunk.getChunkX(), chunk.getChunkY(), chunk.getChunkZ());
        LoadedChunk loadedChunk = new LoadedChunk(chunk, new ChunkMeshState());
        loaded.put(key, loadedChunk);
        return loadedChunk;
    }

    /**
     * Removes and returns the {@link LoadedChunk} for the given key, or
     * {@code null} if it was not present.
     */
    public LoadedChunk remove(ChunkKey key) {
        return loaded.remove(key);
    }

    // ----------------------------------------------------------------
    // Lookups
    // ----------------------------------------------------------------

    /** Returns the loaded chunk record, or {@code null} if not present. */
    public LoadedChunk getLoaded(ChunkKey key) {
        return loaded.get(key);
    }

    /**
     * Returns the {@link VoxelChunk} for the given chunk-space coordinates,
     * or {@code null} if not loaded.
     */
    public VoxelChunk getChunk(int cx, int cy, int cz) {
        LoadedChunk loadedChunk = loaded.get(new ChunkKey(cx, cy, cz));
        return loadedChunk != null ? loadedChunk.chunk() : null;
    }

    /**
     * Returns the {@link VoxelChunk} for the given key, or {@code null} if
     * not loaded.
     */
    public VoxelChunk getChunk(ChunkKey key) {
        LoadedChunk loadedChunk = loaded.get(key);
        return loadedChunk != null ? loadedChunk.chunk() : null;
    }

    /** Returns {@code true} if the given key is currently loaded. */
    public boolean isLoaded(ChunkKey key) {
        return loaded.containsKey(key);
    }

    // ----------------------------------------------------------------
    // Bulk queries
    // ----------------------------------------------------------------

    /**
     * Returns an unmodifiable snapshot of all loaded {@link VoxelChunk}s.
     */
    public Collection<VoxelChunk> getChunks() {
        List<VoxelChunk> chunks = new ArrayList<>(loaded.size());
        for (LoadedChunk loadedChunk : loaded.values()) {
            chunks.add(loadedChunk.chunk());
        }
        return Collections.unmodifiableList(chunks);
    }

    /**
     * Returns an unmodifiable snapshot of all {@link LoadedChunk} records.
     */
    public Collection<LoadedChunk> getLoadedChunks() {
        return Collections.unmodifiableList(new ArrayList<>(loaded.values()));
    }

    /** Returns the live, unmodifiable collection of all loaded chunk entries. */
    public Collection<LoadedChunk> entries() {
        return loaded.values();
    }

    /** Number of chunks currently held in the store. */
    public int size() {
        return loaded.size();
    }

    /**
     * Marks the mesh for the given key as dirty if a {@link LoadedChunk}
     * exists for it. No-op if the key is not loaded.
     */
    public void markMeshDirty(ChunkKey key) {
        LoadedChunk loadedChunk = loaded.get(key);
        if (loadedChunk != null) {
            loadedChunk.renderState().setMeshDirty(true);
        }
    }
}
