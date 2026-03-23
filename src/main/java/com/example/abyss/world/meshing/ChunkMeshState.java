package com.example.abyss.world.meshing;

import com.example.abyss.world.render.MeshListRenderState;

/**
 * Render-side state for a loaded chunk.
 * <p>
 * Keeps mesh lifecycle separate from the simulation-facing {@code VoxelChunk}.
 * </p>
 */
public class ChunkMeshState extends MeshListRenderState {

    /** The mesh data produced by the background mesher, waiting to be uploaded to the GPU. Null if none is pending. */
    private MeshData pendingMeshData;

    public MeshData getPendingMeshData() {
        return pendingMeshData;
    }

    public void setPendingMeshData(MeshData pendingMeshData) {
        this.pendingMeshData = pendingMeshData;
    }
}