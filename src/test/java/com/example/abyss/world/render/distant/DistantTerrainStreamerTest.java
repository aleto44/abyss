package com.example.abyss.world.render.distant;

import com.badlogic.gdx.math.Vector3;
import com.example.abyss.WorldConstants;
import com.example.abyss.world.gen.Layer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the boundary-crossing contract of {@link DistantTerrainStreamer}.
 */
class DistantTerrainStreamerTest {

    /** Resolves the band index for a given worldY, matching the resolver formula. */
    private static int bandForWorldY(float worldY) {
        int chunkY        = Math.floorDiv((int) Math.floor(worldY), WorldConstants.CHUNK_SIZE);
        int worldMinChunkY = Math.floorDiv(Layer.DEEP_EXTREMES.getYEnd(), WorldConstants.CHUNK_SIZE);
        int raw = Math.floorDiv(chunkY - worldMinChunkY, DistantTerrainConfig.VERTICAL_DISTANT_TERRAIN_HEIGHT);
        return Math.max(DistantTerrainConfig.WORLD_MIN_BAND_Y,
                        Math.min(DistantTerrainConfig.WORLD_MAX_BAND_Y, raw));
    }

    // ------------------------------------------------------------------
    // First-update behaviour
    // ------------------------------------------------------------------

    @Test
    void firstUpdateSubmitsAllKeysInRadius() {
        TrackingTerrainPort port = new TrackingTerrainPort(List.of());
        DistantTerrainStreamer streamer = new DistantTerrainStreamer(port);

        streamer.updateForPosition(new Vector3(0f, 0f, 0f), 0.016f);

        assertTrue(port.polledCompletedMeshes);
        assertFalse(port.requestedKeys.isEmpty(), "First update must submit macro-chunk slab candidates");
        int originBand = bandForWorldY(0f);
        MacroChunkKey expectedOriginKey = new MacroChunkKey(0, originBand, 0);
        assertTrue(port.requestedKeys.contains(expectedOriginKey),
                "The player's own macro-chunk slab must be submitted on first update");
    }

    @Test
    void firstUpdateCountMatchesExpectedVolume() {
        TrackingTerrainPort port = new TrackingTerrainPort(List.of());
        DistantTerrainStreamer streamer = new DistantTerrainStreamer(port);

        streamer.updateForPosition(new Vector3(0f, 0f, 0f), 0.016f);

        int xzR  = DistantTerrainConfig.LOAD_DISTANCE_MACRO_CHUNKS;
        int yR   = DistantTerrainConfig.LOAD_DISTANCE_BANDS_Y;
        int maxXZ = DistantTerrainConfig.WORLD_MAX_COORD_MACRO_CHUNKS;

        boolean allWithinXZBounds = port.requestedKeys.stream()
                .allMatch(k -> Math.abs(k.macroX()) <= maxXZ && Math.abs(k.macroZ()) <= maxXZ);
        assertTrue(allWithinXZBounds, "All submitted keys must be within XZ world bounds");

        boolean allWithinYBounds = port.requestedKeys.stream()
                .allMatch(k -> k.macroY() >= DistantTerrainConfig.WORLD_MIN_BAND_Y
                            && k.macroY() <= DistantTerrainConfig.WORLD_MAX_BAND_Y);
        assertTrue(allWithinYBounds, "All submitted keys must be within band bounds");

        // At the origin, at most (2*xzR+1)^2 * (2*yR+1) keys; at least 1.
        int maxExpected = (2 * xzR + 1) * (2 * xzR + 1) * (2 * yR + 1);
        assertTrue(port.requestedKeys.size() <= maxExpected);
        assertTrue(port.requestedKeys.size() >= 1);
    }

    // ------------------------------------------------------------------
    // No-op within the same macro-chunk slab
    // ------------------------------------------------------------------

    @Test
    void noAdditionalRequestsWithinSameSlab() {
        TrackingTerrainPort port = new TrackingTerrainPort(List.of());
        DistantTerrainStreamer streamer = new DistantTerrainStreamer(port);

        float tileSize = DistantTerrainConfig.MACRO_CHUNK_SIZE_VOXELS;
        streamer.updateForPosition(new Vector3(0f, 0f, 0f), 0.016f);
        int countAfterFirst = port.requestedKeys.size();

        // Move within the same XZ macro-chunk and same Y band.
        streamer.updateForPosition(new Vector3(tileSize * 0.3f, 0f, tileSize * 0.3f), 0.016f);
        streamer.updateForPosition(new Vector3(tileSize * 0.6f, 0f, tileSize * 0.6f), 0.016f);

        assertEquals(countAfterFirst, port.requestedKeys.size(),
                "No new requests should fire when staying within the same slab");
    }

    // ------------------------------------------------------------------
    // Boundary crossing — new requests
    // ------------------------------------------------------------------

    @Test
    void xzBoundaryChangeTriggersNewRequests() {
        TrackingTerrainPort port = new TrackingTerrainPort(List.of());
        DistantTerrainStreamer streamer = new DistantTerrainStreamer(port);

        streamer.updateForPosition(new Vector3(0f, 0f, 0f), 0.016f);
        int countAfterFirst = port.requestedKeys.size();

        // Cross into the next XZ macro-chunk column.
        float tileSize = DistantTerrainConfig.MACRO_CHUNK_SIZE_VOXELS;
        streamer.updateForPosition(new Vector3(tileSize + 1f, 0f, 0f), 0.016f);

        assertTrue(port.requestedKeys.size() > countAfterFirst,
                "Crossing an XZ macro-chunk boundary must submit new keys");
    }

