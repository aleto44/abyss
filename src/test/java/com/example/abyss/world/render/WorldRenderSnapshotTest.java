package com.example.abyss.world.render;

import com.example.abyss.world.AbyssWorld;
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
}
