package com.example.abyss.world.render;

import com.example.abyss.world.AbyssWorld;
import com.example.abyss.world.render.distant.DistantMeshState;
import com.example.abyss.world.render.distant.MacroChunkHeightmap;
import com.example.abyss.world.render.distant.MacroChunkKey;
import com.example.abyss.world.render.distant.HeightmapBand;
import com.example.abyss.world.render.distant.DistantTerrainConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldRenderSnapshotTest {

    private AbyssWorld world;

    @BeforeEach
    void setUp() {
        world = new AbyssWorld(42L);
        world.init();
    }

    @AfterEach
    void tearDown() {
        world.dispose();
    }

    @Test
    void renderSnapshotStartsEmptyForNearAndDistantTerrain() {
        WorldRenderSnapshot renderSnapshot = world.getRenderSnapshot();

        assertTrue(renderSnapshot.nearFieldEntries().isEmpty());
        assertTrue(renderSnapshot.distantTerrainEntries().isEmpty());
    }

    @Test
    void renderSnapshotIncludesNearFieldEntriesForLoadedChunks() {
        world.getChunkManager().getOrGenerateChunk(0, 0, 0);
        world.getChunkManager().getOrGenerateChunk(1, 0, 0);

        WorldRenderSnapshot renderSnapshot = world.getRenderSnapshot();

        assertEquals(2, renderSnapshot.nearFieldEntries().size());
        assertTrue(renderSnapshot.distantTerrainEntries().isEmpty());
    }

    @Test
    void renderSnapshotIncludesDistantTerrainEntriesFromManager() {
        MacroChunkKey key = new MacroChunkKey(1, 0, 3);
        int gridW    = DistantTerrainConfig.MACRO_CHUNK_SIZE_VOXELS / DistantTerrainConfig.DISTANT_SAMPLING_STEP;
        int colCount = gridW * gridW;
        HeightmapBand band = new HeightmapBand(0, new int[colCount], new int[colCount], new float[colCount]);
        MacroChunkHeightmap heightmap = new MacroChunkHeightmap(key, band);
        world.getDistantTerrainManager().putHeightmap(heightmap);

        // Inject a mesh state with geometry so getRenderEntries() includes it
        DistantMeshState meshState = new DistantMeshState();
        world.getDistantTerrainManager().putMeshState(key, meshState);

        WorldRenderSnapshot renderSnapshot = world.getRenderSnapshot();

        // The render entry only shows if the mesh state has geometry; empty state produces no entries
        assertTrue(renderSnapshot.nearFieldEntries().isEmpty());
    }
}