    @Test
    void yBandCrossingTriggersNewRequests() {
        TrackingTerrainPort port = new TrackingTerrainPort(List.of());
        DistantTerrainStreamer streamer = new DistantTerrainStreamer(port);

        // Start at the world floor (band 0) so the upper load window has headroom;
        // starting near Y=0 would place the player near band 109 of 116, where the
        // upper edge is already capped and no new keys can appear on an upward crossing.
        int worldMinChunkY = Math.floorDiv(Layer.DEEP_EXTREMES.getYEnd(), WorldConstants.CHUNK_SIZE);
        float floorBandWorldY = worldMinChunkY * (float) WorldConstants.CHUNK_SIZE;
        streamer.updateForPosition(new Vector3(0f, floorBandWorldY, 0f), 0.016f);
        int countAfterFirst = port.requestedKeys.size();

        // Move into band 1 — the upper boundary of the load window shifts up, exposing new keys.
        float nextBandWorldY = (worldMinChunkY + DistantTerrainConfig.VERTICAL_DISTANT_TERRAIN_HEIGHT)
                               * (float) WorldConstants.CHUNK_SIZE;
        streamer.updateForPosition(new Vector3(0f, nextBandWorldY, 0f), 0.016f);

        assertTrue(port.requestedKeys.size() > countAfterFirst,
                "Crossing a Y band boundary must submit new keys");
    }

    // ------------------------------------------------------------------
    // Boundary crossing — eviction
    // ------------------------------------------------------------------

    @Test
    void boundaryChangeEvictsStaleKeys() {
        int originBand = bandForWorldY(0f);
        MacroChunkKey staleKey = new MacroChunkKey(0, originBand, 0);
        TrackingTerrainPort port = new TrackingTerrainPort(new ArrayList<>(List.of(staleKey)));
        DistantTerrainStreamer streamer = new DistantTerrainStreamer(port);

        int evictionRadius = DistantTerrainConfig.LOAD_DISTANCE_MACRO_CHUNKS
                             + DistantTerrainConfig.EVICTION_PADDING_MACRO_CHUNKS;
        float tileSize = DistantTerrainConfig.MACRO_CHUNK_SIZE_VOXELS;
        float farX = (evictionRadius + 2) * tileSize + 1f;

        streamer.updateForPosition(new Vector3(farX, 0f, 0f), 0.016f);

        assertTrue(port.removedKeys.contains(staleKey),
                "Keys outside the XZ eviction radius must be evicted at boundary crossing");
    }

    @Test
    void noEvictionWithinLoadDistance() {
        int originBand = bandForWorldY(0f);
        int r = DistantTerrainConfig.LOAD_DISTANCE_MACRO_CHUNKS;
        MacroChunkKey nearKey = new MacroChunkKey(r - 1, originBand, 0);
        TrackingTerrainPort port = new TrackingTerrainPort(new ArrayList<>(List.of(nearKey)));
        DistantTerrainStreamer streamer = new DistantTerrainStreamer(port);

        streamer.updateForPosition(new Vector3(0f, 0f, 0f), 0.016f);

        assertFalse(port.removedKeys.contains(nearKey),
                "Keys within the load distance must never be evicted");
    }

    // ------------------------------------------------------------------
    // World-boundary filter (passthrough from KeyResolver)
    // ------------------------------------------------------------------

    @Test
    void noRequestsBeyondWorldMaxCoord() {
        TrackingTerrainPort port = new TrackingTerrainPort(List.of());
        DistantTerrainStreamer streamer = new DistantTerrainStreamer(port);

        streamer.updateForPosition(new Vector3(0f, 0f, 0f), 0.016f);

        int cap = DistantTerrainConfig.WORLD_MAX_COORD_MACRO_CHUNKS;
        boolean anyOutOfBounds = port.requestedKeys.stream()
                .anyMatch(k -> Math.abs(k.macroX()) > cap || Math.abs(k.macroZ()) > cap);
        assertFalse(anyOutOfBounds,
                "No macro-chunk candidate beyond WORLD_MAX_COORD_MACRO_CHUNKS should be submitted");

        boolean anyOutOfYBounds = port.requestedKeys.stream()
                .anyMatch(k -> k.macroY() < DistantTerrainConfig.WORLD_MIN_BAND_Y
                            || k.macroY() > DistantTerrainConfig.WORLD_MAX_BAND_Y);
        assertFalse(anyOutOfYBounds, "No macro-chunk candidate beyond the band range should be submitted");
    }

    // ------------------------------------------------------------------
    // Test double
    // ------------------------------------------------------------------

    private static final class TrackingTerrainPort implements DistantTerrainPort {
        private final List<MacroChunkKey> loadedKeys;
        final List<MacroChunkKey> requestedKeys = new ArrayList<>();
        final List<MacroChunkKey> removedKeys   = new ArrayList<>();
        boolean polledCompletedMeshes;

        TrackingTerrainPort(List<MacroChunkKey> loadedKeys) {
            this.loadedKeys = new ArrayList<>(loadedKeys);
        }

        @Override public void pollCompletedMeshes() { polledCompletedMeshes = true; }

        @Override public boolean isMacroChunkKnown(MacroChunkKey key) { return loadedKeys.contains(key); }

        @Override
        public boolean requestMacroChunkAsync(MacroChunkKey key) {
            requestedKeys.add(key);
            loadedKeys.add(key);
            return true;
        }

        @Override public int getPendingCount() { return requestedKeys.size(); }

        @Override
        public void removeMacroChunk(MacroChunkKey key) {
            removedKeys.add(key);
            loadedKeys.remove(key);
        }

        @Override public List<MacroChunkKey> getLoadedKeys() { return List.copyOf(loadedKeys); }

        @Override public List<DistantRenderEntry> getRenderEntries() { return List.of(); }
    }
}
