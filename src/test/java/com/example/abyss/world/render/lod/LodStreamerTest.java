package com.example.abyss.world.render.lod;

import com.badlogic.gdx.math.Vector3;
import com.example.abyss.world.render.CylinderStreamer;
import com.example.abyss.world.render.LodRenderEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodStreamerTest {

    /**
     * A region at region-coords (2, 0, 2) should be removed when the player stands at world origin
     * (region (0,0,0)), because it falls inside the near-field exclusion cylinder.
     * <p>
     * With hRadius=14, vRadius=8, REGION_VOXELS_H=128:
     *   exclusionHRadius = ceil(14 * 32 / 128) = 4
     *   dx=2 < 4 and dz=2 < 4 → inside exclusion → no longer in LOD band → must be unloaded.
     */
    @Test
    void regionInsideNearChunkRangeIsUnloadedOnUnloadTick() {
        int regionVoxelSize = LodProxyGenerator.REGION_VOXELS_H;
        float worldX = 2 * regionVoxelSize;
        float worldY = 0;
        float worldZ = 2 * regionVoxelSize;

        LodRegionKey loadedKey = new LodRegionKey(2, 0, 2, 1);
        LodRenderEntry entry = new LodRenderEntry(worldX, worldY, worldZ, 1, List.of(), List.of());

        TrackingLodProxyManager proxyManager = new TrackingLodProxyManager(List.of(entry));
        // hRadius=14, vRadius=8 → exclusionHRadius = ceil(14*32/128) = 4; region (2,0,2): dx=2 < 4 → inside exclusion
        CylinderStreamer cylinderStreamer = new CylinderStreamer(14, 8, 20, 12);
        LodStreamer streamer = new LodStreamer(proxyManager, cylinderStreamer);

        // Advance past UNLOAD_INTERVAL_SECONDS (4.0f) with player at world origin (region 0,0,0).
        streamer.updateForPosition(new Vector3(0f, 0f, 0f), 5.0f);

        assertTrue(proxyManager.removedKeys.contains(loadedKey),
                "Region inside near-chunk exclusion zone should be unloaded when player is in range");
    }

    @Test
    void constructorAcceptsSharedCylinderStreamerWithoutNearChunkStreamerDependency() {
        FakeLodProxyManager proxyManager = new FakeLodProxyManager();
        CylinderStreamer cylinderStreamer = new CylinderStreamer(14, 8, 20, 12);
        LodStreamer streamer = new LodStreamer(proxyManager, cylinderStreamer);

        streamer.updateForPosition(new Vector3(0f, 0f, 0f), 0.016f);

        assertTrue(proxyManager.polledCompletedMeshes);
        assertFalse(proxyManager.requestedKeys.isEmpty());
    }

    private static final class TrackingLodProxyManager extends LodProxyManager {
        private final List<LodRenderEntry> entries;
        final List<LodRegionKey> removedKeys = new ArrayList<>();

        TrackingLodProxyManager(List<LodRenderEntry> entries) {
            this.entries = entries;
        }

        @Override
        public void pollCompletedMeshes() {}

        @Override
        public boolean isRegionKnown(LodRegionKey key) {
            return true;
        }

        @Override
        public boolean requestProxyMeshAsync(LodRegionKey key) {
            return false;
        }

        @Override
        public LodProxyRegion removeRegion(LodRegionKey key) {
            removedKeys.add(key);
            return null;
        }

        @Override
        public List<LodRenderEntry> getRenderEntries() {
            return entries;
        }
    }

    private static final class FakeLodProxyManager extends LodProxyManager {
        private final List<LodRegionKey> requestedKeys = new ArrayList<>();
        private boolean polledCompletedMeshes;

        @Override
        public void pollCompletedMeshes() {
            polledCompletedMeshes = true;
        }

        @Override
        public boolean isRegionKnown(LodRegionKey key) {
            return false;
        }

        @Override
        public boolean requestProxyMeshAsync(LodRegionKey key) {
            requestedKeys.add(key);
            return true;
        }

        @Override
        public List<LodRenderEntry> getRenderEntries() {
            return List.of();
        }
    }
}