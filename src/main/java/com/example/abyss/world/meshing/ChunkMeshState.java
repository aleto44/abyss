package com.example.abyss.world.meshing;

import com.badlogic.gdx.graphics.Mesh;

import java.util.ArrayList;
import java.util.List;

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
    private List<Mesh> opaqueMeshes = new ArrayList<>();
    private List<Mesh> transparentMeshes = new ArrayList<>();

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

    public List<Mesh> getOpaqueMeshes() {
        return opaqueMeshes;
    }

    public void setOpaqueMeshes(List<Mesh> opaqueMeshes) {
        this.opaqueMeshes = opaqueMeshes;
    }

    public List<Mesh> getTransparentMeshes() {
        return transparentMeshes;
    }

    public void setTransparentMeshes(List<Mesh> transparentMeshes) {
        this.transparentMeshes = transparentMeshes;
    }
}