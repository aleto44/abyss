package com.example.abyss.world.render.distant;

import com.badlogic.gdx.graphics.Color;
import com.example.abyss.WorldConstants;
import com.example.abyss.world.gen.Layer;
import com.example.abyss.world.meshing.MeshData;

/**
 * Builds a surface mesh for one heightmap band at the given macro-chunk world origin.
 * Pure static: no side effects, fully thread-safe.
 */
public final class DistantMeshBuilder {

    private static final int NO_SURFACE = Integer.MIN_VALUE;

    private DistantMeshBuilder() {}

    /** Builds a surface mesh for one heightmap band at the given macro-chunk world origin. */
    public static MeshData build(HeightmapBand band, float worldBaseX, float worldBaseZ) {
        int sampleStep  = DistantTerrainConfig.DISTANT_SAMPLING_STEP;
        int macroVoxels = DistantTerrainConfig.MACRO_CHUNK_SIZE_VOXELS;
        int gridW       = macroVoxels / sampleStep;
        int bandWorldBaseY = (Math.floorDiv(Layer.DEEP_EXTREMES.getYEnd(), WorldConstants.CHUNK_SIZE) + band.bandIndex()
                             * DistantTerrainConfig.VERTICAL_DISTANT_TERRAIN_HEIGHT)
                             * WorldConstants.CHUNK_SIZE;

        DistantMeshSectionBuilder opaque = new DistantMeshSectionBuilder();

        emitBandGeometry(opaque, band, gridW, sampleStep, macroVoxels, bandWorldBaseY);

        if (opaque.isEmpty()) {
            return MeshData.EMPTY;
        }
        return opaque.toMeshData();
    }

    private static void emitBandGeometry(DistantMeshSectionBuilder opaque,
                                         HeightmapBand band,
                                         int gridW, int sampleStep, int regionVoxelsH,
                                         int bandWorldBaseY) {
        for (int gz = 0; gz < gridW; gz++) {
            int cellH = Math.min(sampleStep, regionVoxelsH - gz * sampleStep);
            for (int gx = 0; gx < gridW; gx++) {
                int cellW = Math.min(sampleStep, regionVoxelsH - gx * sampleStep);
                int idx           = gz * gridW + gx;
                int topmostWorldY = band.maxHeight()[idx];

                boolean columnIsEmpty = topmostWorldY == NO_SURFACE || topmostWorldY == Integer.MIN_VALUE;
                if (columnIsEmpty) {
                    continue;
                }

                float density = band.densityScore()[idx];
                Color color   = densityColor(density);

                float lx = gx * sampleStep;
                float lz = gz * sampleStep;
                float ly = (topmostWorldY + 1) - bandWorldBaseY;

                emitTopFace(opaque, lx, ly, lz, cellW, cellH, color, sampleStep);
                emitSideWalls(opaque, band, gx, gz, cellW, cellH, lx, ly, lz,
                              topmostWorldY, color, bandWorldBaseY, gridW);
            }
        }
    }

    private static Color densityColor(float density) {
        // Earth-tone lerp: dark rock → brighter surface material
        float r = 0.3f + 0.4f * density;
        float g = 0.25f + 0.35f * density;
        float b = 0.2f + 0.2f * density;
        return new Color(r, g, b, 1.0f);
    }

    private static void emitTopFace(DistantMeshSectionBuilder opaque,
                                    float lx, float ly, float lz,
                                    int cellW, int cellH, Color color, int sampleStep) {
        boolean isFullCell = cellW == sampleStep && cellH == sampleStep;
        if (isFullCell) {
            DistantQuadEmitter.emitQuadY(opaque, lx, ly, lz, sampleStep, color);
        } else {
            DistantQuadEmitter.emitQuadYSized(opaque, lx, ly, lz, cellW, cellH, color);
        }
    }

    private static void emitSideWalls(DistantMeshSectionBuilder opaque,
                                      HeightmapBand band,
                                      int gx, int gz, int cellW, int cellH,
                                      float lx, float ly, float lz,
                                      int topmostWorldY, Color color,
                                      int bandWorldBaseY, int gridW) {
        int negZNeighbourY = (gz > 0) ? band.maxHeight()[(gz - 1) * gridW + gx] : NO_SURFACE;
        boolean negZIsLower = isNeighbourLower(negZNeighbourY, topmostWorldY);
        if (negZIsLower) {
            float wallBottom = wallBottom(negZNeighbourY, bandWorldBaseY);
            DistantQuadEmitter.emitQuadZFace(opaque, lx, wallBottom, lz, ly - wallBottom, cellW, color, false);
        }

        int posZNeighbourY = (gz < gridW - 1) ? band.maxHeight()[(gz + 1) * gridW + gx] : NO_SURFACE;
        boolean posZIsLower = isNeighbourLower(posZNeighbourY, topmostWorldY);
        if (posZIsLower) {
            float wallBottom = wallBottom(posZNeighbourY, bandWorldBaseY);
            DistantQuadEmitter.emitQuadZFace(opaque, lx, wallBottom, lz + cellH, ly - wallBottom, cellW, color, true);
        }

        int negXNeighbourY = (gx > 0) ? band.maxHeight()[gz * gridW + (gx - 1)] : NO_SURFACE;
        boolean negXIsLower = isNeighbourLower(negXNeighbourY, topmostWorldY);
        if (negXIsLower) {
            float wallBottom = wallBottom(negXNeighbourY, bandWorldBaseY);
            DistantQuadEmitter.emitQuadXFace(opaque, lx, wallBottom, lz, ly - wallBottom, cellH, color, false);
        }

        int posXNeighbourY = (gx < gridW - 1) ? band.maxHeight()[gz * gridW + (gx + 1)] : NO_SURFACE;
        boolean posXIsLower = isNeighbourLower(posXNeighbourY, topmostWorldY);
        if (posXIsLower) {
            float wallBottom = wallBottom(posXNeighbourY, bandWorldBaseY);
            DistantQuadEmitter.emitQuadXFace(opaque, lx + cellW, wallBottom, lz, ly - wallBottom, cellH, color, true);
        }
    }

    private static boolean isNeighbourLower(int neighbourY, int topmostWorldY) {
        return neighbourY < topmostWorldY;
    }

    private static float wallBottom(int neighbourY, int bandWorldBaseY) {
        int rawBottom = (neighbourY != NO_SURFACE && neighbourY != Integer.MIN_VALUE)
                        ? neighbourY + 1 - bandWorldBaseY
                        : 0;
        return Math.max(rawBottom, 0);
    }
}
