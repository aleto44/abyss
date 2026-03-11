package com.example.abyss.world;

import com.example.abyss.world.gen.ProceduralGenerator;
import com.example.abyss.util.KeyedTaskQueue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the set of currently-loaded {@link VoxelChunk}s.
 * <p>
 * Supports both synchronous and <b>asynchronous</b> chunk generation.
 * Async work is delegated to a generic {@link KeyedTaskQueue} backed by a
 * fixed thread pool, with per-thread {@link ProceduralGenerator} instances
 * for thread safety.
 * </p>
 * <h3>Usage pattern (per frame):</h3>
 * <ol>
 *   <li>{@link #requestChunkAsync(int, int, int)} for every chunk the player needs</li>
 *   <li>{@link #pollCompletedChunks()} to drain finished results into the loaded map</li>
 *   <li>{@link #getChunk(int, int, int)} (or {@link #getOrGenerateChunk(int, int, int)})
 *       for immediate access</li>
 * </ol>
 */
public class ChunkManager {

    private final Map<ChunkKey, VoxelChunk> loaded = new ConcurrentHashMap<>();

    /**
     * Each thread gets its own {@link ProceduralGenerator} instance so that
     * mutable noise state (e.g. frequency) is never shared across threads.
     * All instances use the same seed, guaranteeing identical output.
     */
    private final ThreadLocal<ProceduralGenerator> generatorLocal;

    /** Keyed task queue that handles background chunk generation. */
    private final KeyedTaskQueue<ChunkKey, VoxelChunk> taskQueue;

    public ChunkManager(long seed) {
        this.generatorLocal = ThreadLocal.withInitial(() -> new ProceduralGenerator(seed));
        this.taskQueue = KeyedTaskQueue.<ChunkKey, VoxelChunk>builder()
                .threadNamePrefix("ChunkGen")
                .build();
    }

    // ----------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------

    private VoxelChunk buildChunk(ChunkKey key) {
        ProceduralGenerator gen = generatorLocal.get();
        return new VoxelChunk(key.cx(), key.cy(), key.cz(),
                gen.generateChunk(key.cx(), key.cy(), key.cz()));
    }

    // ----------------------------------------------------------------
    // Synchronous access (blocks if chunk is not yet loaded)
    // ----------------------------------------------------------------

    /**
     * Returns the chunk at the given chunk-space coordinates, generating it
     * <b>synchronously on the calling thread</b> if it has not been loaded yet.
     * <p>
     * Use this only when you absolutely need the data <em>right now</em>
     * (e.g. collision queries, voxel lookups).  Prefer the async path for
     * loading chunks ahead of the player.
     */
    public VoxelChunk getOrGenerateChunk(int cx, int cy, int cz) {
        ChunkKey key = new ChunkKey(cx, cy, cz);

        // Fast path — already loaded
        VoxelChunk existing = loaded.get(key);
        if (existing != null) return existing;

        // If it's pending async, wait for it
        VoxelChunk awaited = taskQueue.awaitPending(key);
        if (awaited != null) {
            loaded.put(key, awaited);
            return awaited;
        }

        // Not pending either — generate synchronously
        VoxelChunk chunk = taskQueue.computeNow(key, () -> buildChunk(key));
        loaded.put(key, chunk);
        return chunk;
    }

    /**
     * Returns the loaded chunk, or {@code null} if it isn't loaded yet.
     * Never blocks, never triggers generation.
     */
    public VoxelChunk getChunk(int cx, int cy, int cz) {
        return loaded.get(new ChunkKey(cx, cy, cz));
    }

    // ----------------------------------------------------------------
    // Asynchronous generation
    // ----------------------------------------------------------------

    /**
     * Requests that the chunk at (cx, cy, cz) be generated on a background
     * thread if it is not already loaded or pending.  Non-blocking.
     *
     * @return {@code true} if a new generation task was submitted
     */
    public boolean requestChunkAsync(int cx, int cy, int cz) {
        ChunkKey key = new ChunkKey(cx, cy, cz);
        if (loaded.containsKey(key)) return false;
        return taskQueue.submit(key, () -> buildChunk(key));
    }

    /**
     * Drains completed async results into the loaded map.
     * Call once per frame on the main thread.
     *
     * @return the list of chunks that just became available this tick
     */
    public List<VoxelChunk> pollCompletedChunks() {
        List<KeyedTaskQueue.Result<ChunkKey, VoxelChunk>> results = taskQueue.pollCompleted();
        if (results.isEmpty()) return Collections.emptyList();

        List<VoxelChunk> justLoaded = new ArrayList<>(results.size());
        for (var result : results) {
            loaded.put(result.key(), result.value());
            justLoaded.add(result.value());
        }
        return justLoaded;
    }

    // ----------------------------------------------------------------
    // Unloading
    // ----------------------------------------------------------------

    /** Unload a chunk, freeing its data from memory. */
    public void unloadChunk(int cx, int cy, int cz) {
        ChunkKey key = new ChunkKey(cx, cy, cz);
        loaded.remove(key);
        taskQueue.forget(key);
    }

    // ----------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------

    /** Returns {@code true} if the chunk is currently in memory. */
    public boolean isLoaded(int cx, int cy, int cz) {
        return loaded.containsKey(new ChunkKey(cx, cy, cz));
    }

    /** All chunks currently in memory (read-only snapshot). */
    public Collection<VoxelChunk> getLoadedChunks() {
        return loaded.values();
    }

    /** Number of loaded chunks. */
    public int getLoadedCount() {
        return loaded.size();
    }

    /** Number of chunks currently being generated. */
    public int getPendingCount() {
        return taskQueue.pendingCount();
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    /** Shuts down the generation thread pool.  Call on game exit. */
    public void shutdown() {
        taskQueue.shutdown();
    }
}
