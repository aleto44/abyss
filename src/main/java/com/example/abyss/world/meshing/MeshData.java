package com.example.abyss.world.meshing;

import java.util.List;

/**
 * Raw CPU-side mesh geometry produced by the chunk mesher.
 * <p>
 * Kept as plain arrays so it can be created safely on a background thread.
 * The main (GL) thread promotes this into real LibGDX meshes
 * via the meshing pipeline.
 * </p>
 * <p>
 * Vertex layout (floats per vertex):
 * <pre>
 *   x, y, z,          – position   (3)
 *   nx, ny, nz,       – normal     (3)
 *   r, g, b, a        – color      (4)
 * </pre>
 * Total: {@value #FLOATS_PER_VERTEX} floats per vertex.
 * </p>
 *
 * @param opaqueSections      mesh sections for opaque geometry (may be empty, never null)
 * @param transparentSections mesh sections for transparent geometry (may be empty, never null)
 */
public record MeshData(
        List<MeshSectionData> opaqueSections,
        List<MeshSectionData> transparentSections
) {
    /** Number of floats per vertex: position(3) + normal(3) + color(4). */
    public static final int FLOATS_PER_VERTEX = 10;

    /** Maximum vertex count addressable by 16-bit mesh indices. */
    public static final int MAX_VERTICES_PER_SECTION = Short.MAX_VALUE;

    /** Singleton empty result — reused when a chunk produces no geometry. */
    public static final MeshData EMPTY = new MeshData(List.of(), List.of());

    /** Returns true if both the opaque and transparent section lists are empty. */
    public boolean isEmpty() {
        return opaqueSections.isEmpty() && transparentSections.isEmpty();
    }

    public int opaqueIndexCount() {
        return sumIndexCount(opaqueSections);
    }

    public int transparentIndexCount() {
        return sumIndexCount(transparentSections);
    }

    public float[] firstOpaqueVertices() {
        if (opaqueSections.isEmpty()) {
            return new float[0];
        }
        return opaqueSections.get(0).vertices();
    }

    public float[] firstTransparentVertices() {
        if (transparentSections.isEmpty()) {
            return new float[0];
        }
        return transparentSections.get(0).vertices();
    }

    private static int sumIndexCount(List<MeshSectionData> sections) {
        int total = 0;
        for (MeshSectionData section : sections) {
            total += section.indices().length;
        }
        return total;
    }
}