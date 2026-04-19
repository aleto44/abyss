package com.example.abyss.world.render.distant;

import com.example.abyss.WorldConstants;
import com.example.abyss.world.gen.Layer;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-static utility: the single authoritative place for converting world positions and
 * player origins into sets of {@link MacroChunkKey}s for the distant terrain system.
 */
public final class DistantTerrainKeyResolver {

    private DistantTerrainKeyResolver() {}

    /**
     * Converts a world-space XYZ position to the macro-chunk key of the slab it falls inside.
     * Uses floor division so negative coordinates map consistently (no seam at zero).
     */
    public static MacroChunkKey normalizeToMacroChunkKey(float worldX, float worldY, float worldZ) {
        int mx = Math.floorDiv((int) Math.floor(worldX), DistantTerrainConfig.MACRO_CHUNK_SIZE_VOXELS);
        int mz = Math.floorDiv((int) Math.floor(worldZ), DistantTerrainConfig.MACRO_CHUNK_SIZE_VOXELS);
        int chunkY       = Math.floorDiv((int) Math.floor(worldY), WorldConstants.CHUNK_SIZE);
        int worldMinChunkY = Math.floorDiv(Layer.DEEP_EXTREMES.getYEnd(), WorldConstants.CHUNK_SIZE);
        int rawMy = Math.floorDiv(chunkY - worldMinChunkY, DistantTerrainConfig.VERTICAL_DISTANT_TERRAIN_HEIGHT);
        int my = Math.max(DistantTerrainConfig.WORLD_MIN_BAND_Y,
                          Math.min(DistantTerrainConfig.WORLD_MAX_BAND_Y, rawMy));
        return new MacroChunkKey(mx, my, mz);
    }

    /**
     * Returns all {@link MacroChunkKey}s within the box defined by {@code xzRadius} on XZ axes
     * and {@code yRadius} on the band axis, centered on {@code origin}, clipped to world bounds.
     * Results are ordered nearest-first by 3-D Chebyshev distance, ties broken by XZ then Y.
     */
    public static List<MacroChunkKey> computeRequestSet(MacroChunkKey origin, int xzRadius, int yRadius) {
        int halfExt  = DistantTerrainConfig.WORLD_MAX_COORD_MACRO_CHUNKS;
        int minBandY = DistantTerrainConfig.WORLD_MIN_BAND_Y;
        int maxBandY = DistantTerrainConfig.WORLD_MAX_BAND_Y;
        List<MacroChunkKey> keys = new ArrayList<>();
        int maxShell = Math.max(xzRadius, yRadius);

        for (int shell = 0; shell <= maxShell; shell++) {
            int dxMax = Math.min(shell, xzRadius);
            int dyMax = Math.min(shell, yRadius);
            int dzMax = Math.min(shell, xzRadius);
            for (int dy = -dyMax; dy <= dyMax; dy++) {
                for (int dz = -dzMax; dz <= dzMax; dz++) {
                    for (int dx = -dxMax; dx <= dxMax; dx++) {
                        // Only emit the key at the exact shell that matches its 3D Chebyshev distance.
                        boolean isOnShellSurface = Math.abs(dx) == shell
                                                || Math.abs(dz) == shell
                                                || Math.abs(dy) == shell;
                        if (!isOnShellSurface) {
                            continue;
                        }
                        int cx = origin.macroX() + dx;
                        int cy = origin.macroY() + dy;
                        int cz = origin.macroZ() + dz;
                        boolean withinWorldXZ = Math.abs(cx) <= halfExt && Math.abs(cz) <= halfExt;
                        boolean withinBandRange = cy >= minBandY && cy <= maxBandY;
                        if (withinWorldXZ && withinBandRange) {
                            keys.add(new MacroChunkKey(cx, cy, cz));
                        }
                    }
                }
            }
        }

        return keys;
    }
}
