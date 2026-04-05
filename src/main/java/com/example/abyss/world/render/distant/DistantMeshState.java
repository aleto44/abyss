package com.example.abyss.world.render.distant;

import com.example.abyss.world.render.MeshListRenderState;

/**
 * Render-side mesh lifecycle state for a distant terrain background region.
 */
public class DistantMeshState extends MeshListRenderState {

    private long approximateMeshMemoryBytes;

    public long getApproximateMeshMemoryBytes() {
        return approximateMeshMemoryBytes;
    }

    public void setApproximateMeshMemoryBytes(long approximateMeshMemoryBytes) {
        this.approximateMeshMemoryBytes = approximateMeshMemoryBytes;
    }
}
