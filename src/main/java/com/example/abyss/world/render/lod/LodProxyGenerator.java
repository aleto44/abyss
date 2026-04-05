package com.example.abyss.world.render.lod;

import com.badlogic.gdx.graphics.Color;
import com.example.abyss.WorldConstants;
import com.example.abyss.world.gen.ProceduralGenerator;
import java.util.function.Supplier;
import com.example.abyss.world.meshing.MeshData;
import com.example.abyss.world.voxel.VoxelData;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Generates a coarse top-surface proxy mesh for a far-field proxy region.
 * <p>
 * Region dimensions and sample resolution are driven by the {@link LodTierConfig}
 * for the requested {@link LodRegionKey#lodLevel()}, making this generator tier-aware
 * without embedding any streaming policy.
 */
public final class LodProxyGenerator {

    private static final int NO_SURFACE = Integer.MIN_VALUE;

    private final ProceduralGenerator proceduralGenerator;

    /** Caller owns the {@link ProceduralGenerator} factory; each call produces one instance per thread. */
    public LodProxyGenerator(Supplier<ProceduralGenerator> generatorFactory) {
        this.proceduralGenerator = generatorFactory.get();
    }

    /**
     * Generates a proxy mesh for the given region key.
     * Safe to call from any background thread — each {@link LodProxyGenerator} owns its
     * own {@link ProceduralGenerator} instance.
     *
     * @return CPU-side mesh data, or {@link MeshData#EMPTY} if the region has no surface
     */
    public MeshData generate(LodRegionKey key) {
        LodTierConfig tier = LodConfig.TIERS.get(key.lodLevel());
        int regionSizeChunks = tier.regionSizeChunks();
        int regionVoxelsH = tier.regionSizeVoxels();
        int sampleStep = tier.sampleStep();
        int gridW = (regionVoxelsH + sampleStep - 1) / sampleStep;

        int baseChunkX = key.regionX() * regionSizeChunks;
        int baseChunkY = key.regionY() * regionSizeChunks;
        int baseChunkZ = key.regionZ() * regionSizeChunks;

        int worldBaseX = baseChunkX * WorldConstants.CHUNK_SIZE;
        int worldBaseY = baseChunkY * WorldConstants.CHUNK_SIZE;
        int worldBaseZ = baseChunkZ * WorldConstants.CHUNK_SIZE;

        VoxelData[][] chunks = generateRegionChunks(baseChunkX, baseChunkY, baseChunkZ, regionSizeChunks);
        return buildSurfaceMesh(chunks, worldBaseX, worldBaseY, worldBaseZ, regionSizeChunks, regionVoxelsH, sampleStep, gridW);
    }

    // ---------------------------------------------------------------
    // Chunk generation
    // ---------------------------------------------------------------

    private VoxelData[][] generateRegionChunks(int baseChunkX, int baseChunkY, int baseChunkZ, int regionSizeChunks) {
        int rc = regionSizeChunks;
        VoxelData[][] chunksByY = new VoxelData[rc][rc * rc];
        for (int dcy = 0; dcy < rc; dcy++) {
            for (int dcz = 0; dcz < rc; dcz++) {
                for (int dcx = 0; dcx < rc; dcx++) {
                    chunksByY[dcy][dcz * rc + dcx] = proceduralGenerator.generateChunk(
                            baseChunkX + dcx,
                            baseChunkY + dcy,
                            baseChunkZ + dcz
                    );
                }
            }
        }
        return chunksByY;
    }

    // ---------------------------------------------------------------
    // Surface mesh building
    // ---------------------------------------------------------------

    private MeshData buildSurfaceMesh(VoxelData[][] chunksByY,
                                      int worldBaseX, int worldBaseY, int worldBaseZ,
                                      int regionSizeChunks, int regionVoxelsH, int sampleStep, int gridW) {
        int[] surfaceWorldY = resolveSurfaceHeights(chunksByY, worldBaseX, worldBaseY, worldBaseZ, regionSizeChunks, regionVoxelsH, sampleStep, gridW);
        Color[] surfaceColor = resolveSurfaceColors(chunksByY, surfaceWorldY, worldBaseY, regionSizeChunks, sampleStep, gridW);

        ProxyMeshSectionBuilder opaque = new ProxyMeshSectionBuilder();
        emitSurfaceGeometry(opaque, surfaceWorldY, surfaceColor, worldBaseY, regionVoxelsH, sampleStep, gridW);

        if (opaque.isEmpty()) {
            return MeshData.EMPTY;
        }
        return opaque.toMeshData();
    }

    private int[] resolveSurfaceHeights(VoxelData[][] chunksByY,
                                        int worldBaseX, int worldBaseY, int worldBaseZ,
                                        int regionSizeChunks, int regionVoxelsH, int sampleStep, int gridW) {
        int[] surfaceWorldY = new int[gridW * gridW];
        for (int gz = 0; gz < gridW; gz++) {
            int wz = worldBaseZ + gz * sampleStep;
            for (int gx = 0; gx < gridW; gx++) {
                int wx = worldBaseX + gx * sampleStep;
                surfaceWorldY[gz * gridW + gx] =
                        findTopmostSolidY(chunksByY, wx, wz, worldBaseX, worldBaseY, worldBaseZ, regionSizeChunks);
            }
        }
        return surfaceWorldY;
    }

    private Color[] resolveSurfaceColors(VoxelData[][] chunksByY,
                                         int[] surfaceWorldY,
                                         int worldBaseY,
                                         int regionSizeChunks, int sampleStep, int gridW) {
        Color[] surfaceColor = new Color[gridW * gridW];
        for (int gz = 0; gz < gridW; gz++) {
            int wz = gz * sampleStep;
            for (int gx = 0; gx < gridW; gx++) {
                int wx = gx * sampleStep;
                int idx = gz * gridW + gx;
                int topmostY = surfaceWorldY[idx];
                boolean hasSurface = topmostY != NO_SURFACE;
                if (hasSurface) {
                    surfaceColor[idx] = voxelColor(chunksByY, wx, topmostY, wz, worldBaseY, regionSizeChunks);
                }
            }
        }
        return surfaceColor;
    }

    private void emitSurfaceGeometry(ProxyMeshSectionBuilder opaque,
                                     int[] surfaceWorldY, Color[] surfaceColor,
                                     int worldBaseY, int regionVoxelsH, int sampleStep, int gridW) {
        for (int gz = 0; gz < gridW; gz++) {
            int cellH = Math.min(sampleStep, regionVoxelsH - gz * sampleStep);
            for (int gx = 0; gx < gridW; gx++) {
                int cellW = Math.min(sampleStep, regionVoxelsH - gx * sampleStep);
                int idx = gz * gridW + gx;
                int topmostWorldY = surfaceWorldY[idx];

                boolean columnIsAllAir = topmostWorldY == NO_SURFACE;
                if (columnIsAllAir) {
                    continue;
                }

                float lx = gx * sampleStep;
                float lz = gz * sampleStep;
                float ly = (topmostWorldY + 1) - worldBaseY;
                Color color = surfaceColor[idx];

                emitTopFace(opaque, lx, ly, lz, cellW, cellH, color, sampleStep);
                emitSideWalls(opaque, surfaceWorldY, gx, gz, cellW, cellH, lx, ly, lz, topmostWorldY, color, worldBaseY, gridW);
            }
        }
    }

    private static void emitTopFace(ProxyMeshSectionBuilder opaque,
                                    float lx, float ly, float lz,
                                    int cellW, int cellH, Color color, int sampleStep) {
        boolean isFullCell = cellW == sampleStep && cellH == sampleStep;
        if (isFullCell) {
            ProxyQuadEmitter.emitQuadY(opaque, lx, ly, lz, sampleStep, color);
        } else {
            ProxyQuadEmitter.emitQuadYSized(opaque, lx, ly, lz, cellW, cellH, color);
        }
    }

    private void emitSideWalls(ProxyMeshSectionBuilder opaque, int[] surfaceWorldY,
                                int gx, int gz, int cellW, int cellH,
                                float lx, float ly, float lz,
                                int topmostWorldY, Color color, int worldBaseY, int gridW) {
        int negZNeighbourY = (gz > 0) ? surfaceWorldY[(gz - 1) * gridW + gx] : NO_SURFACE;
        boolean negZIsLower = negZNeighbourY < topmostWorldY;
        if (negZIsLower) {
            float wallBottom = wallBottom(negZNeighbourY, worldBaseY);
            ProxyQuadEmitter.emitQuadZFace(opaque, lx, wallBottom, lz, ly - wallBottom, cellW, color, false);
        }

        int posZNeighbourY = (gz < gridW - 1) ? surfaceWorldY[(gz + 1) * gridW + gx] : NO_SURFACE;
        boolean posZIsLower = posZNeighbourY < topmostWorldY;
        if (posZIsLower) {
            float wallBottom = wallBottom(posZNeighbourY, worldBaseY);
            ProxyQuadEmitter.emitQuadZFace(opaque, lx, wallBottom, lz + cellH, ly - wallBottom, cellW, color, true);
        }

        int negXNeighbourY = (gx > 0) ? surfaceWorldY[gz * gridW + (gx - 1)] : NO_SURFACE;
        boolean negXIsLower = negXNeighbourY < topmostWorldY;
        if (negXIsLower) {
            float wallBottom = wallBottom(negXNeighbourY, worldBaseY);
            ProxyQuadEmitter.emitQuadXFace(opaque, lx, wallBottom, lz, ly - wallBottom, cellH, color, false);
        }

        int posXNeighbourY = (gx < gridW - 1) ? surfaceWorldY[gz * gridW + (gx + 1)] : NO_SURFACE;
        boolean posXIsLower = posXNeighbourY < topmostWorldY;
        if (posXIsLower) {
            float wallBottom = wallBottom(posXNeighbourY, worldBaseY);
            ProxyQuadEmitter.emitQuadXFace(opaque, lx + cellW, wallBottom, lz, ly - wallBottom, cellH, color, true);
        }
    }

    private static float wallBottom(int neighbourY, int worldBaseY) {
        return Math.max(neighbourY != NO_SURFACE ? neighbourY + 1 - worldBaseY : 0f, 0);
    }

    // ---------------------------------------------------------------
    // Chunk scanning helpers
    // ---------------------------------------------------------------

    private int findTopmostSolidY(VoxelData[][] chunksByY,
                                  int wx, int wz,
                                  int worldBaseX, int worldBaseY, int worldBaseZ,
                                  int regionSizeChunks) {
        int size = WorldConstants.CHUNK_SIZE;
        int rc = regionSizeChunks;
        for (int dcy = rc - 1; dcy >= 0; dcy--) {
            int dcx = (wx - worldBaseX) / size;
            int dcz = (wz - worldBaseZ) / size;
            VoxelData chunk = chunksByY[dcy][dcz * rc + dcx];
            int lx = (wx - worldBaseX) % size;
            int lz = (wz - worldBaseZ) % size;
            for (int ly = size - 1; ly >= 0; ly--) {
                VoxelType type = chunk.getType(lx, ly, lz);
                boolean isSolid = type != null && type.isSolid();
                if (isSolid) {
                    return worldBaseY + dcy * size + ly;
                }
            }
        }
        return NO_SURFACE;
    }

    private Color voxelColor(VoxelData[][] chunksByY,
                             int localX, int wy, int localZ,
                             int worldBaseY, int regionSizeChunks) {
        int size = WorldConstants.CHUNK_SIZE;
        int rc = regionSizeChunks;
        int dcy = (wy - worldBaseY) / size;
        int dcx = localX / size;
        int dcz = localZ / size;

        boolean outOfBounds = dcy < 0 || dcy >= rc
                || dcx < 0 || dcx >= rc
                || dcz < 0 || dcz >= rc;
        if (outOfBounds) {
            return Color.GRAY;
        }

        VoxelData chunk = chunksByY[dcy][dcz * rc + dcx];
        VoxelType type = chunk.getType(
                localX % size,
                (wy - worldBaseY) % size,
                localZ % size
        );
        boolean hasColor = type != null && type.getBaseColor() != null;
        if (hasColor) {
            return type.getBaseColor();
        }
        return Color.GRAY;
    }
}