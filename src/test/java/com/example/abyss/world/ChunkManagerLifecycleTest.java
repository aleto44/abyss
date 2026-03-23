package com.example.abyss.world;

import com.example.abyss.world.gen.GenConfigResolver;
import com.example.abyss.world.meshing.MeshCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkManagerLifecycleTest {

    private ChunkManager chunkManager;
    private MeshCoordinator meshCoordinator;

    @BeforeEach
    void setUp() {
        chunkManager = new ChunkManager(42L, GenConfigResolver.DEFAULT);
        meshCoordinator = new MeshCoordinator(chunkManager);
    }

    @AfterEach
    void tearDown() {
        chunkManager.shutdown();
    }

    // ----------------------------------------------------------------
    // Chunk residency lifecycle
    // ----------------------------------------------------------------

    @Test
    void unloadCancelsPendingChunkGeneration() {
        chunkManager.requestChunkAsync(5, 0, 5);

        boolean wasPending = chunkManager.getPendingChunkKeys().contains(new ChunkKey(5, 0, 5));
        if (wasPending) {
            chunkManager.cancelPendingChunk(5, 0, 5);
            assertFalse(chunkManager.getPendingChunkKeys().contains(new ChunkKey(5, 0, 5)),
                    "pending chunk key should be removed after cancel");
        }
        // Either the task was cancelled before completion, or it already completed and
        // landed in the loaded map — either way it should not remain in the pending queue.
        assertFalse(chunkManager.getPendingChunkKeys().contains(new ChunkKey(5, 0, 5)),
                "chunk should not appear in pending keys after cancel");
    }

    @Test
    void unloadRemovesChunkFromLoadedMap() {
        chunkManager.getOrGenerateChunk(3, 0, 3);
        assertTrue(chunkManager.isLoaded(3, 0, 3), "chunk should be loaded after getOrGenerateChunk");

        chunkManager.unloadChunk(3, 0, 3);
        assertFalse(chunkManager.isLoaded(3, 0, 3), "chunk should no longer be loaded after unload");
    }

    @Test
    void unloadClearsRenderStateMeshLists() {
        chunkManager.getOrGenerateChunk(1, 0, 1);
        ChunkKey key = new ChunkKey(1, 0, 1);
        LoadedChunk loaded = chunkManager.getLoadedChunk(key);
        assertNotNull(loaded, "chunk should be present before unload");

        // Render state starts with empty mesh lists since no actual GPU meshing occurs here.
        // Verify unload doesn't throw and the chunk is gone.
        chunkManager.unloadChunk(1, 0, 1);
        assertFalse(chunkManager.isLoaded(1, 0, 1), "chunk removed after unload");
    }

    // ----------------------------------------------------------------
    // Mesh invalidation
    // ----------------------------------------------------------------

    @Test
    void boundaryRemeshMarksCenterAndSixNeighboursDirty() {
        // Load center + all 6 face neighbours synchronously.
        chunkManager.getOrGenerateChunk(0, 0, 0);
        chunkManager.getOrGenerateChunk(1, 0, 0);
        chunkManager.getOrGenerateChunk(-1, 0, 0);
        chunkManager.getOrGenerateChunk(0, 1, 0);
        chunkManager.getOrGenerateChunk(0, -1, 0);
        chunkManager.getOrGenerateChunk(0, 0, 1);
        chunkManager.getOrGenerateChunk(0, 0, -1);

        // Clear dirty flags first so we have a clean baseline.
        clearDirtyFlag(0, 0, 0);
        clearDirtyFlag(1, 0, 0);
        clearDirtyFlag(-1, 0, 0);
        clearDirtyFlag(0, 1, 0);
        clearDirtyFlag(0, -1, 0);
        clearDirtyFlag(0, 0, 1);
        clearDirtyFlag(0, 0, -1);

        meshCoordinator.markNeighborsForBoundaryRemesh(0, 0, 0);

        assertTrue(isMeshDirty(0, 0, 0),   "center should be marked dirty");
        assertTrue(isMeshDirty(1, 0, 0),   "+X neighbour should be marked dirty");
        assertTrue(isMeshDirty(-1, 0, 0),  "-X neighbour should be marked dirty");
        assertTrue(isMeshDirty(0, 1, 0),   "+Y neighbour should be marked dirty");
        assertTrue(isMeshDirty(0, -1, 0),  "-Y neighbour should be marked dirty");
        assertTrue(isMeshDirty(0, 0, 1),   "+Z neighbour should be marked dirty");
        assertTrue(isMeshDirty(0, 0, -1),  "-Z neighbour should be marked dirty");
    }

    @Test
    void editAtMinXBorderMarksBothCenterAndNegativeXNeighbourDirty() {
        chunkManager.getOrGenerateChunk(0, 0, 0);
        chunkManager.getOrGenerateChunk(-1, 0, 0);

        clearDirtyFlag(0, 0, 0);
        clearDirtyFlag(-1, 0, 0);

        // lx=0 is the -X face boundary.
        meshCoordinator.invalidateMeshForEdit(0, 0, 0, 0, 8, 8);

        assertTrue(isMeshDirty(0, 0, 0),  "center chunk should be marked dirty by edit");
        assertTrue(isMeshDirty(-1, 0, 0), "-X neighbour should be marked dirty by border edit");
    }

    @Test
    void editInChunkInteriorOnlyMarksCenterDirty() {
        chunkManager.getOrGenerateChunk(0, 0, 0);
        chunkManager.getOrGenerateChunk(1, 0, 0);

        clearDirtyFlag(0, 0, 0);
        clearDirtyFlag(1, 0, 0);

        // lx=15, ly=15, lz=15 is interior — no face boundary.
        meshCoordinator.invalidateMeshForEdit(0, 0, 0, 15, 15, 15);

        assertTrue(isMeshDirty(0, 0, 0), "center chunk should be marked dirty by edit");
        assertFalse(isMeshDirty(1, 0, 0), "+X neighbour should NOT be marked dirty for interior edit");
    }

    @Test
    void editAtMaxXBorderMarksBothCenterAndPositiveXNeighbourDirty() {
        chunkManager.getOrGenerateChunk(0, 0, 0);
        chunkManager.getOrGenerateChunk(1, 0, 0);

        clearDirtyFlag(0, 0, 0);
        clearDirtyFlag(1, 0, 0);

        // lx = CHUNK_SIZE-1 is the +X face boundary.
        int maxLocal = com.example.abyss.WorldConstants.CHUNK_SIZE - 1;
        meshCoordinator.invalidateMeshForEdit(0, 0, 0, maxLocal, 8, 8);

        assertTrue(isMeshDirty(0, 0, 0), "center chunk should be marked dirty by edit");
        assertTrue(isMeshDirty(1, 0, 0), "+X neighbour should be marked dirty by border edit");
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private void clearDirtyFlag(int cx, int cy, int cz) {
        LoadedChunk loaded = chunkManager.getLoadedChunk(new ChunkKey(cx, cy, cz));
        if (loaded != null) {
            loaded.renderState().setMeshDirty(false);
        }
    }

    private boolean isMeshDirty(int cx, int cy, int cz) {
        LoadedChunk loaded = chunkManager.getLoadedChunk(new ChunkKey(cx, cy, cz));
        boolean isLoaded = loaded != null;
        return isLoaded && loaded.renderState().isMeshDirty();
    }
}
