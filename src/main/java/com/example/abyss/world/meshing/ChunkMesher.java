package com.example.abyss.world.meshing;

import com.badlogic.gdx.graphics.Color;
import com.example.abyss.world.ChunkKey;
import com.example.abyss.world.VoxelChunk;
import com.example.abyss.world.voxel.VoxelType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Background-safe chunk mesher.
 * <p>
 * Extracts a smooth isosurface from chunk densities using a marching-tetrahedra
 * decomposition of each voxel cell. This preserves the existing async queue,
 * render promotion, and disposal pipeline while replacing the temporary
 * exposed-face meshing pass with actual density-based surface extraction.
 * </p>
 */
public final class ChunkMesher {

    private static final int SIZE = VoxelChunk.SIZE;
    private static final int SCRATCH_SIZE = SIZE + 1;
    private static final float ISO_LEVEL = 0f;
    private static final int[][] CELL_CORNERS = {
            {0, 0, 0},
            {1, 0, 0},
            {1, 1, 0},
            {0, 1, 0},
            {0, 0, 1},
            {1, 0, 1},
            {1, 1, 1},
            {0, 1, 1}
    };
    private static final int[][] TETRAHEDRA = {
            {0, 5, 1, 6},
            {0, 1, 2, 6},
            {0, 2, 3, 6},
            {0, 3, 7, 6},
            {0, 7, 4, 6},
            {0, 4, 5, 6}
    };
    private static final int[][] TETRA_EDGES = {
            {0, 1},
            {1, 2},
            {2, 0},
            {0, 3},
            {1, 3},
            {2, 3}
    };

    private ChunkMesher() {
    }

