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
 * Each proxy region spans {@link WorldConstants#REGION_CHUNKS} chunks on all three axes.
 * All chunks in the region are generated via {@link ProceduralGenerator} — the same
 * pipeline used for near-field chunks — then scanned top-down per XZ column at
 * {@value #SAMPLE_STEP}-voxel intervals to find the highest solid voxel. One upward-facing
 * quad is emitted per column at that height, plus side-wall quads where adjacent columns
 * differ in height.
 * <p>
 * Using the real generator guarantees the LOD surface matches actual chunk geometry.
 */
public final class LodProxyGenerator {

    // ---------------------------------------------------------------
    // Region geometry — easy to tune
    // ---------------------------------------------------------------

    /** World-voxel size of one proxy region on all three axes. */
    public static final int REGION_VOXELS_H = WorldConstants.REGION_CHUNKS * WorldConstants.CHUNK_SIZE;

    /**
     * Voxels between LOD surface samples (XZ step size for quad emission).
     * Lowering this increases LOD detail (more quads per region).
     */
    public static final int SAMPLE_STEP = 2;

    /** Number of quad columns along X and Z within one region. */
    static final int GRID_W = REGION_VOXELS_H / SAMPLE_STEP;

    /** Sentinel: column contains no solid voxels. */
    private static final int NO_SURFACE = Integer.MIN_VALUE;

    // ---------------------------------------------------------------
    // Fields
    // ---------------------------------------------------------------

    private final ProceduralGenerator proceduralGenerator;

    /** Caller owns the {@link ProceduralGenerator} factory; each call to the factory produces one instance per thread. */
    public LodProxyGenerator(Supplier<ProceduralGenerator> generatorFactory) {
        this.proceduralGenerator = generatorFactory.get();
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    /**
     * Generates a proxy mesh for the given region key.
     * Safe to call from any background thread — each {@link LodProxyGenerator} owns its
     * own {@link ProceduralGenerator} instance.
     *
     * @return CPU-side mesh data, or {@link MeshData#EMPTY} if the region has no surface
     */
    public MeshData generate(LodRegionKey key) {
        int baseChunkX = key.regionX() * WorldConstants.REGION_CHUNKS;
        int baseChunkY = key.regionY() * WorldConstants.REGION_CHUNKS;
        int baseChunkZ = key.regionZ() * WorldConstants.REGION_CHUNKS;

        int worldBaseX = baseChunkX * WorldConstants.CHUNK_SIZE;
        int worldBaseY = baseChunkY * WorldConstants.CHUNK_SIZE;
        int worldBaseZ = baseChunkZ * WorldConstants.CHUNK_SIZE;

        VoxelData[][] chunks = generateRegionChunks(baseChunkX, baseChunkY, baseChunkZ);
        return buildSurfaceMesh(chunks, worldBaseX, worldBaseY, worldBaseZ);
    }

    // ---------------------------------------------------------------
    // Chunk generation
    // ---------------------------------------------------------------

    /**
     * Generates every chunk in this region using the full procedural pipeline.
     * Indexed as {@code chunksByY[dcy][dcz * REGION_CHUNKS + dcx]}.
     */
    private VoxelData[][] generateRegionChunks(int baseChunkX, int baseChunkY, int baseChunkZ) {
        int rc = WorldConstants.REGION_CHUNKS;
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
                                      int worldBaseX, int worldBaseY, int worldBaseZ) {
        int[] surfaceWorldY = resolveSurfaceHeights(chunksByY, worldBaseX, worldBaseY, worldBaseZ);
        Color[] surfaceColor = resolveSurfaceColors(chunksByY, surfaceWorldY, worldBaseY);

        ProxyMeshSectionBuilder opaque = new ProxyMeshSectionBuilder();
        emitSurfaceGeometry(opaque, surfaceWorldY, surfaceColor, worldBaseY);

        if (opaque.isEmpty()) {
            return MeshData.EMPTY;
        }
        return opaque.toMeshData();
    }

    /**
     * Pass 1: resolves the highest solid world-Y for every grid column.
     * Returns {@link #NO_SURFACE} for all-air columns.
     * <p>
     * Each region always emits the top face for its own surface voxels. Previously,
     * columns at the region top were suppressed and deferred to the region above —
     * but the region above only scans its own (higher) chunks and finds air, producing
     * holes at region-top boundaries.
     */
    private int[] resolveSurfaceHeights(VoxelData[][] chunksByY,
                                        int worldBaseX, int worldBaseY, int worldBaseZ) {
        int[] surfaceWorldY = new int[GRID_W * GRID_W];
        for (int gz = 0; gz < GRID_W; gz++) {
            int wz = worldBaseZ + gz * SAMPLE_STEP;
            for (int gx = 0; gx < GRID_W; gx++) {
                int wx = worldBaseX + gx * SAMPLE_STEP;
                surfaceWorldY[gz * GRID_W + gx] =
                        findTopmostSolidY(chunksByY, wx, wz, worldBaseX, worldBaseY, worldBaseZ);
            }
        }
        return surfaceWorldY;
    }

    /**
     * Pass 2: resolves the surface color for each non-air grid column.
     * Null entries indicate all-air columns.
     */
    private Color[] resolveSurfaceColors(VoxelData[][] chunksByY,
                                         int[] surfaceWorldY,
                                         int worldBaseY) {
        Color[] surfaceColor = new Color[GRID_W * GRID_W];
        for (int gz = 0; gz < GRID_W; gz++) {
            int wz = gz * SAMPLE_STEP;
            for (int gx = 0; gx < GRID_W; gx++) {
                int wx = gx * SAMPLE_STEP;
                int idx = gz * GRID_W + gx;
                int topmostY = surfaceWorldY[idx];
                boolean hasSurface = topmostY != NO_SURFACE;
                if (hasSurface) {
                    surfaceColor[idx] = voxelColor(chunksByY, wx, topmostY, wz, worldBaseY);
                }
            }
        }
        return surfaceColor;
    }

    /**
     * Pass 3: emits a top-face quad for each solid column, plus side-wall quads
     * wherever this column is higher than a cardinal neighbour.
     * Walls are always emitted from the higher side to avoid Z-fighting.
     */
    private void emitSurfaceGeometry(ProxyMeshSectionBuilder opaque,
                                     int[] surfaceWorldY, Color[] surfaceColor,
                                     int worldBaseY) {
        for (int gz = 0; gz < GRID_W; gz++) {
            for (int gx = 0; gx < GRID_W; gx++) {
                int idx = gz * GRID_W + gx;
                int topmostWorldY = surfaceWorldY[idx];

                boolean columnIsAllAir = topmostWorldY == NO_SURFACE;
                if (columnIsAllAir) {
                    continue;
                }

                float lx = gx * SAMPLE_STEP;
                float lz = gz * SAMPLE_STEP;
                float ly = (topmostWorldY + 1) - worldBaseY;
                Color color = surfaceColor[idx];

                ProxyQuadEmitter.emitQuadY(opaque, lx, ly, lz, SAMPLE_STEP, color);
                emitSideWalls(opaque, surfaceWorldY, gx, gz, lx, ly, lz, topmostWorldY, color, worldBaseY);
            }
        }
    }

    /**
     * Emits side-wall quads for the four cardinal neighbours of the given column.
     * A wall is emitted when this column is higher than the neighbour.
     * Columns at the region edge treat the out-of-bounds neighbour as all-air
     * so the boundary is always sealed and no gap opens toward an adjacent region.
     */
    private void emitSideWalls(ProxyMeshSectionBuilder opaque, int[] surfaceWorldY,
                                int gx, int gz, float lx, float ly, float lz,
                                int topmostWorldY, Color color, int worldBaseY) {
        int negZNeighbourY = (gz > 0) ? surfaceWorldY[(gz - 1) * GRID_W + gx] : NO_SURFACE;
        boolean negZIsLower = negZNeighbourY < topmostWorldY;
        if (negZIsLower) {
            float wallBottom = wallBottom(negZNeighbourY, worldBaseY);
            ProxyQuadEmitter.emitQuadZFace(opaque, lx, wallBottom, lz, ly - wallBottom, SAMPLE_STEP, color, false);
        }

        int posZNeighbourY = (gz < GRID_W - 1) ? surfaceWorldY[(gz + 1) * GRID_W + gx] : NO_SURFACE;
        boolean posZIsLower = posZNeighbourY < topmostWorldY;
        if (posZIsLower) {
            float wallBottom = wallBottom(posZNeighbourY, worldBaseY);
            ProxyQuadEmitter.emitQuadZFace(opaque, lx, wallBottom, lz + SAMPLE_STEP, ly - wallBottom, SAMPLE_STEP, color, true);
        }

        int negXNeighbourY = (gx > 0) ? surfaceWorldY[gz * GRID_W + (gx - 1)] : NO_SURFACE;
        boolean negXIsLower = negXNeighbourY < topmostWorldY;
        if (negXIsLower) {
            float wallBottom = wallBottom(negXNeighbourY, worldBaseY);
            ProxyQuadEmitter.emitQuadXFace(opaque, lx, wallBottom, lz, ly - wallBottom, SAMPLE_STEP, color, false);
        }

        int posXNeighbourY = (gx < GRID_W - 1) ? surfaceWorldY[gz * GRID_W + (gx + 1)] : NO_SURFACE;
        boolean posXIsLower = posXNeighbourY < topmostWorldY;
        if (posXIsLower) {
            float wallBottom = wallBottom(posXNeighbourY, worldBaseY);
            ProxyQuadEmitter.emitQuadXFace(opaque, lx + SAMPLE_STEP, wallBottom, lz, ly - wallBottom, SAMPLE_STEP, color, true);
        }
    }

    private static float wallBottom(int neighbourY, int worldBaseY) {
        return Math.max(neighbourY != NO_SURFACE ? neighbourY + 1 - worldBaseY : 0f, 0);
    }

    // ---------------------------------------------------------------
    // Chunk scanning helpers
    // ---------------------------------------------------------------

    /**
     * Scans from the top of the region downward for the given world XZ position.
     * Returns the world-Y of the highest solid voxel, or {@link #NO_SURFACE} if the column is all air.
     */
    private int findTopmostSolidY(VoxelData[][] chunksByY,
                                  int wx, int wz,
                                  int worldBaseX, int worldBaseY, int worldBaseZ) {
        int size = WorldConstants.CHUNK_SIZE;
        int rc = WorldConstants.REGION_CHUNKS;
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

    /**
     * Returns the base color of the voxel at the given region-local XZ and world Y.
     */
    private Color voxelColor(VoxelData[][] chunksByY,
                             int localX, int wy, int localZ,
                             int worldBaseY) {
        int size = WorldConstants.CHUNK_SIZE;
        int rc = WorldConstants.REGION_CHUNKS;
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