package com.example.abyss.world.render.lod;

import com.badlogic.gdx.graphics.Color;
import com.example.abyss.WorldConstants;
import com.example.abyss.world.gen.GenConfigResolver;
import com.example.abyss.world.gen.ProceduralGenerator;
import com.example.abyss.world.meshing.MeshData;
import com.example.abyss.world.meshing.MeshSectionData;
import com.example.abyss.world.voxel.VoxelData;
import com.example.abyss.world.voxel.VoxelType;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates a coarse top-surface proxy mesh for a far-field proxy region.
 * <p>
 * Each proxy region spans {@value #REGION_CHUNKS_H}×{@value #REGION_CHUNKS_H}×{@value #REGION_CHUNKS_H}
 * chunks. All chunks in the region are generated via {@link ProceduralGenerator} — the same
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

    /** Chunk span of one proxy region on all three axes (cubic region). */
    public static final int REGION_CHUNKS_H = 4;

    /** World-voxel size of one proxy region on all three axes. */
    public static final int REGION_VOXELS_H = REGION_CHUNKS_H * WorldConstants.CHUNK_SIZE;

    /**
     * Voxels between LOD surface samples (XZ step size for quad emission).
     * Each quad covers {@value #SAMPLE_STEP}×{@value #SAMPLE_STEP} voxels on the terrain surface.
     */
    public static final int SAMPLE_STEP = 4;

    /** Number of quad columns along X and Z within one region. */
    static final int GRID_W = REGION_VOXELS_H / SAMPLE_STEP;

    /**
     * Sentinel returned by {@link #findTopmostSolidY} when a column contains no solid voxels.
     * Must be distinct from any valid world-Y coordinate, including negative ones in deep abyss layers.
     */
    private static final int NO_SURFACE = Integer.MIN_VALUE;

    // ---------------------------------------------------------------
    // Vertex layout: position(3) + normal(3) + color(4) = 10 floats
    // ---------------------------------------------------------------
    private static final int FLOATS_PER_VERTEX = 10;
    private static final int MAX_VERTICES_PER_SECTION = Short.MAX_VALUE;

    // ---------------------------------------------------------------
    // Fields
    // ---------------------------------------------------------------

    private final ProceduralGenerator proceduralGenerator;

    public LodProxyGenerator(long seed) {
        this(seed, GenConfigResolver.DEFAULT);
    }

    public LodProxyGenerator(long seed, GenConfigResolver resolver) {
        this.proceduralGenerator = new ProceduralGenerator(seed, resolver);
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    /**
     * Generates a proxy mesh for the given region key.
     * Safe to call from any background thread — each {@link LodProxyGenerator} owns its
     * own {@link ProceduralGenerator} instance.
     *
     * @param key the proxy region to generate
     * @return CPU-side mesh data, or {@link MeshData#EMPTY} if the region has no surface
     */
    public MeshData generate(LodRegionKey key) {
        int baseChunkX = key.regionX() * REGION_CHUNKS_H;
        int baseChunkY = key.regionY() * REGION_CHUNKS_H;
        int baseChunkZ = key.regionZ() * REGION_CHUNKS_H;

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
     * Indexed as {@code chunksByY[dcy][dcz * REGION_CHUNKS_H + dcx]}.
     */
    private VoxelData[][] generateRegionChunks(int baseChunkX, int baseChunkY, int baseChunkZ) {
        VoxelData[][] chunksByY = new VoxelData[REGION_CHUNKS_H][REGION_CHUNKS_H * REGION_CHUNKS_H];
        for (int dcy = 0; dcy < REGION_CHUNKS_H; dcy++) {
            for (int dcz = 0; dcz < REGION_CHUNKS_H; dcz++) {
                for (int dcx = 0; dcx < REGION_CHUNKS_H; dcx++) {
                    chunksByY[dcy][dcz * REGION_CHUNKS_H + dcx] = proceduralGenerator.generateChunk(
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

        SectionBuilder opaque = new SectionBuilder();
        emitSurfaceGeometry(opaque, surfaceWorldY, surfaceColor, worldBaseY);

        boolean isEmpty = opaque.sections.isEmpty()
                || opaque.sections.stream().allMatch(SectionData::isEmpty);
        if (isEmpty) {
            return MeshData.EMPTY;
        }
        return new MeshData(opaque.toSections(), List.of());
    }

    /**
     * Pass 1: resolves the highest solid world-Y for every grid column.
     * Returns {@link #NO_SURFACE} for all-air columns.
     * <p>
     * Each region always emits the top face for its own surface voxels. Double-rendering
     * cannot occur because each region only scans its own chunk data. Previously, columns
     * whose surface was at the very top of the region were suppressed and deferred to the
     * region above — but the region above only scans its own (higher) chunks and finds air
     * at those positions, so neither region emitted the quad, producing horizontal holes at
     * region-top boundaries.
     */
    private int[] resolveSurfaceHeights(VoxelData[][] chunksByY,
                                         int worldBaseX, int worldBaseY, int worldBaseZ) {
        int[] surfaceWorldY = new int[GRID_W * GRID_W];

        for (int gz = 0; gz < GRID_W; gz++) {
            int wz = worldBaseZ + gz * SAMPLE_STEP;
            for (int gx = 0; gx < GRID_W; gx++) {
                int wx = worldBaseX + gx * SAMPLE_STEP;
                surfaceWorldY[gz * GRID_W + gx] = findTopmostSolidY(chunksByY, wx, wz, worldBaseX, worldBaseY, worldBaseZ);
            }
        }
        return surfaceWorldY;
    }

    /**
     * Pass 2: resolves the surface color for each non-air grid column.
     * Returns null entries for all-air columns (surfaceWorldY == -1).
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
    private void emitSurfaceGeometry(SectionBuilder opaque,
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

                emitQuadY(opaque, lx, ly, lz, color);
                emitSideWalls(opaque, surfaceWorldY, gx, gz, lx, ly, lz, topmostWorldY, color, worldBaseY);
            }
        }
    }

    /**
     * Emits side-wall quads for the four cardinal neighbours of the given column.
     * A wall is emitted when this column is higher than the neighbour.
     * Columns at the region edge treat the out-of-bounds neighbour as all-air (-1)
     * so the boundary is always sealed and no gap opens toward an adjacent region.
     */
    private void emitSideWalls(SectionBuilder opaque, int[] surfaceWorldY,
                                 int gx, int gz, float lx, float ly, float lz,
                                 int topmostWorldY, Color color, int worldBaseY) {
        // -Z neighbour (all-air sentinel at region edge)
        int negZNeighbourY = (gz > 0) ? surfaceWorldY[(gz - 1) * GRID_W + gx] : NO_SURFACE;
        boolean negZIsLower = negZNeighbourY < topmostWorldY;
        if (negZIsLower) {
            float wallBottom = Math.max(negZNeighbourY != NO_SURFACE ? negZNeighbourY + 1 - worldBaseY : 0f, 0);
            emitQuadZFace(opaque, lx, wallBottom, lz, ly - wallBottom, color, false);
        }

        // +Z neighbour (all-air sentinel at region edge)
        int posZNeighbourY = (gz < GRID_W - 1) ? surfaceWorldY[(gz + 1) * GRID_W + gx] : NO_SURFACE;
        boolean posZIsLower = posZNeighbourY < topmostWorldY;
        if (posZIsLower) {
            float wallBottom = Math.max(posZNeighbourY != NO_SURFACE ? posZNeighbourY + 1 - worldBaseY : 0f, 0);
            emitQuadZFace(opaque, lx, wallBottom, lz + SAMPLE_STEP, ly - wallBottom, color, true);
        }

        // -X neighbour (all-air sentinel at region edge)
        int negXNeighbourY = (gx > 0) ? surfaceWorldY[gz * GRID_W + (gx - 1)] : NO_SURFACE;
        boolean negXIsLower = negXNeighbourY < topmostWorldY;
        if (negXIsLower) {
            float wallBottom = Math.max(negXNeighbourY != NO_SURFACE ? negXNeighbourY + 1 - worldBaseY : 0f, 0);
            emitQuadXFace(opaque, lx, wallBottom, lz, ly - wallBottom, color, false);
        }

        // +X neighbour (all-air sentinel at region edge)
        int posXNeighbourY = (gx < GRID_W - 1) ? surfaceWorldY[gz * GRID_W + (gx + 1)] : NO_SURFACE;
        boolean posXIsLower = posXNeighbourY < topmostWorldY;
        if (posXIsLower) {
            float wallBottom = Math.max(posXNeighbourY != NO_SURFACE ? posXNeighbourY + 1 - worldBaseY : 0f, 0);
            emitQuadXFace(opaque, lx + SAMPLE_STEP, wallBottom, lz, ly - wallBottom, color, true);
        }
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
        for (int dcy = REGION_CHUNKS_H - 1; dcy >= 0; dcy--) {
            int dcx = (wx - worldBaseX) / size;
            int dcz = (wz - worldBaseZ) / size;
            VoxelData chunk = chunksByY[dcy][dcz * REGION_CHUNKS_H + dcx];
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
     * Returns the base color of the voxel at the given region-local XZ and world Y,
     * looked up from the pre-generated chunk data.
     */
    private Color voxelColor(VoxelData[][] chunksByY,
                              int localX, int wy, int localZ,
                              int worldBaseY) {
        int size = WorldConstants.CHUNK_SIZE;
        int dcy = (wy - worldBaseY) / size;
        int dcx = localX / size;
        int dcz = localZ / size;

        boolean outOfBounds = dcy < 0 || dcy >= REGION_CHUNKS_H
                || dcx < 0 || dcx >= REGION_CHUNKS_H
                || dcz < 0 || dcz >= REGION_CHUNKS_H;
        if (outOfBounds) {
            return Color.GRAY;
        }

        VoxelData chunk = chunksByY[dcy][dcz * REGION_CHUNKS_H + dcx];
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

    // ---------------------------------------------------------------
    // Quad emission
    // ---------------------------------------------------------------

    /** Emits an upward-facing quad on the XZ plane at (lx, ly, lz), size SAMPLE_STEP×SAMPLE_STEP. */
    private static void emitQuadY(SectionBuilder b, float lx, float ly, float lz, Color color) {
        emitQuad(b, color, 0f, 1f, 0f,
                lx,     ly, lz,
                lx,     ly, lz + SAMPLE_STEP,
                lx + SAMPLE_STEP, ly, lz + SAMPLE_STEP,
                lx + SAMPLE_STEP, ly, lz);
    }

    /**
     * Emits a vertical wall quad parallel to the YZ plane at x = {@code lx}.
     * {@code facingPositiveX} controls the outward normal direction.
     */
    private static void emitQuadXFace(SectionBuilder b, float lx, float wallBottom, float lz,
                                       float height, Color color, boolean facingPositiveX) {
        float nx  = facingPositiveX ? 1f : -1f;
        float top  = wallBottom + height;
        float zFar = lz + SAMPLE_STEP;
        if (facingPositiveX) {
            emitQuad(b, color, nx, 0f, 0f,
                    lx, wallBottom, lz,
                    lx, top,        lz,
                    lx, top,        zFar,
                    lx, wallBottom, zFar);
        } else {
            emitQuad(b, color, nx, 0f, 0f,
                    lx, wallBottom, zFar,
                    lx, top,        zFar,
                    lx, top,        lz,
                    lx, wallBottom, lz);
        }
    }

    /**
     * Emits a vertical wall quad parallel to the XY plane at z = {@code lz}.
     * {@code facingPositiveZ} controls the outward normal direction.
     */
    private static void emitQuadZFace(SectionBuilder b, float lx, float wallBottom, float lz,
                                       float height, Color color, boolean facingPositiveZ) {
        float nz  = facingPositiveZ ? 1f : -1f;
        float top  = wallBottom + height;
        float xFar = lx + SAMPLE_STEP;
        if (facingPositiveZ) {
            emitQuad(b, color, 0f, 0f, nz,
                    lx,   wallBottom, lz,
                    xFar, wallBottom, lz,
                    xFar, top,        lz,
                    lx,   top,        lz);
        } else {
            emitQuad(b, color, 0f, 0f, nz,
                    xFar, wallBottom, lz,
                    lx,   wallBottom, lz,
                    lx,   top,        lz,
                    xFar, top,        lz);
        }
    }

    private static void emitQuad(SectionBuilder b, Color color,
                                  float nx, float ny, float nz,
                                  float x0, float y0, float z0,
                                  float x1, float y1, float z1,
                                  float x2, float y2, float z2,
                                  float x3, float y3, float z3) {
        SectionData section = b.currentSection();
        short base = (short) section.vertexCount();

        section.addVertex(x0, y0, z0, nx, ny, nz, color);
        section.addVertex(x1, y1, z1, nx, ny, nz, color);
        section.addVertex(x2, y2, z2, nx, ny, nz, color);
        section.addVertex(x3, y3, z3, nx, ny, nz, color);

        section.addIndex(base);
        section.addIndex((short) (base + 1));
        section.addIndex((short) (base + 2));
        section.addIndex(base);
        section.addIndex((short) (base + 2));
        section.addIndex((short) (base + 3));
    }

    // ---------------------------------------------------------------
    // Internal mesh builder
    // ---------------------------------------------------------------

    private static final class SectionBuilder {
        private final List<SectionData> sections = new ArrayList<>();

        SectionData currentSection() {
            boolean needsNewSection = sections.isEmpty()
                    || sections.get(sections.size() - 1).willOverflowNextQuad();
            if (needsNewSection) {
                sections.add(new SectionData());
            }
            return sections.get(sections.size() - 1);
        }

        List<MeshSectionData> toSections() {
            List<MeshSectionData> result = new ArrayList<>();
            for (SectionData s : sections) {
                if (!s.isEmpty()) {
                    result.add(new MeshSectionData(s.vertices(), s.indices()));
                }
            }
            return result;
        }
    }

    private static final class SectionData {
        private float[] verts = new float[256];
        private short[] inds  = new short[256];
        private int vSize;
        private int iSize;

        void addVertex(float x, float y, float z, float nx, float ny, float nz, Color c) {
            ensureVertexCapacity();
            verts[vSize++] = x;  verts[vSize++] = y;  verts[vSize++] = z;
            verts[vSize++] = nx; verts[vSize++] = ny; verts[vSize++] = nz;
            verts[vSize++] = c.r; verts[vSize++] = c.g; verts[vSize++] = c.b; verts[vSize++] = c.a;
        }

        void addIndex(short idx) {
            if (iSize == inds.length) {
                short[] next = new short[inds.length * 2];
                System.arraycopy(inds, 0, next, 0, iSize);
                inds = next;
            }
            inds[iSize++] = idx;
        }

        int vertexCount() {
            return vSize / FLOATS_PER_VERTEX;
        }

        boolean willOverflowNextQuad() {
            return vertexCount() + 4 > MAX_VERTICES_PER_SECTION;
        }

        boolean isEmpty() {
            return vSize == 0 || iSize == 0;
        }

        float[] vertices() {
            float[] out = new float[vSize];
            System.arraycopy(verts, 0, out, 0, vSize);
            return out;
        }

        short[] indices() {
            short[] out = new short[iSize];
            System.arraycopy(inds, 0, out, 0, iSize);
            return out;
        }

        private void ensureVertexCapacity() {
            int needed = vSize + FLOATS_PER_VERTEX;
            if (needed > verts.length) {
                float[] next = new float[Math.max(needed, verts.length * 2)];
                System.arraycopy(verts, 0, next, 0, vSize);
                verts = next;
            }
        }
    }
}
