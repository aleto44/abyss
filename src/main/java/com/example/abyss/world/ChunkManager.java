package com.example.abyss.world;

import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.example.abyss.util.KeyedTaskQueue;
import com.example.abyss.world.gen.GenConfigResolver;
import com.example.abyss.world.gen.ProceduralGenerator;
import com.example.abyss.world.meshing.ChunkMeshState;
import com.example.abyss.world.meshing.MeshManager;

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
 *   <li>{@link #requestChunkMesh(ChunkKey)} for meshable chunks</li>
 *   <li>{@link #pollCompletedMeshes(ShaderProgram)} to promote CPU mesh data into LibGDX meshes on the main thread</li>
 *   <li>{@link #getChunk(int, int, int)} (or {@link #getOrGenerateChunk(int, int, int)})
 *       for immediate access</li>
 * </ol>
 */
public class ChunkManager {

    private final Map<ChunkKey, LoadedChunk> loaded = new ConcurrentHashMap<>();

    /**
     * Each thread gets its own {@link ProceduralGenerator} instance so that
     * mutable noise state (e.g. frequency) is never shared across threads.
     * All instances use the same seed, guaranteeing identical output.
     */
    private final ThreadLocal<ProceduralGenerator> generatorLocal;

    /** Keyed task queue that handles background chunk generation. */
    private final KeyedTaskQueue<ChunkKey, VoxelChunk> taskQueue;
    private final MeshManager meshManager;

    public ChunkManager(long seed, GenConfigResolver resolver) {
        this.generatorLocal = ThreadLocal.withInitial(() -> new ProceduralGenerator(seed, resolver));
        this.taskQueue = KeyedTaskQueue.<ChunkKey, VoxelChunk>builder()
                .threadNamePrefix("ChunkGen")
                .threadCount(Math.max(2, Runtime.getRuntime().availableProcessors()))
                .build();
        this.meshManager = new MeshManager();
    }

    private VoxelChunk buildChunk(ChunkKey key) {
        ProceduralGenerator gen = generatorLocal.get();
        VoxelChunk chunk = new VoxelChunk(key.cx(), key.cy(), key.cz(),
                gen.generateChunk(key.cx(), key.cy(), key.cz()));
        chunk.setFaceConnectivity(ChunkFaceConnectivity.compute(chunk));
        return chunk;
    }

    private LoadedChunk createLoadedChunk(VoxelChunk chunk) {
        return new LoadedChunk(chunk, new ChunkMeshState());
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

        LoadedChunk existing = loaded.get(key);
        if (existing != null) {
            return existing.chunk();
        }

        VoxelChunk awaited = taskQueue.awaitPending(key);
        if (awaited != null) {
            loaded.put(key, createLoadedChunk(awaited));
            return awaited;
        }

        VoxelChunk chunk = taskQueue.computeNow(() -> buildChunk(key));
        loaded.put(key, createLoadedChunk(chunk));
        return chunk;
    }

    /**
     * Returns the loaded chunk, or {@code null} if it isn't loaded yet.
     * Never blocks, never triggers generation.
     */
    public VoxelChunk getChunk(int cx, int cy, int cz) {
        LoadedChunk loadedChunk = loaded.get(new ChunkKey(cx, cy, cz));
        return loadedChunk != null ? loadedChunk.chunk() : null;
    }

    public VoxelChunk getChunk(ChunkKey key) {
        LoadedChunk loadedChunk = loaded.get(key);
        return loadedChunk != null ? loadedChunk.chunk() : null;
    }

    public LoadedChunk getLoadedChunk(ChunkKey key) {
        return loaded.get(key);
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
        return requestChunkAsync(cx, cy, cz, Integer.MAX_VALUE);
    }

    /**
     * Requests chunk generation at a specific priority. Lower values execute first.
     * No-op if the chunk is already loaded or pending.
     *
     * @return {@code true} if a new generation task was submitted
     */
    public boolean requestChunkAsync(int cx, int cy, int cz, int priority) {
        ChunkKey key = new ChunkKey(cx, cy, cz);
        if (loaded.containsKey(key)) {
            return false;
        }
        return taskQueue.submit(key, () -> buildChunk(key), priority);
    }

    /**
     * Attempts to raise the priority of an already-pending chunk generation task.
     * No-op if the chunk is not pending or has already started generating.
     */
    public void reprioritiseChunkAsync(int cx, int cy, int cz, int newPriority) {
        ChunkKey key = new ChunkKey(cx, cy, cz);
        taskQueue.reprioritise(key, newPriority);
    }

    /**
     * Drains completed async results into the loaded map.
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
            loaded.put(result.key(), createLoadedChunk(result.value()));
            justLoaded.add(result.value());
        }
        return justLoaded;
    }

    public boolean requestChunkMesh(ChunkKey key) {
        return meshManager.requestMesh(key, this::getLoadedChunk, this::getChunk);
    }

    public void pollCompletedMeshes(ShaderProgram shader) {
        meshManager.pollCompleted(this::getLoadedChunk, shader);
    }

    public void markChunkMeshDirty(ChunkKey key) {
        LoadedChunk loadedChunk = loaded.get(key);
        if (loadedChunk != null) {
            loadedChunk.renderState().setMeshDirty(true);
        }
    }

    public int getPendingMeshCount() {
        return meshManager.getPendingCount();
    }

    // ----------------------------------------------------------------
    // Unloading
    // ----------------------------------------------------------------

    /** Unload a chunk, freeing its data from memory. */
    public void unloadChunk(int cx, int cy, int cz) {
        ChunkKey key = new ChunkKey(cx, cy, cz);
        LoadedChunk loadedChunk = loaded.remove(key);
        taskQueue.forget(key);
        meshManager.forget(key);
        if (loadedChunk != null) {
            meshManager.disposeChunkMeshes(loadedChunk.renderState());
        }
    }

    /**
     * Cancels a pending (not-yet-loaded) generation task for the given chunk.
     * Has no effect if the chunk is already loaded or not in the queue.
     */
    public void cancelPendingChunk(int cx, int cy, int cz) {
        ChunkKey key = new ChunkKey(cx, cy, cz);
        boolean isLoaded = loaded.containsKey(key);
        if (!isLoaded) {
            taskQueue.forget(key);
        }
    }

    /**
     * Returns all chunk keys currently pending generation (submitted but not yet drained).
     */
    public Collection<ChunkKey> getPendingChunkKeys() {
        return taskQueue.getPendingKeys();
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
        List<VoxelChunk> chunks = new ArrayList<>(loaded.size());
        for (LoadedChunk loadedChunk : loaded.values()) {
            chunks.add(loadedChunk.chunk());
        }
        return Collections.unmodifiableList(chunks);
    }

    /**
     * Returns a live, non-copying view of all loaded chunk records.
     * Suitable for read-only iteration on the main thread where no snapshot
     * is needed (e.g. per-frame mesh sweeps).
     */
    public Iterable<LoadedChunk> iterateLoadedChunks() {
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

    /** Shuts down the generation and meshing thread pools.  Call on game exit. */
    public void shutdown() {
        for (LoadedChunk loadedChunk : loaded.values()) {
            meshManager.disposeChunkMeshes(loadedChunk.renderState());
        }
        meshManager.shutdown();
        taskQueue.shutdown();
    }
}
