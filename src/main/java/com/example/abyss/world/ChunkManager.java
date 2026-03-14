package com.example.abyss.world;

import com.example.abyss.util.KeyedTaskQueue;
import com.example.abyss.world.gen.ProceduralGenerator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Manages the set of currently-loaded {@link VoxelChunk}s.
 * <p>
 * Responsible for two concerns:
 * <ol>
 *   <li><b>Chunk storage</b> — delegates to an internal {@link ChunkStore}.</li>
 *   <li><b>Async generation</b> — a {@link KeyedTaskQueue} backed by a fixed
 *       thread pool, with per-thread {@link ProceduralGenerator} instances
 *       for thread safety.</li>
 * </ol>
 * Mesh lifecycle (dirty-flagging, queuing, polling) is the responsibility of
 * {@link com.example.abyss.world.meshing.MeshCoordinator}.
 * </p>
 * <h3>Usage pattern (per frame):</h3>
 * <ol>
 *   <li>{@link #requestChunkAsync(int, int, int)} for every chunk the player needs</li>
 *   <li>{@link #pollCompletedChunks()} to drain finished results into the store</li>
 *   <li>{@link #getChunk(int, int, int)} (or {@link #getOrGenerateChunk(int, int, int)})
 *       for immediate access</li>
 * </ol>
 */
public class ChunkManager {

    /**
     * Each thread gets its own {@link ProceduralGenerator} instance so that
     * mutable noise state is never shared across threads.
     * All instances use the same seed, guaranteeing identical output.
     */
    private final ThreadLocal<ProceduralGenerator> generatorLocal;

    /** Keyed task queue that handles background chunk generation. */
    private final KeyedTaskQueue<ChunkKey, VoxelChunk> taskQueue;

    /** All currently-loaded chunks, keyed by chunk coordinate. */
    private final ChunkStore store;

    public ChunkManager(long seed) {
        this.generatorLocal = ThreadLocal.withInitial(() -> new ProceduralGenerator(seed));
        this.taskQueue = KeyedTaskQueue.<ChunkKey, VoxelChunk>builder()
                .threadNamePrefix("ChunkGen")
                .build();
        this.store = new ChunkStore();
    }

    // ----------------------------------------------------------------
    // Storage access (delegated to ChunkStore)
    // ----------------------------------------------------------------

    /** Returns the {@link ChunkStore} holding all currently-loaded chunk data. */
    public ChunkStore getStore() {
        return store;
    }

    /**
     * Returns the loaded chunk record, or {@code null} if not loaded.
     * Equivalent to {@code getStore().getLoaded(key)}.
     */
    public LoadedChunk getLoadedChunk(ChunkKey key) {
        return store.getLoaded(key);
    }

    /**
     * Returns the loaded chunk, or {@code null} if it isn't loaded yet.
     * Never blocks, never triggers generation.
     */
    public VoxelChunk getChunk(int cx, int cy, int cz) {
        return store.getChunk(cx, cy, cz);
    }

    /** Returns the loaded chunk for the given key, or {@code null} if not loaded. */
    public VoxelChunk getChunk(ChunkKey key) {
        return store.getChunk(key);
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

        LoadedChunk existing = store.getLoaded(key);
        if (existing != null) {
            return existing.chunk();
        }

        VoxelChunk awaited = taskQueue.awaitPending(key);
        if (awaited != null) {
            store.put(awaited);
            return awaited;
        }

        VoxelChunk chunk = taskQueue.computeNow(key, () -> buildChunk(key));
        store.put(chunk);
        return chunk;
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
        boolean alreadyLoaded = store.isLoaded(key);
        if (alreadyLoaded) {
            return false;
        }
        return taskQueue.submit(key, () -> buildChunk(key));
    }

    /**
     * Drains completed async results into the store.
     * Call once per frame on the main thread.
     *
     * @return the list of chunks that just became available this tick
     */
    public List<VoxelChunk> pollCompletedChunks() {
        List<KeyedTaskQueue.Result<ChunkKey, VoxelChunk>> results = taskQueue.pollCompleted();
        if (results.isEmpty()) {
            return Collections.emptyList();
        }

        List<VoxelChunk> justLoaded = new ArrayList<>(results.size());
        for (KeyedTaskQueue.Result<ChunkKey, VoxelChunk> result : results) {
            store.put(result.value());
            justLoaded.add(result.value());
        }
        return justLoaded;
    }

    // ----------------------------------------------------------------
    // Unloading
    // ----------------------------------------------------------------

    /** Unload a chunk, freeing its data from memory. Returns the removed entry, or {@code null}. */
    public LoadedChunk unloadChunk(int cx, int cy, int cz) {
        ChunkKey key = new ChunkKey(cx, cy, cz);
        taskQueue.forget(key);
        return store.remove(key);
    }

    /**
     * Cancels a pending (not-yet-loaded) generation task for the given chunk.
     * Has no effect if the chunk is already loaded or not in the queue.
     */
    public void cancelPendingChunk(int cx, int cy, int cz) {
        ChunkKey key = new ChunkKey(cx, cy, cz);
        boolean isLoaded = store.isLoaded(key);
        if (!isLoaded) {
            taskQueue.forget(key);
        }
    }

    /**
     * Returns all chunk keys that are currently pending generation (submitted
     * but not yet drained into the store).
     */
    public Collection<ChunkKey> getPendingChunkKeys() {
        return new ArrayList<>(taskQueue.getPendingKeys());
    }

    // ----------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------

    /** Returns {@code true} if the chunk is currently in memory. */
    public boolean isLoaded(int cx, int cy, int cz) {
        return store.isLoaded(new ChunkKey(cx, cy, cz));
    }

    /** All chunks currently in memory (read-only snapshot). */
    public Collection<VoxelChunk> getLoadedChunks() {
        return store.getChunks();
    }

    /** All loaded chunk records currently in memory (read-only snapshot). */
    public Collection<LoadedChunk> getLoadedChunkEntries() {
        return store.getLoadedChunks();
    }

    /** Number of loaded chunks. */
    public int getLoadedCount() {
        return store.size();
    }

    /** Number of chunks currently being generated. */
    public int getPendingCount() {
        return taskQueue.pendingCount();
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    /** Shuts down the background generation thread pool.  Call on game exit. */
    public void shutdown() {
        taskQueue.shutdown();
    }

    // ----------------------------------------------------------------
    // Internal helpers
    // ----------------------------------------------------------------

    private VoxelChunk buildChunk(ChunkKey key) {
        ProceduralGenerator gen = generatorLocal.get();
        return new VoxelChunk(key.cx(), key.cy(), key.cz(),
                gen.generateChunk(key.cx(), key.cy(), key.cz()));
    }
}