package com.example.abyss.world.render;

import com.badlogic.gdx.graphics.Mesh;
import com.example.abyss.world.meshing.MeshData;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared mutable render-state base for objects that own mesh lists and participate in
 * a dirty/queued/staged mesh lifecycle.
 * <p>
 * Chunk and proxy states inherit shared scheduling, resident-mesh, and staging behavior.
 * Subclasses may add owner-specific accounting fields (e.g. memory estimates).
 * </p>
 */
public abstract class MeshListRenderState {

    private boolean meshDirty = true;
    private boolean meshQueued;
    private List<Mesh> opaqueMeshes = new ArrayList<>();
    private List<Mesh> transparentMeshes = new ArrayList<>();

    /** CPU-side mesh data waiting to be uploaded to the GPU. Null when no upload is pending. */
    private MeshData pendingMeshData;

    public MeshData getPendingMeshData() {
        return pendingMeshData;
    }

    public void setPendingMeshData(MeshData pendingMeshData) {
        this.pendingMeshData = pendingMeshData;
    }

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