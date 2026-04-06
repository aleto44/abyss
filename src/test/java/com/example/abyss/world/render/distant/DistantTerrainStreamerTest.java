package com.example.abyss.world.render.distant;

import com.badlogic.gdx.math.Vector3;
import com.example.abyss.world.render.CylinderStreamer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the core contract of {@link DistantTerrainStreamer}:
 * regions are never removed during normal play — only the safety eviction path removes them.
 */
class DistantTerrainStreamerTest {

    @Test
    void initialSweepSubmitsRegionsCandidates() {
        TrackingTerrainPort port = new TrackingTerrainPort(List.of());
        DistantTerrainStreamer streamer = new DistantTerrainStreamer(port, new CylinderStreamer(8, 5));

        streamer.updateForPosition(new Vector3(0f, 0f, 0f), 0.016f);

        assertTrue(port.polledCompletedMeshes);
        assertFalse(port.requestedKeys.isEmpty(), "Initial sweep should submit region candidates");
    }

    @Test
    void regionsAreNeverRemovedDuringNormalPlay() {
        DistantRegionKey preLoadedKey = new DistantRegionKey(0, 0, 0);
        TrackingTerrainPort port = new TrackingTerrainPort(List.of(preLoadedKey));
        DistantTerrainStreamer streamer = new DistantTerrainStreamer(port, new CylinderStreamer(8, 5));

        // Move the player — even a large displacement must not remove a previously loaded region
        streamer.updateForPosition(new Vector3(0f, 0f, 0f), 0.016f);
        streamer.updateForPosition(new Vector3(500f, 0f, 500f), 0.016f);

        assertFalse(port.removedKeys.contains(preLoadedKey),
                "Regions must never be removed during normal streaming; only safety eviction may remove them");
    }

    @Test
    void safetyEvictionDoesNotFireBeforeInterval() {
        DistantRegionKey farKey = new DistantRegionKey(9999, 0, 9999);
        TrackingTerrainPort port = new TrackingTerrainPort(List.of(farKey));
        DistantTerrainStreamer streamer = new DistantTerrainStreamer(port, new CylinderStreamer(8, 5));

        streamer.updateForPosition(new Vector3(0f, 0f, 0f), DistantTerrainConfig.EVICTION_INTERVAL_SECONDS - 1f);

        assertFalse(port.removedKeys.contains(farKey),
                "Safety eviction must not fire before EVICTION_INTERVAL_SECONDS have elapsed");
    }

    @Test
    void safetyEvictionRemovesTrulyDistantRegionsAfterInterval() {
        int farCoord = DistantTerrainConfig.EVICTION_H_RADIUS_REGIONS + 10;
        DistantRegionKey distantKey = new DistantRegionKey(farCoord, 0, farCoord);
        TrackingTerrainPort port = new TrackingTerrainPort(List.of(distantKey));
        DistantTerrainStreamer streamer = new DistantTerrainStreamer(port, new CylinderStreamer(8, 5));

        streamer.updateForPosition(new Vector3(0f, 0f, 0f), DistantTerrainConfig.EVICTION_INTERVAL_SECONDS + 1f);

        assertTrue(port.removedKeys.contains(distantKey),
                "Regions beyond EVICTION_H_RADIUS_REGIONS must be evicted after the interval fires");
    }

    @Test
    void safetyEvictionKeepsRegionsWithinEvictionRadius() {
        int safeCoord = DistantTerrainConfig.EVICTION_H_RADIUS_REGIONS - 1;
        DistantRegionKey nearEnoughKey = new DistantRegionKey(safeCoord, 0, 0);
        TrackingTerrainPort port = new TrackingTerrainPort(List.of(nearEnoughKey));
        DistantTerrainStreamer streamer = new DistantTerrainStreamer(port, new CylinderStreamer(8, 5));

        streamer.updateForPosition(new Vector3(0f, 0f, 0f), DistantTerrainConfig.EVICTION_INTERVAL_SECONDS + 1f);

        assertFalse(port.removedKeys.contains(nearEnoughKey),
                "Regions within EVICTION_H_RADIUS_REGIONS must never be evicted");
    }

    @Test
    void horizonSweepSubmitsRegionsNearOrigin() {
        TrackingTerrainPort port = new TrackingTerrainPort(List.of());
        DistantTerrainStreamer streamer = new DistantTerrainStreamer(port, new CylinderStreamer(8, 5));

        streamer.updateForPosition(new Vector3(0f, 0f, 0f), 0.016f);

        boolean originSubmitted = port.requestedKeys.stream()
                .anyMatch(k -> k.regionX() == 0 && k.regionY() == 0 && k.regionZ() == 0);
        assertTrue(originSubmitted, "The player's own region should be submitted on first sweep");
    }

    // ---------------------------------------------------------------
    // Test double
    // ---------------------------------------------------------------

    private static final class TrackingTerrainPort implements DistantTerrainPort {
        private final List<DistantRegionKey> loadedKeys;
        final List<DistantRegionKey> requestedKeys = new ArrayList<>();
        final List<DistantRegionKey> removedKeys   = new ArrayList<>();
        boolean polledCompletedMeshes;

        TrackingTerrainPort(List<DistantRegionKey> loadedKeys) {
            this.loadedKeys = new ArrayList<>(loadedKeys);
        }

        @Override public void pollCompletedMeshes() { polledCompletedMeshes = true; }

        @Override public boolean isRegionKnown(DistantRegionKey key) { return loadedKeys.contains(key); }

        @Override
        public boolean requestRegionAsync(DistantRegionKey key) {
            requestedKeys.add(key);
            loadedKeys.add(key);
            return true;
        }

        @Override public int getPendingRegionCount() { return requestedKeys.size(); }

        @Override
        public DistantRegion removeRegion(DistantRegionKey key) {
            removedKeys.add(key);
            loadedKeys.remove(key);
            return null;
        }

        @Override public List<DistantRegionKey> getLoadedRegionKeys() { return List.copyOf(loadedKeys); }

        @Override public List<DistantRenderEntry> getRenderEntries() { return List.of(); }
    }
}