    public static MeshData buildMesh(VoxelChunk center,
                                     VoxelChunk plusX,
                                     VoxelChunk plusY,
                                     VoxelChunk plusZ) {
        float[][][] density = new float[SCRATCH_SIZE][SCRATCH_SIZE][SCRATCH_SIZE];
        VoxelType[][][] types = new VoxelType[SCRATCH_SIZE][SCRATCH_SIZE][SCRATCH_SIZE];

        fillScratch(center, plusX, plusY, plusZ, density, types);

        SectionCollector opaqueSections = new SectionCollector();
        SectionCollector transparentSections = new SectionCollector();

        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    emitCell(x, y, z, density, types, opaqueSections, transparentSections);
                }
            }
        }

        if (opaqueSections.isEmpty() && transparentSections.isEmpty()) {
            return MeshData.EMPTY;
        }

        return new MeshData(opaqueSections.toSections(), transparentSections.toSections());
    }

    private static void emitCell(int x,
                                 int y,
                                 int z,
                                 float[][][] density,
                                 VoxelType[][][] types,
                                 SectionCollector opaqueSections,
                                 SectionCollector transparentSections) {
        CellCorner[] corners = new CellCorner[8];
        boolean hasSolid = false;
        boolean hasAir = false;
        for (int i = 0; i < CELL_CORNERS.length; i++) {
            int[] offset = CELL_CORNERS[i];
            int sx = x + offset[0];
            int sy = y + offset[1];
            int sz = z + offset[2];
            float sampleDensity = density[sx][sy][sz];
            VoxelType sampleType = normalizeType(types[sx][sy][sz], sampleDensity);
            corners[i] = new CellCorner(sx, sy, sz, sampleDensity, sampleType);
            if (sampleDensity > ISO_LEVEL) {
                hasSolid = true;
            } else {
                hasAir = true;
            }
        }
        if (!hasSolid || !hasAir) {
            return;
        }

        for (int[] tetra : TETRAHEDRA) {
            emitTetrahedron(corners, tetra, density, opaqueSections, transparentSections);
        }
    }

    private static void emitTetrahedron(CellCorner[] cellCorners,
                                        int[] tetra,
                                        float[][][] density,
                                        SectionCollector opaqueSections,
                                        SectionCollector transparentSections) {
        CellCorner[] tetraCorners = new CellCorner[4];
        boolean hasSolid = false;
        boolean hasAir = false;
        for (int i = 0; i < tetra.length; i++) {
            tetraCorners[i] = cellCorners[tetra[i]];
            if (tetraCorners[i].density() > ISO_LEVEL) {
                hasSolid = true;
            } else {
                hasAir = true;
            }
        }
        if (!hasSolid || !hasAir) {
            return;
        }

        IntersectionPoint[] intersections = new IntersectionPoint[4];
        int intersectionCount = 0;
        for (int[] edge : TETRA_EDGES) {
            CellCorner a = tetraCorners[edge[0]];
            CellCorner b = tetraCorners[edge[1]];
            if (!crossesIsoSurface(a.density(), b.density())) {
                continue;
            }
            intersections[intersectionCount++] = interpolateIntersection(a, b, density);
        }

        if (intersectionCount < 3) {
            return;
        }

        VoxelType surfaceType = chooseSurfaceType(tetraCorners);
        SectionCollector sections = surfaceType.isTransparent() ? transparentSections : opaqueSections;

        if (intersectionCount == 3) {
            emitTriangle(intersections[0], intersections[1], intersections[2], surfaceType, sections);
            return;
        }

        if (intersectionCount == 4) {
            emitTriangle(intersections[0], intersections[1], intersections[2], surfaceType, sections);
            emitTriangle(intersections[0], intersections[2], intersections[3], surfaceType, sections);
        }
    }

    private static void emitTriangle(IntersectionPoint a,
                                     IntersectionPoint b,
                                     IntersectionPoint c,
                                     VoxelType type,
                                     SectionCollector sections) {
        float ux = b.x() - a.x();
        float uy = b.y() - a.y();
        float uz = b.z() - a.z();
        float vx = c.x() - a.x();
        float vy = c.y() - a.y();
        float vz = c.z() - a.z();
        float faceNx = uy * vz - uz * vy;
        float faceNy = uz * vx - ux * vz;
        float faceNz = ux * vy - uy * vx;
        float faceLengthSq = faceNx * faceNx + faceNy * faceNy + faceNz * faceNz;
        if (faceLengthSq <= 1.0e-12f) {
            return;
        }

        float avgNx = a.nx() + b.nx() + c.nx();
        float avgNy = a.ny() + b.ny() + c.ny();
        float avgNz = a.nz() + b.nz() + c.nz();
        if (faceNx * avgNx + faceNy * avgNy + faceNz * avgNz < 0f) {
            IntersectionPoint temp = b;
            b = c;
            c = temp;
        }

        MeshSectionBuilder section = sections.currentSectionForTriangle();
        short baseIndex = (short) section.vertexCount();

        appendVertex(section.vertices, a, type);
        appendVertex(section.vertices, b, type);
        appendVertex(section.vertices, c, type);

        section.indices.add(baseIndex);
        section.indices.add((short) (baseIndex + 1));
        section.indices.add((short) (baseIndex + 2));
    }

    private static void appendVertex(FloatCollector vertices, IntersectionPoint point, VoxelType type) {
        Color color = type.getBaseColor();
        vertices.add(point.x());
        vertices.add(point.y());
        vertices.add(point.z());
        vertices.add(point.nx());
        vertices.add(point.ny());
        vertices.add(point.nz());
        vertices.add(color.r);
        vertices.add(color.g);
        vertices.add(color.b);
        vertices.add(color.a);
    }

    private static IntersectionPoint interpolateIntersection(CellCorner a,
                                                             CellCorner b,
                                                             float[][][] density) {
        float aDensity = a.density();
        float bDensity = b.density();
        float denominator = bDensity - aDensity;
        float t = Math.abs(denominator) < 1.0e-6f ? 0.5f : (ISO_LEVEL - aDensity) / denominator;
        t = clamp01(t);

        float x = lerp(a.x(), b.x(), t);
        float y = lerp(a.y(), b.y(), t);
        float z = lerp(a.z(), b.z(), t);
        float[] gradient = sampleGradient(x, y, z, density);
        VoxelType type = dominantSolidType(a.type(), aDensity, b.type(), bDensity);
        return new IntersectionPoint(x, y, z, gradient[0], gradient[1], gradient[2], type);
    }

    private static float[] sampleGradient(float x, float y, float z, float[][][] density) {
        float dx = sampleDensityClamped(density, x + 0.5f, y, z) - sampleDensityClamped(density, x - 0.5f, y, z);
        float dy = sampleDensityClamped(density, x, y + 0.5f, z) - sampleDensityClamped(density, x, y - 0.5f, z);
        float dz = sampleDensityClamped(density, x, y, z + 0.5f) - sampleDensityClamped(density, x, y, z - 0.5f);
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 1.0e-6f) {
            return new float[]{0f, 1f, 0f};
        }
        return new float[]{dx / length, dy / length, dz / length};
    }

    private static float sampleDensityClamped(float[][][] density, float x, float y, float z) {
        float clampedX = clampToScratch(x);
        float clampedY = clampToScratch(y);
        float clampedZ = clampToScratch(z);

        int x0 = (int) Math.floor(clampedX);
        int y0 = (int) Math.floor(clampedY);
        int z0 = (int) Math.floor(clampedZ);
        int x1 = Math.min(x0 + 1, SIZE);
        int y1 = Math.min(y0 + 1, SIZE);
        int z1 = Math.min(z0 + 1, SIZE);

        float tx = clampedX - x0;
        float ty = clampedY - y0;
        float tz = clampedZ - z0;

        float c000 = density[x0][y0][z0];
        float c100 = density[x1][y0][z0];
        float c010 = density[x0][y1][z0];
        float c110 = density[x1][y1][z0];
        float c001 = density[x0][y0][z1];
        float c101 = density[x1][y0][z1];
        float c011 = density[x0][y1][z1];
        float c111 = density[x1][y1][z1];

        float c00 = lerp(c000, c100, tx);
        float c10 = lerp(c010, c110, tx);
        float c01 = lerp(c001, c101, tx);
        float c11 = lerp(c011, c111, tx);
        float c0 = lerp(c00, c10, ty);
        float c1 = lerp(c01, c11, ty);
        return lerp(c0, c1, tz);
    }

    private static void fillScratch(VoxelChunk center,
                                    VoxelChunk plusX,
                                    VoxelChunk plusY,
                                    VoxelChunk plusZ,
                                    float[][][] density,
                                    VoxelType[][][] types) {
        for (int x = 0; x < SCRATCH_SIZE; x++) {
            for (int y = 0; y < SCRATCH_SIZE; y++) {
                for (int z = 0; z < SCRATCH_SIZE; z++) {
                    VoxelChunk chunk = center;
                    int sx = x;
                    int sy = y;
                    int sz = z;

                    if (x == SIZE) {
                        chunk = plusX;
                        sx = 0;
                    }
                    if (y == SIZE) {
                        chunk = plusY;
                        sy = 0;
                    }
                    if (z == SIZE) {
                        chunk = plusZ;
                        sz = 0;
                    }

                    if (x == SIZE && y == SIZE) {
                        chunk = plusX != null ? plusX : plusY;
                        sx = 0;
                        sy = 0;
                    }
                    if (x == SIZE && z == SIZE) {
                        chunk = plusX != null ? plusX : plusZ;
                        sx = 0;
                        sz = 0;
                    }
                    if (y == SIZE && z == SIZE) {
                        chunk = plusY != null ? plusY : plusZ;
                        sy = 0;
                        sz = 0;
                    }
                    if (x == SIZE && y == SIZE && z == SIZE) {
                        chunk = plusX != null ? plusX : (plusY != null ? plusY : plusZ);
                        sx = 0;
                        sy = 0;
                        sz = 0;
                    }

                    if (chunk == null) {
                        density[x][y][z] = -1f;
                        types[x][y][z] = VoxelType.AIR;
                    } else {
                        density[x][y][z] = chunk.getDensity(sx, sy, sz);
                        types[x][y][z] = chunk.getVoxel(sx, sy, sz);
                    }
                }
            }
        }
    }

    public static boolean hasRequiredNeighbors(ChunkKey key,
                                               Function<ChunkKey, VoxelChunk> chunkLookup) {
        return chunkLookup.apply(new ChunkKey(key.cx() + 1, key.cy(), key.cz())) != null
                && chunkLookup.apply(new ChunkKey(key.cx(), key.cy() + 1, key.cz())) != null
                && chunkLookup.apply(new ChunkKey(key.cx(), key.cy(), key.cz() + 1)) != null;
    }

    private static boolean crossesIsoSurface(float a, float b) {
        boolean aInside = a > ISO_LEVEL;
        boolean bInside = b > ISO_LEVEL;
        return aInside != bInside;
    }

    private static VoxelType chooseSurfaceType(CellCorner[] corners) {
        VoxelType bestType = VoxelType.STONE;
        float bestDensity = Float.NEGATIVE_INFINITY;
        boolean foundTransparent = false;
        for (CellCorner corner : corners) {
            if (corner.density() <= ISO_LEVEL || corner.type() == null || !corner.type().isSolid()) {
                continue;
            }
            if (corner.type().isTransparent()) {
                if (!foundTransparent || corner.density() > bestDensity) {
                    bestType = corner.type();
                    bestDensity = corner.density();
                    foundTransparent = true;
                }
            } else if (!foundTransparent && corner.density() > bestDensity) {
                bestType = corner.type();
                bestDensity = corner.density();
            }
        }
        return bestDensity == Float.NEGATIVE_INFINITY ? VoxelType.STONE : bestType;
    }

    private static VoxelType dominantSolidType(VoxelType aType,
                                               float aDensity,
                                               VoxelType bType,
                                               float bDensity) {
        VoxelType normalizedA = normalizeType(aType, aDensity);
        VoxelType normalizedB = normalizeType(bType, bDensity);
        boolean aSolid = aDensity > ISO_LEVEL && normalizedA.isSolid();
        boolean bSolid = bDensity > ISO_LEVEL && normalizedB.isSolid();
        if (aSolid && (!bSolid || aDensity >= bDensity)) {
            return normalizedA;
        }
        if (bSolid) {
            return normalizedB;
        }
        return normalizedA.isSolid() ? normalizedA : (normalizedB.isSolid() ? normalizedB : VoxelType.STONE);
    }

    private static VoxelType normalizeType(VoxelType type, float density) {
        if (density <= ISO_LEVEL) {
            return VoxelType.AIR;
        }
        if (type == null || !type.isSolid()) {
            return VoxelType.STONE;
        }
        return type;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float clampToScratch(float value) {
        return Math.max(0f, Math.min(SIZE, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private record CellCorner(int x, int y, int z, float density, VoxelType type) {
    }

    private record IntersectionPoint(float x, float y, float z,
                                     float nx, float ny, float nz,
                                     VoxelType type) {
    }

    private static final class SectionCollector {
        private static final int TRIANGLE_VERTEX_COUNT = 3;

        private final List<MeshSectionBuilder> sections = new ArrayList<>();

        MeshSectionBuilder currentSectionForTriangle() {
            MeshSectionBuilder current = currentSection();
            if (current.vertexCount() + TRIANGLE_VERTEX_COUNT > MeshData.MAX_VERTICES_PER_SECTION) {
                current = new MeshSectionBuilder();
                sections.add(current);
            }
            return current;
        }

        boolean isEmpty() {
            for (MeshSectionBuilder section : sections) {
                if (!section.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        List<MeshSectionData> toSections() {
            List<MeshSectionData> result = new ArrayList<>();
            for (MeshSectionBuilder section : sections) {
                if (!section.isEmpty()) {
                    result.add(new MeshSectionData(section.vertices.toFloatArray(), section.indices.toShortArray()));
                }
            }
            return result;
        }

        private MeshSectionBuilder currentSection() {
            if (sections.isEmpty()) {
                MeshSectionBuilder section = new MeshSectionBuilder();
                sections.add(section);
            }
            return sections.get(sections.size() - 1);
        }
    }

    private static final class MeshSectionBuilder {
        private final FloatCollector vertices = new FloatCollector();
        private final ShortCollector indices = new ShortCollector();

        int vertexCount() {
            return vertices.elementCount() / MeshData.FLOATS_PER_VERTEX;
        }

        boolean isEmpty() {
            return vertices.elementCount() == 0 || indices.elementCount() == 0;
        }
    }

    private static final class FloatCollector {
        private float[] values = new float[256];
        private int size;

        void add(float value) {
            if (size == values.length) {
                float[] next = new float[values.length * 2];
                System.arraycopy(values, 0, next, 0, values.length);
                values = next;
            }
            values[size++] = value;
        }

        int elementCount() {
            return size;
        }

        float[] toFloatArray() {
            float[] out = new float[size];
            System.arraycopy(values, 0, out, 0, size);
            return out;
        }
    }

    private static final class ShortCollector {
        private short[] values = new short[256];
        private int size;

        void add(short value) {
            if (size == values.length) {
                short[] next = new short[values.length * 2];
                System.arraycopy(values, 0, next, 0, values.length);
                values = next;
            }
            values[size++] = value;
        }

        int elementCount() {
            return size;
        }

        short[] toShortArray() {
            short[] out = new short[size];
            System.arraycopy(values, 0, out, 0, size);
            return out;
        }
    }
}
