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
     * With hRadius=14, WorldConstants.REGION_CHUNKS=4, REGION_VOXELS_H=128:
     *   LOD_EXCLUSION_H_REGIONS = ceil((14 + 4) * 32 / 128) = ceil(4.5) = 5
     *   dx=2 < 5 and dz=2 < 5 → inside exclusion → no longer in LOD band → must be unloaded.
     */
    @Test
    void regionInsideNearChunkRangeIsUnloadedOnUnloadTick() {
        LodRegionKey loadedKey = new LodRegionKey(2, 0, 2, 1);
        TrackingProxyPort proxyPort = new TrackingProxyPort(List.of(loadedKey));
        CylinderStreamer cylinderStreamer = new CylinderStreamer(14, 8, 20, 12);
        LodStreamer streamer = new LodStreamer(proxyPort, cylinderStreamer);

        streamer.updateForPosition(new Vector3(0f, 0f, 0f), 5.0f);

        assertTrue(proxyPort.removedKeys.contains(loadedKey),
                "Region inside near-chunk exclusion zone should be unloaded when player is in range");
    }

    @Test
    void constructorAcceptsSharedCylinderStreamerWithoutNearChunkStreamerDependency() {
        TrackingProxyPort proxyPort = new TrackingProxyPort(List.of());
        CylinderStreamer cylinderStreamer = new CylinderStreamer(14, 8, 20, 12);
        LodStreamer streamer = new LodStreamer(proxyPort, cylinderStreamer);

        streamer.updateForPosition(new Vector3(0f, 0f, 0f), 0.016f);

        assertTrue(proxyPort.polledCompletedMeshes);
        assertFalse(proxyPort.requestedKeys.isEmpty());
    }

    // ---------------------------------------------------------------
    // Test doubles — implement LodProxyPort, no subclassing needed
    // ---------------------------------------------------------------

    private static final class TrackingProxyPort implements LodProxyPort {
        private final List<LodRegionKey> loadedKeys;
        final List<LodRegionKey> requestedKeys = new ArrayList<>();
        final List<LodRegionKey> removedKeys   = new ArrayList<>();
        boolean polledCompletedMeshes;

        TrackingProxyPort(List<LodRegionKey> loadedKeys) {
            this.loadedKeys = new ArrayList<>(loadedKeys);
        }

        @Override public void pollCompletedMeshes() { polledCompletedMeshes = true; }

        @Override public boolean isRegionKnown(LodRegionKey key) { return loadedKeys.contains(key); }

        @Override
        public boolean requestProxyMeshAsync(LodRegionKey key, int priority) {
            requestedKeys.add(key);
            return true;
        }

        @Override public void reprioritiseProxyMeshAsync(LodRegionKey key, int newPriority) {}

        @Override
        public LodProxyRegion removeRegion(LodRegionKey key) {
            removedKeys.add(key);
            loadedKeys.remove(key);
            return null;
        }

        @Override public List<LodRegionKey> getLoadedRegionKeys() { return List.copyOf(loadedKeys); }

        @Override public List<LodRenderEntry> getRenderEntries() { return List.of(); }
    }
}