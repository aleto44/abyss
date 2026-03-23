package com.example.abyss.world.render;

import com.example.abyss.world.meshing.ChunkMeshState;
import com.example.abyss.world.render.lod.LodProxyMeshState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshListRenderStateTest {

    @Test
    void chunkMeshStateInheritsSharedDirtyAndQueuedLifecycle() {
        ChunkMeshState renderState = new ChunkMeshState();

        assertTrue(renderState.isMeshDirty());
        assertFalse(renderState.isMeshQueued());

        renderState.setMeshDirty(false);
        renderState.setMeshQueued(true);

        assertFalse(renderState.isMeshDirty());
        assertTrue(renderState.isMeshQueued());
    }

    @Test
    void lodProxyMeshStateInheritsSharedDirtyAndQueuedLifecycle() {
        LodProxyMeshState renderState = new LodProxyMeshState();

        assertTrue(renderState.isMeshDirty());
        assertFalse(renderState.isMeshQueued());

        renderState.setMeshDirty(false);
        renderState.setMeshQueued(true);

        assertFalse(renderState.isMeshDirty());
        assertTrue(renderState.isMeshQueued());
    }
}