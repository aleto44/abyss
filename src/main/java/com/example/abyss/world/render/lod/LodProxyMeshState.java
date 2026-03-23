package com.example.abyss.world.render.lod;

import com.example.abyss.world.render.MeshListRenderState;

/**
 * Render-side mesh lifecycle state for a far-field proxy region.
 * <p>
 * This remains separate from near-field chunk meshing so proxy rendering does
 * not take ownership of gameplay chunk state.
 * </p>
 */
public class LodProxyMeshState extends MeshListRenderState {

    private long approximateMeshMemoryBytes;

    public long getApproximateMeshMemoryBytes() {
        return approximateMeshMemoryBytes;
    }

    public void setApproximateMeshMemoryBytes(long approximateMeshMemoryBytes) {
        this.approximateMeshMemoryBytes = approximateMeshMemoryBytes;
    }
}