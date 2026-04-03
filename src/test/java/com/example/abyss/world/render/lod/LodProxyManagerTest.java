package com.example.abyss.world.render.lod;

import com.example.abyss.world.render.LodRenderEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodProxyManagerTest {

    @Test
    void managerStartsEmpty() {
        LodProxyManager manager = new LodProxyManager(() -> null);

        assertEquals(0, manager.getLoadedRegionCount());
        assertEquals(0, manager.getPendingMeshCount());
        assertEquals(0L, manager.getApproximateLoadedMeshMemoryBytes());
        assertTrue(manager.getRenderEntries().isEmpty());
        assertEquals(LodDebugStats.empty(), manager.getDebugStats(0L));
    }

    @Test
    void managerTracksProxyRegionStateAndBuildsRenderEntries() {
        LodProxyManager manager = new LodProxyManager(() -> null);
        LodRegionKey key = new LodRegionKey(2, -1, 5, 1);
        LodProxyMeshState meshState = new LodProxyMeshState();
        meshState.setMeshQueued(true);
        meshState.setApproximateMeshMemoryBytes(4096L);
        LodProxyRegion region = new LodProxyRegion(key, 64.0f, -32.0f, 160.0f, meshState);

        manager.putRegion(region);

        LodProxyRegion storedRegion = manager.getRegion(key);
        List<LodRenderEntry> renderEntries = manager.getRenderEntries();
        LodDebugStats debugStats = manager.getDebugStats(0L);

        assertNotNull(storedRegion);
        assertEquals(1, manager.getLoadedRegionCount());
        assertEquals(1, manager.getPendingMeshCount());
        assertEquals(4096L, manager.getApproximateLoadedMeshMemoryBytes());
        assertEquals(1, renderEntries.size());
        assertEquals(64.0f, renderEntries.get(0).worldX());
        assertEquals(-32.0f, renderEntries.get(0).worldY());
        assertEquals(160.0f, renderEntries.get(0).worldZ());
        assertEquals(1, renderEntries.get(0).lodLevel());
        assertTrue(renderEntries.get(0).opaqueMeshes().isEmpty());
        assertTrue(renderEntries.get(0).transparentMeshes().isEmpty());
        assertEquals(1, debugStats.loadedProxyRegionCount());
        assertEquals(1, debugStats.pendingProxyMeshCount());
        assertEquals(4096L, debugStats.approximateLoadedProxyMeshMemoryBytes());
    }

    @Test
    void managerRemovesRegionsByKey() {
        LodProxyManager manager = new LodProxyManager(() -> null);
        LodRegionKey key = new LodRegionKey(0, 0, 0, 2);
        LodProxyRegion region = new LodProxyRegion(key, 0.0f, 0.0f, 0.0f, new LodProxyMeshState());
        manager.putRegion(region);

        LodProxyRegion removedRegion = manager.removeRegion(key);

        assertEquals(region, removedRegion);
        assertNull(manager.getRegion(key));
        assertEquals(0, manager.getLoadedRegionCount());
    }
}