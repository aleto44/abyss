package com.example.abyss.world.meshing;

import com.badlogic.gdx.graphics.Mesh;
import com.example.abyss.WorldConstants;
import com.example.abyss.world.ChunkKey;
import com.example.abyss.world.ChunkManager;
import com.example.abyss.world.ChunkStore;
import com.example.abyss.world.LoadedChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates the full chunk-mesh dirty/queue/poll lifecycle.
 * <p>
 * Owns the {@link MeshManager} (background mesh builder + GPU upload) and
 * operates directly on {@link ChunkStore} for dirty-flag mutations and chunk
 * lookups, keeping mesh concerns entirely out of {@link ChunkManager}.
 * </p>
 */
public class MeshCoordinator {

    private final ChunkManager chunkManager;
    private final ChunkStore store;
    private final MeshManager meshManager;

    public MeshCoordinator(ChunkManager chunkManager) {
        this.chunkManager = chunkManager;
        this.store = chunkManager.getStore();
        this.meshManager = new MeshManager();
    }

    // ----------------------------------------------------------------
    // Dirty-flag helpers
    // ----------------------------------------------------------------

    /**
     * Marks the chunk containing the edited local voxel ({@code lx, ly, lz})
     * and any face-neighbor chunks that share that face boundary as mesh-dirty.
     */
    public void invalidateMeshForEdit(int cx, int cy, int cz, int lx, int ly, int lz) {
        store.markMeshDirty(new ChunkKey(cx, cy, cz));
        if (lx == 0) {
            store.markMeshDirty(new ChunkKey(cx - 1, cy, cz));
        }
        if (ly == 0) {
            store.markMeshDirty(new ChunkKey(cx, cy - 1, cz));
        }
        if (lz == 0) {
            store.markMeshDirty(new ChunkKey(cx, cy, cz - 1));
        }
        if (lx == WorldConstants.CHUNK_SIZE - 1) {
            store.markMeshDirty(new ChunkKey(cx + 1, cy, cz));
        }
        if (ly == WorldConstants.CHUNK_SIZE - 1) {
            store.markMeshDirty(new ChunkKey(cx, cy + 1, cz));
        }
        if (lz == WorldConstants.CHUNK_SIZE - 1) {
            store.markMeshDirty(new ChunkKey(cx, cy, cz + 1));
        }
    }

    /**
     * Marks the newly-loaded chunk and its negative-face neighbors as
     * mesh-dirty so boundary seams are rebuilt.
     */
    public void markNeighborsForBoundaryRemesh(int cx, int cy, int cz) {
        store.markMeshDirty(new ChunkKey(cx, cy, cz));
        store.markMeshDirty(new ChunkKey(cx - 1, cy, cz));
        store.markMeshDirty(new ChunkKey(cx, cy - 1, cz));
        store.markMeshDirty(new ChunkKey(cx, cy, cz - 1));
    }

    // ----------------------------------------------------------------
    // Queue / poll
    // ----------------------------------------------------------------

    /**
     * Scans all loaded chunks and submits those that are dirty but not yet
     * queued to the background mesh builder.
     */
    public void queueDirtyMeshes() {
        List<ChunkKey> keys = new ArrayList<>();
        for (LoadedChunk loadedChunk : store.getLoadedChunks()) {
            boolean isDirtyAndUnqueued = loadedChunk.renderState().isMeshDirty()
                    && !loadedChunk.renderState().isMeshQueued();
            if (isDirtyAndUnqueued) {
                keys.add(new ChunkKey(
                        loadedChunk.chunk().getChunkX(),
                        loadedChunk.chunk().getChunkY(),
                        loadedChunk.chunk().getChunkZ()
                ));
            }
        }
        for (ChunkKey key : keys) {
            meshManager.requestMesh(key, store::getLoaded, chunkManager::getChunk);
        }
    }

    /** Drains completed mesh results on the main thread. Call once per frame. */
    public void pollCompletedMeshes() {
        meshManager.pollCompleted(store::getLoaded, store.entries());
    }

    /** Returns the number of mesh tasks currently pending in the background queue. */
    public int getPendingMeshCount() {
        return meshManager.getPendingCount();
    }

    // ----------------------------------------------------------------
    // Render queries
    // ----------------------------------------------------------------

    /** Returns all opaque meshes from currently-loaded chunks. */
    public Iterable<Mesh> getOpaqueMeshes() {
        List<Mesh> meshes = new ArrayList<>();
        for (LoadedChunk loadedChunk : store.getLoadedChunks()) {
            meshes.addAll(loadedChunk.renderState().getOpaqueMeshes());
        }
        return meshes;
    }

    /** Returns all transparent meshes from currently-loaded chunks. */
    public Iterable<Mesh> getTransparentMeshes() {
        List<Mesh> meshes = new ArrayList<>();
        for (LoadedChunk loadedChunk : store.getLoadedChunks()) {
            meshes.addAll(loadedChunk.renderState().getTransparentMeshes());
        }
        return meshes;
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    /**
     * Disposes all GPU meshes for the given chunk render state and shuts down
     * the background meshing thread pool. Call on game exit.
     */
    public void dispose() {
        for (LoadedChunk loadedChunk : store.getLoadedChunks()) {
            meshManager.disposeChunkMeshes(loadedChunk.renderState());
        }
        meshManager.shutdown();
    }

    /**
     * Forgets any pending mesh task for the given key and disposes its GPU
     * meshes. Called when a chunk is unloaded.
     */
    public void onChunkUnloaded(ChunkKey key, LoadedChunk loadedChunk) {
        meshManager.forget(key);
        if (loadedChunk != null) {
            meshManager.disposeChunkMeshes(loadedChunk.renderState());
        }
    }
}
