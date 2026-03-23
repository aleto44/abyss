package com.example.abyss.world.meshing;

import com.badlogic.gdx.graphics.Color;
import com.example.abyss.world.ChunkFaceConnectivity;
import com.example.abyss.world.VoxelChunk;
import com.example.abyss.world.voxel.VoxelType;

import java.util.ArrayList;
import java.util.List;

/**
 * Background-safe chunk mesher.
 * <p>
 * Emits a face for every voxel side that borders a non-solid (air/transparent)
 * neighbour. Each exposed face becomes two triangles forming a quad, with a
 * flat outward normal. Opaque and transparent faces are kept in separate
 * section lists to support depth-sorting on the render side.
 * </p>
 */
public final class ChunkMesher {

    private static final int SIZE = VoxelChunk.SIZE;

    /** The six cardinal face directions: +X, -X, +Y, -Y, +Z, -Z. */
    private static final int[][] FACE_NORMALS = {
            { 1,  0,  0},
            {-1,  0,  0},
            { 0,  1,  0},
            { 0, -1,  0},
            { 0,  0,  1},
            { 0,  0, -1}
    };

    /**
     * For each face direction the four corners of the quad, expressed as
     * offsets from the voxel's (x, y, z) origin.
     * Winding order is counter-clockwise when viewed from outside.
     */
    private static final float[][][] FACE_QUADS = {
            // +X
            {{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}},
            // -X
            {{0, 0, 1}, {0, 1, 1}, {0, 1, 0}, {0, 0, 0}},
            // +Y
            {{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},
            // -Y
            {{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}},
            // +Z
            {{1, 0, 1}, {1, 1, 1}, {0, 1, 1}, {0, 0, 1}},
            // -Z
            {{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}}
    };

    private ChunkMesher() {
    }

    public static MeshData buildMesh(VoxelChunk center,
                                     VoxelChunk plusX,
                                     VoxelChunk plusY,
                                     VoxelChunk plusZ,
                                     VoxelChunk minusX,
                                     VoxelChunk minusY,
                                     VoxelChunk minusZ) {
        SectionCollector opaqueSections = new SectionCollector();
        SectionCollector transparentSections = new SectionCollector();
        NeighbourSampler neighbours = new NeighbourSampler(center, plusX, plusY, plusZ, minusX, minusY, minusZ);

        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    VoxelType type = center.getVoxel(x, y, z);
                    boolean hasGeometry = type != null && (type.isSolid() || type.isTransparent());
                    if (hasGeometry) {
                        emitExposedFaces(x, y, z, type, neighbours, opaqueSections, transparentSections);
                    }
                }
            }
        }

        // Connectivity was already computed at generation time for fresh chunks.
        // Only recompute it here when the chunk has been player-edited (isDirty),
        // since that's the only time voxel data has changed since the last compute.
        if (center.isDirty()) {
            center.setFaceConnectivity(ChunkFaceConnectivity.compute(center));
        }
        if (opaqueSections.isEmpty() && transparentSections.isEmpty()) {
            return MeshData.EMPTY;
        }
        return new MeshData(opaqueSections.toSections(), transparentSections.toSections());
    }

    private static void emitExposedFaces(int x, int y, int z,
                                         VoxelType type,
                                         NeighbourSampler neighbours,
                                         SectionCollector opaqueSections,
                                         SectionCollector transparentSections) {
        SectionCollector sections = type.isTransparent() ? transparentSections : opaqueSections;

        for (int face = 0; face < FACE_NORMALS.length; face++) {
            int[] normal = FACE_NORMALS[face];
            VoxelType neighbour = neighbours.sample(x + normal[0], y + normal[1], z + normal[2]);

            if (isFaceVisible(type, neighbour)) {
                emitQuad(x, y, z, face, type, sections);
            }
        }
    }

    /**
     * Returns true if a face between {@code type} and its {@code neighbour} should be emitted.
     * A face is visible when the neighbour does not fully hide it:
     * <ul>
     *   <li>Solid opaque neighbours always hide the face — no face needed.</li>
     *   <li>A transparent voxel facing the same transparent type is an interior — no face needed.</li>
     *   <li>A non-solid voxel (e.g. water) facing any solid neighbour is hidden — no face needed.</li>
     * </ul>
     */
    private static boolean isFaceVisible(VoxelType type, VoxelType neighbour) {
        if (neighbour != null && neighbour.isSolid() && !neighbour.isTransparent()) {
            return false;
        }
        if (type.isTransparent() && neighbour == type) {
            return false;
        }
        if (!type.isSolid() && neighbour != null && neighbour.isSolid()) {
            return false;
        }
        return true;
    }

    /** Emits two triangles forming a quad for the given face of voxel (x, y, z). */
    private static void emitQuad(int x, int y, int z,
                                 int faceIndex,
                                 VoxelType type,
                                 SectionCollector sections) {
        float[] normal = {
                FACE_NORMALS[faceIndex][0],
                FACE_NORMALS[faceIndex][1],
                FACE_NORMALS[faceIndex][2]
        };
        float[][] quad = FACE_QUADS[faceIndex];
        Color color = type.getBaseColor();

        MeshSectionBuilder section = sections.currentSectionForQuad();
        short base = (short) section.vertexCount();

        for (float[] corner : quad) {
            section.vertices.add(x + corner[0]);
            section.vertices.add(y + corner[1]);
            section.vertices.add(z + corner[2]);
            section.vertices.add(normal[0]);
            section.vertices.add(normal[1]);
            section.vertices.add(normal[2]);
            section.vertices.add(color.r);
            section.vertices.add(color.g);
            section.vertices.add(color.b);
            section.vertices.add(color.a);
        }

        // Triangle 1: 0-1-2
        section.indices.add(base);
        section.indices.add((short) (base + 1));
        section.indices.add((short) (base + 2));
        // Triangle 2: 0-2-3
        section.indices.add(base);
        section.indices.add((short) (base + 2));
        section.indices.add((short) (base + 3));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Holds references to all six axis-aligned neighbours of a chunk and
     * exposes a single {@link #sample} method for out-of-bounds coordinate
     * lookups during meshing.
     */
    private static final class NeighbourSampler {
        private final VoxelChunk center;
        private final VoxelChunk plusX;
        private final VoxelChunk plusY;
        private final VoxelChunk plusZ;
        private final VoxelChunk minusX;
        private final VoxelChunk minusY;
        private final VoxelChunk minusZ;

        NeighbourSampler(VoxelChunk center,
                         VoxelChunk plusX, VoxelChunk plusY, VoxelChunk plusZ,
                         VoxelChunk minusX, VoxelChunk minusY, VoxelChunk minusZ) {
            this.center = center;
            this.plusX  = plusX;
            this.plusY  = plusY;
            this.plusZ  = plusZ;
            this.minusX = minusX;
            this.minusY = minusY;
            this.minusZ = minusZ;
        }

        /**
         * Samples a voxel type at local chunk coordinates that may spill into any
         * of the six axis-aligned neighbouring chunks. Returns {@code null} when
         * the relevant neighbour chunk is not loaded (treat as air/unculled).
         */
        VoxelType sample(int x, int y, int z) {
            if (x < 0)      { return (minusX != null) ? minusX.getVoxel(SIZE - 1, y, z) : null; }
            if (y < 0)      { return (minusY != null) ? minusY.getVoxel(x, SIZE - 1, z) : null; }
            if (z < 0)      { return (minusZ != null) ? minusZ.getVoxel(x, y, SIZE - 1) : null; }
            if (x >= SIZE)  { return (plusX  != null) ? plusX.getVoxel(0, y, z)          : null; }
            if (y >= SIZE)  { return (plusY  != null) ? plusY.getVoxel(x, 0, z)          : null; }
            if (z >= SIZE)  { return (plusZ  != null) ? plusZ.getVoxel(x, y, 0)          : null; }
            return center.getVoxel(x, y, z);
        }
    }

    private static final class SectionCollector {
        private static final int QUAD_VERTEX_COUNT = 4;

        private final List<MeshSectionBuilder> sections = new ArrayList<>();

        MeshSectionBuilder currentSectionForQuad() {
            MeshSectionBuilder current = currentSection();
            if (current.vertexCount() + QUAD_VERTEX_COUNT > MeshData.MAX_VERTICES_PER_SECTION) {
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
                    result.add(new MeshSectionData(
                            section.vertices.toFloatArray(),
                            section.indices.toShortArray()));
                }
            }
            return result;
        }

        private MeshSectionBuilder currentSection() {
            if (sections.isEmpty()) {
                sections.add(new MeshSectionBuilder());
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
