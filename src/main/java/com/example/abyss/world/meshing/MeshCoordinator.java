package com.example.abyss.world.meshing;

import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.example.abyss.WorldConstants;
import com.example.abyss.world.ChunkKey;
import com.example.abyss.world.ChunkManager;
import com.example.abyss.world.LoadedChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Coordinates the full chunk-mesh dirty/queue/poll lifecycle.
 * <p>
 * Owns the logic for determining which chunks need remeshing (including
 * face-neighbor invalidation on edits and boundary invalidation when adjacent
 * chunks finish generation) and drives {@link ChunkManager}'s mesh queue.
 * </p>
 */
public class MeshCoordinator {

    private final ChunkManager chunkManager;

    public MeshCoordinator(ChunkManager chunkManager) {
        this.chunkManager = chunkManager;
    }

    // ----------------------------------------------------------------
    // Dirty-flag helpers
    // ----------------------------------------------------------------

    /**
     * Marks the chunk containing the edited local voxel ({@code lx, ly, lz})
     * and any face-neighbor chunks that share that face boundary as mesh-dirty.
     */
    public void invalidateMeshForEdit(int cx, int cy, int cz, int lx, int ly, int lz) {
        chunkManager.markChunkMeshDirty(new ChunkKey(cx, cy, cz));
        if (lx == 0) {
            chunkManager.markChunkMeshDirty(new ChunkKey(cx - 1, cy, cz));
        }
        if (ly == 0) {
            chunkManager.markChunkMeshDirty(new ChunkKey(cx, cy - 1, cz));
        }
        if (lz == 0) {
            chunkManager.markChunkMeshDirty(new ChunkKey(cx, cy, cz - 1));
        }
        if (lx == WorldConstants.CHUNK_SIZE - 1) {
            chunkManager.markChunkMeshDirty(new ChunkKey(cx + 1, cy, cz));
        }
        if (ly == WorldConstants.CHUNK_SIZE - 1) {
            chunkManager.markChunkMeshDirty(new ChunkKey(cx, cy + 1, cz));
        }
        if (lz == WorldConstants.CHUNK_SIZE - 1) {
            chunkManager.markChunkMeshDirty(new ChunkKey(cx, cy, cz + 1));
        }
    }

    /**
     * Marks the newly-loaded chunk and all six face-adjacent neighbours as
     * mesh-dirty so that boundary seams are rebuilt on every side.
     */
    public void markNeighborsForBoundaryRemesh(int cx, int cy, int cz) {
        chunkManager.markChunkMeshDirty(new ChunkKey(cx,     cy,     cz    ));
        chunkManager.markChunkMeshDirty(new ChunkKey(cx - 1, cy,     cz    ));
        chunkManager.markChunkMeshDirty(new ChunkKey(cx + 1, cy,     cz    ));
        chunkManager.markChunkMeshDirty(new ChunkKey(cx,     cy - 1, cz    ));
        chunkManager.markChunkMeshDirty(new ChunkKey(cx,     cy + 1, cz    ));
        chunkManager.markChunkMeshDirty(new ChunkKey(cx,     cy,     cz - 1));
        chunkManager.markChunkMeshDirty(new ChunkKey(cx,     cy,     cz + 1));
    }

    // ----------------------------------------------------------------
    // Queue / poll
    // ----------------------------------------------------------------

    /**
     * Scans all loaded chunks and submits those that are dirty but not yet
     * queued to the background mesh builder. Iterates the live chunk collection
     * directly — no copy is made.
     */
    public void queueDirtyMeshes() {
        for (LoadedChunk loadedChunk : chunkManager.iterateLoadedChunks()) {
            ChunkMeshState renderState = loadedChunk.renderState();
            boolean needsQueuing = renderState.isMeshDirty() && !renderState.isMeshQueued();
            if (needsQueuing) {
                chunkManager.requestChunkMesh(new ChunkKey(
                        loadedChunk.chunk().getChunkX(),
                        loadedChunk.chunk().getChunkY(),
                        loadedChunk.chunk().getChunkZ()
                ));
            }
        }
    }

    /** Drains completed mesh results on the main thread. Call once per frame. */
    public void pollCompletedMeshes(ShaderProgram shader) {
        chunkManager.pollCompletedMeshes(shader);
    }

    // ----------------------------------------------------------------
    // Render queries
    // ----------------------------------------------------------------

    /** Returns all opaque meshes from currently-loaded chunks. */
    public Iterable<Mesh> getOpaqueMeshes() {
        List<Mesh> meshes = new ArrayList<>();
        for (LoadedChunk loadedChunk : chunkManager.iterateLoadedChunks()) {
            meshes.addAll(loadedChunk.renderState().getOpaqueMeshes());
        }
        return meshes;
    }

    /** Returns all transparent meshes from currently-loaded chunks. */
    public Iterable<Mesh> getTransparentMeshes() {
        List<Mesh> meshes = new ArrayList<>();
        for (LoadedChunk loadedChunk : chunkManager.iterateLoadedChunks()) {
            meshes.addAll(loadedChunk.renderState().getTransparentMeshes());
        }
        return meshes;
    }
}