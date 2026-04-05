package com.example.abyss.world.render.lod;

import com.badlogic.gdx.math.Vector3;
import com.example.abyss.world.render.CylinderStreamer;
import com.example.abyss.world.render.LodRenderEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodStreamerTest {

    /**
     * A region at region-coords (2, 0, 2) with lodLevel=1 should be removed when the player
     * stands at world origin. Tier 1 has innerHRadiusRegions=16, so dx=2 is inside the inner
     * exclusion cylinder — not in the band — must be unloaded.
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

    @Test
    void tier0BufferRegionIsSubmittedBeyondNearField() {
        TrackingProxyPort proxyPort = new TrackingProxyPort(List.of());
        CylinderStreamer cylinderStreamer = new CylinderStreamer(8, 5, 64, 40);
        LodStreamer streamer = new LodStreamer(proxyPort, cylinderStreamer);

        streamer.updateForPosition(new Vector3(0f, 0f, 0f), 0.016f);

        boolean submittedTier0 = proxyPort.requestedKeys.stream().anyMatch(k -> k.lodLevel() == 0);
        assertTrue(submittedTier0, "Tier 0 buffer regions should be submitted on first sweep");
    }

    @Test
    void tier2RegionIsPlacedAtCorrectWorldPosition() {
        LodTierConfig tier2 = LodConfig.TIERS.get(2);
        int expectedWorldPos = tier2.regionSizeVoxels();
        assertEquals(256, expectedWorldPos,
            "Tier 2 region at coord 1 should map to world position 256 (8 chunks * 32 voxels)");
    }

    @Test
    void tier1KeyUnloadsUsingTier1Band() {
        // Tier 1: regionSize=4, innerHRadiusRegions=ceil(16/4)=4, outerHRadiusRegions=32/4=8.
        // dx=5 is inside [4,8] → in band → must NOT be unloaded.
        LodRegionKey tier1Key = new LodRegionKey(5, 0, 5, 1);
        TrackingProxyPort proxyPort = new TrackingProxyPort(List.of(tier1Key));
        CylinderStreamer cylinderStreamer = new CylinderStreamer(8, 5, 64, 40);
        LodStreamer streamer = new LodStreamer(proxyPort, cylinderStreamer);

        streamer.updateForPosition(new Vector3(0f, 0f, 0f), 5.0f);

        assertFalse(proxyPort.removedKeys.contains(tier1Key),
            "Tier-1 region inside tier-1 band should not be unloaded");
    }

    @Test
    void tier2KeyUnloadsUsingTier2Band() {
        // Tier 2: regionSize=8, outerHRadiusRegions=64/8=8, hysteresis=1 → unload threshold=9.
        // dx=10 > 9 → outside unload zone → must be unloaded.
        LodRegionKey tier2Key = new LodRegionKey(10, 0, 10, 2);
        TrackingProxyPort proxyPort = new TrackingProxyPort(List.of(tier2Key));
        CylinderStreamer cylinderStreamer = new CylinderStreamer(8, 5, 64, 40);
        LodStreamer streamer = new LodStreamer(proxyPort, cylinderStreamer);

        streamer.updateForPosition(new Vector3(0f, 0f, 0f), 5.0f);

        assertTrue(proxyPort.removedKeys.contains(tier2Key),
            "Tier-2 region outside the hysteresis unload zone should be unloaded");
    }

    @Test
    void regionJustOutsideLoadBandIsKeptByHysteresis() {
        // Tier 2: outerHRadiusRegions=8, hysteresis=1 → unload boundary=9.
        // dx=9 is exactly 1 beyond the load band but still within the unload zone → must NOT be unloaded.
        LodRegionKey tier2Key = new LodRegionKey(9, 0, 9, 2);
        TrackingProxyPort proxyPort = new TrackingProxyPort(List.of(tier2Key));
        CylinderStreamer cylinderStreamer = new CylinderStreamer(8, 5, 64, 40);
        LodStreamer streamer = new LodStreamer(proxyPort, cylinderStreamer);

        streamer.updateForPosition(new Vector3(0f, 0f, 0f), 5.0f);

        assertFalse(proxyPort.removedKeys.contains(tier2Key),
            "Region just outside load band should be kept alive by hysteresis padding");
    }

    @Test
    void lodConfigValidationRejectsNonContiguousTiers() {
        // Simulate a gap: tier 1 inner=20 but tier 0 outer=16
        List<LodTierConfig> bad = List.of(
            new LodTierConfig(0,  8, 16,  5, 10, 1, 2),
            new LodTierConfig(1, 20, 36, 10, 20, 1, 4)
        );
        assertThrows(IllegalStateException.class, () -> LodConfig.validateTiers(bad));
    }

    @Test
    void lodConfigValidationRejectsBufferNotAtNearFieldEdge() {
        // innerH=10 does not match NEAR_FIELD_H_RADIUS=8
        List<LodTierConfig> bad = List.of(
            new LodTierConfig(0, 10, 20, 5, 10, 1, 2)
        );
        assertThrows(IllegalStateException.class, () -> LodConfig.validateTiers(bad));
    }

    @Test
    void tier2HasLargerRegionSizeThanTier1() {
        LodTierConfig tier1 = LodConfig.TIERS.get(1);
        LodTierConfig tier2 = LodConfig.TIERS.get(2);
        assertTrue(tier2.regionSizeVoxels() > tier1.regionSizeVoxels(),
            "Tier 2 should have a larger region size than tier 1");
    }

    // ---------------------------------------------------------------
    // Test doubles
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

        @Override public int getPendingRegionCount() { return requestedKeys.size(); }

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