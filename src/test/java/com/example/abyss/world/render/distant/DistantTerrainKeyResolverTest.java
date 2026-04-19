package com.example.abyss.world.render.distant;

import com.example.abyss.WorldConstants;
import com.example.abyss.world.gen.Layer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistantTerrainKeyResolverTest {

    /** Computes the band index that world Y=0 maps to, matching the resolver's own formula. */
    private static int bandForWorldY(float worldY) {
        int chunkY       = Math.floorDiv((int) Math.floor(worldY), WorldConstants.CHUNK_SIZE);
        int worldMinChunkY = Math.floorDiv(Layer.DEEP_EXTREMES.getYEnd(), WorldConstants.CHUNK_SIZE);
        int raw = Math.floorDiv(chunkY - worldMinChunkY, DistantTerrainConfig.VERTICAL_DISTANT_TERRAIN_HEIGHT);
        return Math.max(DistantTerrainConfig.WORLD_MIN_BAND_Y,
                        Math.min(DistantTerrainConfig.WORLD_MAX_BAND_Y, raw));
    }

    @Test
    void normalizeAtOriginReturnsExpectedKey() {
        int expectedBand = bandForWorldY(0f);
        MacroChunkKey key = DistantTerrainKeyResolver.normalizeToMacroChunkKey(0f, 0f, 0f);
        assertEquals(new MacroChunkKey(0, expectedBand, 0), key);
    }

    @Test
    void normalizePositiveCoords() {
        float tileSize = DistantTerrainConfig.MACRO_CHUNK_SIZE_VOXELS;
        float worldX = tileSize + 1f;
        float worldZ = 2f * tileSize + 5f;
        float worldY = 0f;
        int expectedBand = bandForWorldY(worldY);
        MacroChunkKey key = DistantTerrainKeyResolver.normalizeToMacroChunkKey(worldX, worldY, worldZ);
        assertEquals(new MacroChunkKey(1, expectedBand, 2), key);
    }

    @Test
    void normalizeNegativeXZCoords() {
        float tileSize = DistantTerrainConfig.MACRO_CHUNK_SIZE_VOXELS;
        int expectedBand = bandForWorldY(0f);
        MacroChunkKey key = DistantTerrainKeyResolver.normalizeToMacroChunkKey(-1f, 0f, -1f);
        assertEquals(new MacroChunkKey(-1, expectedBand, -1), key);

        // Exactly on the negative tile boundary maps to -1.
        MacroChunkKey keyAtBoundary = DistantTerrainKeyResolver.normalizeToMacroChunkKey(-tileSize, 0f, -tileSize);
        assertEquals(new MacroChunkKey(-1, expectedBand, -1), keyAtBoundary);
    }

    @Test
    void normalizeYClampsToWorldBounds() {
        // Y far above the world clamps to WORLD_MAX_BAND_Y.
        MacroChunkKey topKey = DistantTerrainKeyResolver.normalizeToMacroChunkKey(0f, 1_000_000f, 0f);
        assertEquals(DistantTerrainConfig.WORLD_MAX_BAND_Y, topKey.macroY());

        // Y far below the world clamps to WORLD_MIN_BAND_Y.
        MacroChunkKey bottomKey = DistantTerrainKeyResolver.normalizeToMacroChunkKey(0f, -1_000_000f, 0f);
        assertEquals(DistantTerrainConfig.WORLD_MIN_BAND_Y, bottomKey.macroY());
    }

    @Test
    void computeRequestSetRadiusZeroReturnsSingleKey() {
        int band = bandForWorldY(0f);
        MacroChunkKey origin = new MacroChunkKey(0, band, 0);
        List<MacroChunkKey> keys = DistantTerrainKeyResolver.computeRequestSet(origin, 0, 0);
        assertEquals(1, keys.size());
        assertEquals(origin, keys.get(0));
    }

    @Test
    void computeRequestSetXZOnly() {
        // yRadius=0 collapses the request set to a flat XZ square (the origin band only).
        int band = bandForWorldY(0f);
        MacroChunkKey origin = new MacroChunkKey(0, band, 0);
        List<MacroChunkKey> keys = DistantTerrainKeyResolver.computeRequestSet(origin, 1, 0);
        // 3×3 XZ × 1 Y = 9 keys (all at the same band index).
        assertEquals(9, keys.size());
        boolean allSameBand = keys.stream().allMatch(k -> k.macroY() == band);
        assertTrue(allSameBand, "All keys in a yRadius=0 set must share the origin band");
    }

    @Test
    void computeRequestSetIncludesYBands() {
        int band = bandForWorldY(0f);
        MacroChunkKey origin = new MacroChunkKey(0, band, 0);
        List<MacroChunkKey> keys = DistantTerrainKeyResolver.computeRequestSet(origin, 1, 1);
        // 3×3 XZ × 3 Y = 27 keys (before world-bound clipping).
        int expectedMin = 1; // at least the origin
        assertTrue(keys.size() >= expectedMin);
        boolean hasVariedBands = keys.stream().mapToInt(MacroChunkKey::macroY).distinct().count() > 1;
        assertTrue(hasVariedBands, "A yRadius=1 set must include adjacent band indices");
    }

    @Test
    void computeRequestSetClipsAtWorldBoundary() {
        int halfExt = DistantTerrainConfig.WORLD_MAX_COORD_MACRO_CHUNKS;
        MacroChunkKey cornerOrigin = new MacroChunkKey(halfExt, bandForWorldY(0f), halfExt);
        List<MacroChunkKey> keys = DistantTerrainKeyResolver.computeRequestSet(cornerOrigin, 2, 1);

        boolean anyOutOfXZBounds = keys.stream().anyMatch(
                k -> Math.abs(k.macroX()) > halfExt || Math.abs(k.macroZ()) > halfExt);
        assertFalse(anyOutOfXZBounds, "Keys outside the XZ world half-extent must be excluded");

        boolean anyOutOfYBounds = keys.stream().anyMatch(
                k -> k.macroY() < DistantTerrainConfig.WORLD_MIN_BAND_Y
                  || k.macroY() > DistantTerrainConfig.WORLD_MAX_BAND_Y);
        assertFalse(anyOutOfYBounds, "Keys outside the band range must be excluded");

        assertTrue(keys.contains(cornerOrigin), "Origin itself must always be in the set");
    }

    @Test
    void computeRequestSetOrderedNearestFirst() {
        int band = bandForWorldY(0f);
        MacroChunkKey origin = new MacroChunkKey(0, band, 0);
        List<MacroChunkKey> keys = DistantTerrainKeyResolver.computeRequestSet(origin, 3, 2);

        assertEquals(origin, keys.get(0), "First key must be the origin");

        // 3D Chebyshev distances must be non-decreasing.
        int prevDist = 0;
        for (MacroChunkKey key : keys) {
            int dist = Math.max(Math.max(Math.abs(key.macroX() - origin.macroX()),
                                         Math.abs(key.macroZ() - origin.macroZ())),
                                Math.abs(key.macroY() - origin.macroY()));
            assertTrue(dist >= prevDist, "Keys must be in non-decreasing 3D Chebyshev order");
            prevDist = dist;
        }
    }
}
