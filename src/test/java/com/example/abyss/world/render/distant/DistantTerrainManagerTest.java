package com.example.abyss.world.render.distant;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistantTerrainManagerTest {

    @Test
    void managerStartsEmpty() {
        DistantTerrainManager manager = new DistantTerrainManager(() -> null);

        assertEquals(0, manager.getLoadedRegionCount());
        assertEquals(0, manager.getPendingMeshCount());
        assertEquals(0L, manager.getApproximateLoadedMeshMemoryBytes());
        assertTrue(manager.getRenderEntries().isEmpty());
        assertEquals(DistantTerrainDebugStats.empty(), manager.getDebugStats(0L));
    }

    @Test
    void managerTracksRegionStateAndBuildsRenderEntries() {
        DistantTerrainManager manager = new DistantTerrainManager(() -> null);
        DistantRegionKey key = new DistantRegionKey(2, -1, 5);
        DistantMeshState meshState = new DistantMeshState();
        meshState.setMeshQueued(true);
        meshState.setApproximateMeshMemoryBytes(4096L);
        DistantRegion region = new DistantRegion(key, 64.0f, -32.0f, 160.0f, meshState);

        manager.putRegion(region);

        DistantRegion storedRegion = manager.getRegion(key);
        List<DistantRenderEntry> renderEntries = manager.getRenderEntries();
        DistantTerrainDebugStats debugStats = manager.getDebugStats(0L);

        assertNotNull(storedRegion);
        assertEquals(1, manager.getLoadedRegionCount());
        assertEquals(1, manager.getPendingMeshCount());
        assertEquals(4096L, manager.getApproximateLoadedMeshMemoryBytes());
        assertEquals(1, renderEntries.size());
        assertEquals(64.0f, renderEntries.get(0).worldX());
        assertEquals(-32.0f, renderEntries.get(0).worldY());
        assertEquals(160.0f, renderEntries.get(0).worldZ());
        assertTrue(renderEntries.get(0).opaqueMeshes().isEmpty());
        assertTrue(renderEntries.get(0).transparentMeshes().isEmpty());
        assertEquals(1, debugStats.loadedRegionCount());
        assertEquals(1, debugStats.pendingMeshCount());
        assertEquals(4096L, debugStats.approximateMeshMemoryBytes());
    }

    @Test
    void managerRemovesRegionsByKey() {
        DistantTerrainManager manager = new DistantTerrainManager(() -> null);
        DistantRegionKey key = new DistantRegionKey(0, 0, 0);
        DistantRegion region = new DistantRegion(key, 0.0f, 0.0f, 0.0f, new DistantMeshState());
        manager.putRegion(region);

        DistantRegion removedRegion = manager.removeRegion(key);

        assertEquals(region, removedRegion);
        assertNull(manager.getRegion(key));
        assertEquals(0, manager.getLoadedRegionCount());
    }
}
