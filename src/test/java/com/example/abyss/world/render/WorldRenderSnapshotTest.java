package com.example.abyss.world.render;

import com.example.abyss.world.AbyssWorld;
import com.example.abyss.world.render.distant.DistantMeshState;
import com.example.abyss.world.render.distant.DistantRegion;
import com.example.abyss.world.render.distant.DistantRegionKey;
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
        DistantRegionKey key = new DistantRegionKey(1, 2, 3);
        DistantRegion region = new DistantRegion(key, 32.0f, 64.0f, 96.0f, new DistantMeshState());
        world.getDistantTerrainManager().putRegion(region);

        WorldRenderSnapshot renderSnapshot = world.getRenderSnapshot();

        assertTrue(renderSnapshot.nearFieldEntries().isEmpty());
        assertEquals(1, renderSnapshot.distantTerrainEntries().size());
        assertEquals(32.0f, renderSnapshot.distantTerrainEntries().get(0).worldX());
        assertEquals(64.0f, renderSnapshot.distantTerrainEntries().get(0).worldY());
        assertEquals(96.0f, renderSnapshot.distantTerrainEntries().get(0).worldZ());
    }
}