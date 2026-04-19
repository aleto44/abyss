package com.example.abyss.world.render.distant;

import com.example.abyss.WorldConstants;
import com.example.abyss.world.gen.Layer;
import com.example.abyss.world.gen.ProceduralGenerator;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * Generates a {@link MacroChunkHeightmap} for one distant terrain macro-chunk slab.
 * Only the single vertical band encoded in {@link MacroChunkKey#macroY()} is computed,
 * so generation cost is proportional to band height rather than the full world column.
 * Pure and deterministic: same key + seed always produces identical output.
 * Safe to call from any background thread when stored in a {@link ThreadLocal}.
 */
public final class DistantTerrainGenerator {

    private final ProceduralGenerator proceduralGenerator;

    /** Each instance owns its own {@link ProceduralGenerator} — never shared across threads. */
    public DistantTerrainGenerator(Supplier<ProceduralGenerator> generatorFactory) {
        this.proceduralGenerator = generatorFactory.get();
    }

    /**
     * Generates the heightmap for the single band identified by {@link MacroChunkKey#macroY()}.
     * Safe to call from any background thread.
     */
    public MacroChunkHeightmap generate(MacroChunkKey key) {
        int gridW        = DistantTerrainConfig.MACRO_CHUNK_SIZE_VOXELS / DistantTerrainConfig.DISTANT_SAMPLING_STEP;
        int bandUnits    = DistantTerrainConfig.VERTICAL_DISTANT_TERRAIN_HEIGHT;
        int chunkSize    = WorldConstants.CHUNK_SIZE;
        int sampleStep   = DistantTerrainConfig.DISTANT_SAMPLING_STEP;
        int halfExtentVx = DistantTerrainConfig.WORLD_MAX_COORD_MACRO_CHUNKS
                           * DistantTerrainConfig.MACRO_CHUNK_SIZE_VOXELS;
        int chunkYBase   = Math.floorDiv(Layer.DEEP_EXTREMES.getYEnd(), chunkSize)
                           + key.macroY() * bandUnits;

        HeightmapBand band = allocateBand(key.macroY(), gridW);

        for (int gz = 0; gz < gridW; gz++) {
            for (int gx = 0; gx < gridW; gx++) {
                int rawWx = key.macroX() * DistantTerrainConfig.MACRO_CHUNK_SIZE_VOXELS + gx * sampleStep;
                int rawWz = key.macroZ() * DistantTerrainConfig.MACRO_CHUNK_SIZE_VOXELS + gz * sampleStep;
                int wx = clamp(rawWx, -halfExtentVx, halfExtentVx);
                int wz = clamp(rawWz, -halfExtentVx, halfExtentVx);

                int colIdx = gz * gridW + gx;
                scanBandColumn(band, colIdx, wx, wz, chunkYBase, bandUnits, chunkSize);
            }
        }
        return new MacroChunkHeightmap(key, band);
    }

    private HeightmapBand allocateBand(int bandIndex, int gridW) {
        int columnCount = gridW * gridW;
        int[]   maxH    = new int[columnCount];
        int[]   minH    = new int[columnCount];
        float[] density = new float[columnCount];
        Arrays.fill(maxH, Integer.MIN_VALUE);
        Arrays.fill(minH, Integer.MAX_VALUE);
        return new HeightmapBand(bandIndex, maxH, minH, density);
    }

    private void scanBandColumn(HeightmapBand band, int colIdx,
                                 int wx, int wz,
                                 int chunkYBase, int bandUnits, int chunkSize) {
        int wyMin = chunkYBase * chunkSize;
        int wyMax = wyMin + bandUnits * chunkSize - 1;

        // Sample every 2 voxels vertically — sufficient for distant terrain heightmaps
        // and ~16× cheaper per column than full chunk generation.
        ProceduralGenerator.HeightmapColumnResult result =
                proceduralGenerator.scanHeightmapColumn(wx, wz, wyMin, wyMax, 2);

        band.maxHeight()[colIdx]    = result.maxSolidY();
        band.minHeight()[colIdx]    = result.minSolidY();
        band.densityScore()[colIdx] = result.densityScore();
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}

