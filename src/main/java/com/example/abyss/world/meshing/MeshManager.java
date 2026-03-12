package com.example.abyss.world.meshing;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.example.abyss.util.KeyedTaskQueue;
import com.example.abyss.world.ChunkKey;
import com.example.abyss.world.LoadedChunk;
import com.example.abyss.world.VoxelChunk;

import java.util.List;
import java.util.function.Function;

/**
 * Coordinates async chunk meshing and main-thread LibGDX Mesh promotion.
 */
public class MeshManager {

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

        VoxelChunk plusX = chunkLookup.apply(new ChunkKey(key.cx() + 1, key.cy(), key.cz()));
        VoxelChunk plusY = chunkLookup.apply(new ChunkKey(key.cx(), key.cy() + 1, key.cz()));
        VoxelChunk plusZ = chunkLookup.apply(new ChunkKey(key.cx(), key.cy(), key.cz() + 1));

        boolean submitted = taskQueue.submit(key, () -> ChunkMesher.buildMesh(center, plusX, plusY, plusZ));
        if (submitted) {
            renderState.setMeshQueued(true);
        }
        return submitted;
    }

    public void pollCompleted(Function<ChunkKey, LoadedChunk> loadedChunkLookup) {
        List<KeyedTaskQueue.Result<ChunkKey, MeshData>> results = taskQueue.pollCompleted();
        for (KeyedTaskQueue.Result<ChunkKey, MeshData> result : results) {
            LoadedChunk loadedChunk = loadedChunkLookup.apply(result.key());
            if (loadedChunk == null) {
                continue;
            }

            ChunkMeshState renderState = loadedChunk.renderState();
            renderState.setMeshQueued(false);
            renderState.setPendingMeshData(result.value());
            promotePendingMesh(renderState);
            renderState.setMeshDirty(false);
        }
    }

    public void promotePendingMesh(ChunkMeshState renderState) {
        MeshData pending = renderState.getPendingMeshData();
        if (pending == null) {
            return;
        }

        disposeMesh(renderState.getOpaqueMesh());
        disposeMesh(renderState.getTransparentMesh());

        renderState.setOpaqueMesh(buildMesh(pending.opaqueVertices(), pending.opaqueIndices()));
        renderState.setTransparentMesh(buildMesh(pending.transparentVertices(), pending.transparentIndices()));
        renderState.setPendingMeshData(null);
    }

    public void cancel(ChunkKey key) {
        taskQueue.cancel(key);
    }

    public void forget(ChunkKey key) {
        taskQueue.forget(key);
    }

    public int getPendingCount() {
        return taskQueue.pendingCount();
    }

    public void disposeChunkMeshes(ChunkMeshState renderState) {
        disposeMesh(renderState.getOpaqueMesh());
        disposeMesh(renderState.getTransparentMesh());
        renderState.setOpaqueMesh(null);
        renderState.setTransparentMesh(null);
        renderState.setPendingMeshData(null);
        renderState.setMeshQueued(false);
    }

    public void shutdown() {
        taskQueue.shutdown();
    }

    private static Mesh buildMesh(float[] vertices, short[] indices) {
        if (vertices.length == 0 || indices.length == 0) {
            return null;
        }
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

    private static void disposeMesh(Mesh mesh) {
        if (mesh != null) {
            mesh.dispose();
        }
    }
}