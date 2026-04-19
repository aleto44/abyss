package com.example.abyss.world.render.distant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistantTerrainManagerTest {

    private static MacroChunkHeightmap buildSingleBandHeightmap(MacroChunkKey key) {
        int gridW    = DistantTerrainConfig.MACRO_CHUNK_SIZE_VOXELS / DistantTerrainConfig.DISTANT_SAMPLING_STEP;
        int colCount = gridW * gridW;
        HeightmapBand band = new HeightmapBand(key.macroY(), new int[colCount], new int[colCount], new float[colCount]);
        return new MacroChunkHeightmap(key, band);
    }

    @Test
    void managerStartsEmpty() {
        DistantTerrainManager manager = new DistantTerrainManager(() -> null);

        assertEquals(0, manager.getLoadedMacroChunkCount());
        assertEquals(0, manager.getActiveBandMeshCount());
        assertEquals(0L, manager.getApproximateMeshMemoryBytes());
        assertTrue(manager.getRenderEntries().isEmpty());
        assertEquals(DistantTerrainDebugStats.empty(), manager.getDebugStats(0L));
    }

    @Test
    void managerTracksMacroChunkAndBuildsRenderEntries() {
        DistantTerrainManager manager = new DistantTerrainManager(() -> null);
        MacroChunkKey key = new MacroChunkKey(2, 0, 5);

        manager.putHeightmap(buildSingleBandHeightmap(key));

        DistantMeshState meshState = new DistantMeshState();
        meshState.setApproximateMeshMemoryBytes(4096L);
        manager.putMeshState(key, meshState);

        assertEquals(1, manager.getLoadedMacroChunkCount());
        assertEquals(1, manager.getActiveBandMeshCount());
        assertEquals(4096L, manager.getApproximateMeshMemoryBytes());
        // getRenderEntries() only returns bands with uploaded geometry
        assertTrue(manager.getRenderEntries().isEmpty(), "No entries without uploaded mesh geometry");
    }

    @Test
    void managerRemovesMacroChunkByKey() {
        DistantTerrainManager manager = new DistantTerrainManager(() -> null);
        MacroChunkKey key = new MacroChunkKey(0, 0, 0);

        manager.putHeightmap(buildSingleBandHeightmap(key));
        manager.putMeshState(key, new DistantMeshState());

        manager.removeMacroChunk(key);

        assertEquals(0, manager.getLoadedMacroChunkCount());
        assertEquals(0, manager.getActiveBandMeshCount());
        assertFalse(manager.isMacroChunkKnown(key));
    }
}
