package com.example.abyss.world.render.distant;

import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Vector3;

import java.util.ArrayList;
import java.util.List;

/**
 * Boundary-driven streaming policy for the distant terrain background layer.
 * <p>
 * Re-evaluation fires exactly once per macro-chunk slab boundary crossing (XZ or Y band)
 * and on first frame. No per-frame sweeps, no timers, no incremental ring logic.
 * </p>
 */
public class DistantTerrainStreamer {

    private final DistantTerrainPort terrainPort;

    /** Null until the first {@link #updateForPosition} call. */
    private MacroChunkKey lastOrigin;

    private long evictionCount;

    public DistantTerrainStreamer(DistantTerrainPort terrainPort) {
        this.terrainPort = terrainPort;
    }

    /** Call once per frame on the main thread. */
    public void update(PerspectiveCamera camera, float deltaTime) {
        updateForPosition(camera.position, deltaTime);
    }

    public long getEvictionCount() {
        return evictionCount;
    }

    void updateForPosition(Vector3 observerPosition, float deltaTime) {
        terrainPort.pollCompletedMeshes();

        MacroChunkKey currentOrigin = DistantTerrainKeyResolver.normalizeToMacroChunkKey(
                observerPosition.x, observerPosition.y, observerPosition.z);

        boolean isBoundaryCrossing = !currentOrigin.equals(lastOrigin);
        if (isBoundaryCrossing) {
            lastOrigin = currentOrigin;
            submitNewKeys(currentOrigin);
            evictStaleKeys(currentOrigin);
        }
    }

    private void submitNewKeys(MacroChunkKey origin) {
        List<MacroChunkKey> requestSet = DistantTerrainKeyResolver.computeRequestSet(
                origin,
                DistantTerrainConfig.LOAD_DISTANCE_MACRO_CHUNKS,
                DistantTerrainConfig.LOAD_DISTANCE_BANDS_Y);
        for (MacroChunkKey key : requestSet) {
            boolean isUnknown = !terrainPort.isMacroChunkKnown(key);
            if (isUnknown) {
                terrainPort.requestMacroChunkAsync(key);
            }
        }
    }

    private void evictStaleKeys(MacroChunkKey origin) {
        int xzEvictionRadius = DistantTerrainConfig.LOAD_DISTANCE_MACRO_CHUNKS
                               + DistantTerrainConfig.EVICTION_PADDING_MACRO_CHUNKS;
        int yEvictionRadius  = DistantTerrainConfig.LOAD_DISTANCE_BANDS_Y
                               + DistantTerrainConfig.EVICTION_PADDING_BANDS_Y;
        List<MacroChunkKey> toEvict = new ArrayList<>();
        for (MacroChunkKey key : terrainPort.getLoadedKeys()) {
            boolean isBeyondEvictionRadius = isBeyondRadius(key, origin, xzEvictionRadius, yEvictionRadius);
            if (isBeyondEvictionRadius) {
                toEvict.add(key);
            }
        }
        for (MacroChunkKey key : toEvict) {
            terrainPort.removeMacroChunk(key);
            evictionCount++;
        }
    }

    private static boolean isBeyondRadius(MacroChunkKey key, MacroChunkKey origin, int xzRadius, int yRadius) {
        int adx = Math.abs(key.macroX() - origin.macroX());
        int adz = Math.abs(key.macroZ() - origin.macroZ());
        int ady = Math.abs(key.macroY() - origin.macroY());
        return adx > xzRadius || adz > xzRadius || ady > yRadius;
    }
}
