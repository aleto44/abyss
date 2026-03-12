package com.example.abyss.world.meshing;

import com.badlogic.gdx.graphics.Color;
import com.example.abyss.world.ChunkKey;
import com.example.abyss.world.VoxelChunk;
import com.example.abyss.world.voxel.VoxelType;

import java.util.ArrayList;
import java.util.List;

/**
 * Background-safe chunk mesher.
 * <p>
 * This first implementation builds a surface mesh from voxel faces while still
 * sampling one voxel into each positive neighbor chunk. That gives us the queue,
 * seam-safety contract, disposal path, and transparent split now, while keeping
 * the actual meshing logic compact enough to validate quickly.
 * </p>
 */
public final class ChunkMesher {

    private static final int SIZE = VoxelChunk.SIZE;
    private static final int SCRATCH_SIZE = SIZE + 1;

    private ChunkMesher() {
    }

    public static MeshData buildMesh(VoxelChunk center,
                                     VoxelChunk plusX,
                                     VoxelChunk plusY,
                                     VoxelChunk plusZ) {
        float[][][] density = new float[SCRATCH_SIZE][SCRATCH_SIZE][SCRATCH_SIZE];
        VoxelType[][][] types = new VoxelType[SCRATCH_SIZE][SCRATCH_SIZE][SCRATCH_SIZE];

        fillScratch(center, plusX, plusY, plusZ, density, types);

        FloatCollector opaqueVertices = new FloatCollector();
        ShortCollector opaqueIndices = new ShortCollector();
        FloatCollector transparentVertices = new FloatCollector();
        ShortCollector transparentIndices = new ShortCollector();

        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    VoxelType type = types[x][y][z];
                    if (type == null || !type.isSolid()) {
                        continue;
                    }

                    emitFaceIfVisible(type, density, types, x, y, z, 1, 0, 0,
                            opaqueVertices, opaqueIndices, transparentVertices, transparentIndices);
                    emitFaceIfVisible(type, density, types, x, y, z, 0, 1, 0,
                            opaqueVertices, opaqueIndices, transparentVertices, transparentIndices);
                    emitFaceIfVisible(type, density, types, x, y, z, 0, 0, 1,
                            opaqueVertices, opaqueIndices, transparentVertices, transparentIndices);
                    emitFaceIfVisible(type, density, types, x, y, z, -1, 0, 0,
                            opaqueVertices, opaqueIndices, transparentVertices, transparentIndices);
                    emitFaceIfVisible(type, density, types, x, y, z, 0, -1, 0,
                            opaqueVertices, opaqueIndices, transparentVertices, transparentIndices);
                    emitFaceIfVisible(type, density, types, x, y, z, 0, 0, -1,
                            opaqueVertices, opaqueIndices, transparentVertices, transparentIndices);
                }
            }
        }

        if (opaqueIndices.isEmpty() && transparentIndices.isEmpty()) {
            return MeshData.EMPTY;
        }

        return new MeshData(
                opaqueVertices.toArray(),
                opaqueIndices.toArray(),
                transparentVertices.toArray(),
                transparentIndices.toArray());
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

    private static void emitFaceIfVisible(VoxelType type,
                                          float[][][] density,
                                          VoxelType[][][] types,
                                          int x,
                                          int y,
                                          int z,
                                          int nx,
                                          int ny,
                                          int nz,
                                          FloatCollector opaqueVertices,
                                          ShortCollector opaqueIndices,
                                          FloatCollector transparentVertices,
                                          ShortCollector transparentIndices) {
        int ox = x + nx;
        int oy = y + ny;
        int oz = z + nz;

        if (ox < 0 || oy < 0 || oz < 0 || ox >= SCRATCH_SIZE || oy >= SCRATCH_SIZE || oz >= SCRATCH_SIZE) {
            return;
        }

        VoxelType neighborType = types[ox][oy][oz];
        float neighborDensity = density[ox][oy][oz];
        boolean visible = neighborType == null || !neighborType.isSolid() || neighborDensity <= 0f;
        if (!visible) {
            return;
        }

        FloatCollector vertices = type.isTransparent() ? transparentVertices : opaqueVertices;
        ShortCollector indices = type.isTransparent() ? transparentIndices : opaqueIndices;
        short baseIndex = (short) (vertices.size() / MeshData.FLOATS_PER_VERTEX);
        if (baseIndex > Short.MAX_VALUE - 4) {
            throw new IllegalStateException("Chunk mesh exceeded 16-bit index capacity for face meshing.");
        }

        float x0 = x;
        float y0 = y;
        float z0 = z;
        float x1 = x + 1f;
        float y1 = y + 1f;
        float z1 = z + 1f;

        Color color = type.getBaseColor();
        float r = color.r;
        float g = color.g;
        float b = color.b;
        float a = color.a;

        float[][] positions = faceVertices(x0, y0, z0, x1, y1, z1, nx, ny, nz);
        for (float[] pos : positions) {
            vertices.add(pos[0]);
            vertices.add(pos[1]);
            vertices.add(pos[2]);
            vertices.add(nx);
            vertices.add(ny);
            vertices.add(nz);
            vertices.add(r);
            vertices.add(g);
            vertices.add(b);
            vertices.add(a);
        }

        indices.add(baseIndex);
        indices.add((short) (baseIndex + 1));
        indices.add((short) (baseIndex + 2));
        indices.add(baseIndex);
        indices.add((short) (baseIndex + 2));
        indices.add((short) (baseIndex + 3));
    }

    private static float[][] faceVertices(float x0,
                                          float y0,
                                          float z0,
                                          float x1,
                                          float y1,
                                          float z1,
                                          int nx,
                                          int ny,
                                          int nz) {
        if (nx > 0) {
            return new float[][]{{x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}, {x1, y0, z1}};
        }
        if (nx < 0) {
            return new float[][]{{x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}, {x0, y0, z0}};
        }
        if (ny > 0) {
            return new float[][]{{x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}, {x0, y1, z0}};
        }
        if (ny < 0) {
            return new float[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}};
        }
        if (nz > 0) {
            return new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
        }
        return new float[][]{{x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}};
    }

    public static boolean hasRequiredNeighbors(ChunkKey key,
                                               java.util.function.Function<ChunkKey, VoxelChunk> chunkLookup) {
        return chunkLookup.apply(new ChunkKey(key.cx() + 1, key.cy(), key.cz())) != null
                && chunkLookup.apply(new ChunkKey(key.cx(), key.cy() + 1, key.cz())) != null
                && chunkLookup.apply(new ChunkKey(key.cx(), key.cy(), key.cz() + 1)) != null;
    }

    private static final class FloatCollector {
        private final List<Float> values = new ArrayList<>();

        void add(float value) {
            values.add(value);
        }

        int size() {
            return values.size();
        }

        boolean isEmpty() {
            return values.isEmpty();
        }

        float[] toArray() {
            float[] result = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                result[i] = values.get(i);
            }
            return result;
        }
    }

    private static final class ShortCollector {
        private final List<Short> values = new ArrayList<>();

        void add(short value) {
            values.add(value);
        }

        boolean isEmpty() {
            return values.isEmpty();
        }

        short[] toArray() {
            short[] result = new short[values.size()];
            for (int i = 0; i < values.size(); i++) {
                result[i] = values.get(i);
            }
            return result;
        }
    }
}
