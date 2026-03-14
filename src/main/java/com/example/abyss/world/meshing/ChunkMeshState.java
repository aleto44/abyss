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

    /** True when the chunk's voxel data has changed and the mesh needs to be rebuilt before rendering. */
    private boolean meshDirty = true;
    /** True when this chunk has already been submitted to the meshing queue and is awaiting a result. */
    private boolean meshQueued;
    /** The mesh data produced by the background mesher, waiting to be uploaded to the GPU. Null if none is pending. */
    private MeshData pendingMeshData;
    /** GPU meshes for fully opaque geometry in this chunk. */
    private List<Mesh> opaqueMeshes = new ArrayList<>();
    /** GPU meshes for translucent/transparent geometry in this chunk. */
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