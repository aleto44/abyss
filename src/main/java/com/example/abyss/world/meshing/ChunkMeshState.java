package com.example.abyss.world.meshing;

import com.badlogic.gdx.graphics.Mesh;

/**
 * Render-side state for a loaded chunk.
 * <p>
 * Keeps mesh lifecycle separate from the simulation-facing {@code VoxelChunk}.
 * </p>
 */
public class ChunkMeshState {

    private boolean meshDirty = true;
    private boolean meshQueued;
    private MeshData pendingMeshData;
    private Mesh opaqueMesh;
    private Mesh transparentMesh;

    public boolean isMeshDirty() {
        return meshDirty;
    }

    public void setMeshDirty(boolean meshDirty) {
        this.meshDirty = meshDirty;
    }

    public boolean isMeshQueued() {
        return meshQueued;
    }

    public void setMeshQueued(boolean meshQueued) {
        this.meshQueued = meshQueued;
    }

    public MeshData getPendingMeshData() {
        return pendingMeshData;
    }

    public void setPendingMeshData(MeshData pendingMeshData) {
        this.pendingMeshData = pendingMeshData;
    }

    public Mesh getOpaqueMesh() {
        return opaqueMesh;
    }

    public void setOpaqueMesh(Mesh opaqueMesh) {
        this.opaqueMesh = opaqueMesh;
    }

    public Mesh getTransparentMesh() {
        return transparentMesh;
    }

    public void setTransparentMesh(Mesh transparentMesh) {
        this.transparentMesh = transparentMesh;
    }
}
