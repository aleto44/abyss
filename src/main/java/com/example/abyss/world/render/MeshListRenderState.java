package com.example.abyss.world.render;

import com.badlogic.gdx.graphics.Mesh;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared mutable render-state base for objects that own mesh lists and participate in
 * a dirty/queued mesh lifecycle.
 * <p>
 * Chunk and proxy states can inherit this shared scheduling and resident-mesh behavior
 * while keeping owner-specific staged-result and accounting fields in their subclasses.
 * </p>
 */
public abstract class MeshListRenderState {

    private boolean meshDirty = true;
    private boolean meshQueued;
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