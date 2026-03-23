package com.example.abyss.world.render;

import com.example.abyss.world.AbyssWorld;
import com.example.abyss.world.render.lod.LodProxyMeshState;
import com.example.abyss.world.render.lod.LodProxyRegion;
import com.example.abyss.world.render.lod.LodRegionKey;
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
    void renderSnapshotStartsEmptyForNearAndFarField() {
        WorldRenderSnapshot renderSnapshot = world.getRenderSnapshot();

        assertTrue(renderSnapshot.nearFieldEntries().isEmpty());
        assertTrue(renderSnapshot.farFieldEntries().isEmpty());
    }

    @Test
    void renderSnapshotIncludesNearFieldEntriesForLoadedChunks() {
        world.getChunkManager().getOrGenerateChunk(0, 0, 0);
        world.getChunkManager().getOrGenerateChunk(1, 0, 0);

        WorldRenderSnapshot renderSnapshot = world.getRenderSnapshot();

        assertEquals(2, renderSnapshot.nearFieldEntries().size());
        assertTrue(renderSnapshot.farFieldEntries().isEmpty());
    }

    @Test
    void renderSnapshotIncludesFarFieldEntriesFromProxyManager() {
        LodRegionKey key = new LodRegionKey(1, 2, 3, 1);
        LodProxyRegion region = new LodProxyRegion(key, 32.0f, 64.0f, 96.0f, new LodProxyMeshState());
        world.getLodProxyManager().putRegion(region);

        WorldRenderSnapshot renderSnapshot = world.getRenderSnapshot();

        assertTrue(renderSnapshot.nearFieldEntries().isEmpty());
        assertEquals(1, renderSnapshot.farFieldEntries().size());
        assertEquals(32.0f, renderSnapshot.farFieldEntries().get(0).worldX());
        assertEquals(64.0f, renderSnapshot.farFieldEntries().get(0).worldY());
        assertEquals(96.0f, renderSnapshot.farFieldEntries().get(0).worldZ());
        assertEquals(1, renderSnapshot.farFieldEntries().get(0).lodLevel());
    }
}