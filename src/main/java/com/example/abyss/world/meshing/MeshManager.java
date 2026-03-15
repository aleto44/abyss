package com.example.abyss.world.meshing;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.example.abyss.util.KeyedTaskQueue;
import com.example.abyss.world.ChunkKey;
import com.example.abyss.world.LoadedChunk;
import com.example.abyss.world.VoxelChunk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Coordinates async chunk meshing and main-thread LibGDX Mesh promotion.
 */
public class MeshManager {

    /**
     * Maximum number of chunk meshes that will be uploaded to the GPU in a single
     * frame. Capping this prevents the main thread from stalling on
     * {@code BufferUtils.copyJni} when many chunks complete simultaneously (e.g.
     * when descending rapidly through the world).
     */
    private static final int MAX_PROMOTIONS_PER_FRAME = 16;

    private final KeyedTaskQueue<ChunkKey, MeshData> taskQueue;

    public MeshManager() {
        this.taskQueue = KeyedTaskQueue.<ChunkKey, MeshData>builder()
                .threadNamePrefix("ChunkMesh")
                .build();
    }

    public boolean requestMesh(ChunkKey key,
                               Function<ChunkKey, LoadedChunk> loadedChunkLookup,
                               Function<ChunkKey, VoxelChunk> chunkLookup) {
        LoadedChunk loadedChunk = loadedChunkLookup.apply(key);
        if (loadedChunk == null) {
            return false;
        }

        ChunkMeshState renderState = loadedChunk.renderState();
        VoxelChunk center = loadedChunk.chunk();
        if (!renderState.isMeshDirty() || renderState.isMeshQueued()) {
            return false;
        }
        if (!ChunkMesher.hasRequiredNeighbors(key, chunkLookup)) {
            return false;
        }

        VoxelChunk plusX  = chunkLookup.apply(new ChunkKey(key.cx() + 1, key.cy(), key.cz()));
        VoxelChunk plusY  = chunkLookup.apply(new ChunkKey(key.cx(), key.cy() + 1, key.cz()));
        VoxelChunk plusZ  = chunkLookup.apply(new ChunkKey(key.cx(), key.cy(), key.cz() + 1));
        VoxelChunk minusX = chunkLookup.apply(new ChunkKey(key.cx() - 1, key.cy(), key.cz()));
        VoxelChunk minusY = chunkLookup.apply(new ChunkKey(key.cx(), key.cy() - 1, key.cz()));
        VoxelChunk minusZ = chunkLookup.apply(new ChunkKey(key.cx(), key.cy(), key.cz() - 1));

        boolean submitted = taskQueue.submit(key, () -> ChunkMesher.buildMesh(center, plusX, plusY, plusZ, minusX, minusY, minusZ));
        if (submitted) {
            renderState.setMeshQueued(true);
        }
        return submitted;
    }

    /**
     * Drains completed mesh results on the main thread, capped at
     * {@link #MAX_PROMOTIONS_PER_FRAME} GPU uploads per frame to prevent
     * {@code BufferUtils.copyJni} stalls when many chunks finish simultaneously.
     * <p>
     * Deferred chunks (those whose {@link MeshData} was stored in a prior frame
     * but not yet promoted) are drained first. Newly completed results that
     * exceed the budget have their {@link MeshData} stored as pending so they
     * are picked up in a future frame.
     * </p>
     *
     * @param loadedChunkLookup function to resolve a {@link LoadedChunk} by key
     * @param allLoadedChunks   all currently-loaded chunks, used to drain deferred promotions
     */
    public void pollCompleted(Function<ChunkKey, LoadedChunk> loadedChunkLookup,
                              Collection<LoadedChunk> allLoadedChunks) {
        int promotionsRemaining = MAX_PROMOTIONS_PER_FRAME;

        // First, drain any MeshData that was deferred from a prior frame.
        for (LoadedChunk loadedChunk : allLoadedChunks) {
            boolean budgetExhausted = promotionsRemaining <= 0;
            if (budgetExhausted) {
                break;
            }
            ChunkMeshState renderState = loadedChunk.renderState();
            boolean hasDeferredData = renderState.getPendingMeshData() != null && !renderState.isMeshQueued();
            if (hasDeferredData) {
                promotePendingMesh(renderState);
                renderState.setMeshDirty(false);
                promotionsRemaining--;
            }
        }

        // Then collect newly completed results from the background queue.
        List<KeyedTaskQueue.Result<ChunkKey, MeshData>> results = taskQueue.pollCompleted();
        for (KeyedTaskQueue.Result<ChunkKey, MeshData> result : results) {
            LoadedChunk loadedChunk = loadedChunkLookup.apply(result.key());
            if (loadedChunk == null) {
                continue;
            }

            ChunkMeshState renderState = loadedChunk.renderState();
            renderState.setMeshQueued(false);
            renderState.setPendingMeshData(result.value());

            boolean withinBudget = promotionsRemaining > 0;
            if (withinBudget) {
                promotePendingMesh(renderState);
                renderState.setMeshDirty(false);
                promotionsRemaining--;
            }
            // If budget is exhausted, pendingMeshData remains set and will be
            // promoted in a subsequent frame by the deferred-drain pass above.
        }
    }

    public void promotePendingMesh(ChunkMeshState renderState) {
        MeshData pending = renderState.getPendingMeshData();
        if (pending == null) {
            return;
        }

        disposeMeshes(renderState.getOpaqueMeshes());
        disposeMeshes(renderState.getTransparentMeshes());

        renderState.setOpaqueMeshes(buildMeshes(pending.opaqueSections()));
        renderState.setTransparentMeshes(buildMeshes(pending.transparentSections()));
        renderState.setPendingMeshData(null);
    }

    public void forget(ChunkKey key) {
        taskQueue.forget(key);
    }

    public int getPendingCount() {
        return taskQueue.pendingCount();
    }

    public void disposeChunkMeshes(ChunkMeshState renderState) {
        disposeMeshes(renderState.getOpaqueMeshes());
        disposeMeshes(renderState.getTransparentMeshes());
        renderState.setOpaqueMeshes(new ArrayList<>());
        renderState.setTransparentMeshes(new ArrayList<>());
        renderState.setPendingMeshData(null);
        renderState.setMeshQueued(false);
    }

    public void shutdown() {
        taskQueue.shutdown();
    }

    private static List<Mesh> buildMeshes(List<MeshSectionData> sections) {
        List<Mesh> meshes = new ArrayList<>();
        for (MeshSectionData section : sections) {
            Mesh mesh = buildMesh(section);
            if (mesh != null) {
                meshes.add(mesh);
            }
        }
        return meshes;
    }

    private static Mesh buildMesh(MeshSectionData section) {
        if (section == null || section.isEmpty()) {
            return null;
        }
        float[] vertices = section.vertices();
        short[] indices = section.indices();
        Mesh mesh = new Mesh(true,
                vertices.length / MeshData.FLOATS_PER_VERTEX,
                indices.length,
                VertexAttribute.Position(),
                VertexAttribute.Normal(),
                new VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 4, GL20.GL_FLOAT, false, "a_color"));
        mesh.setVertices(vertices);
        mesh.setIndices(indices);
        return mesh;
    }

    private static void disposeMeshes(List<Mesh> meshes) {
        if (meshes == null) {
            return;
        }
        for (Mesh mesh : meshes) {
            if (mesh != null) {
                mesh.dispose();
            }
        }
    }
}