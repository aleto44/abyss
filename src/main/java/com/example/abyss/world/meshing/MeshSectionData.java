package com.example.abyss.world.meshing;

/**
 * One CPU-side mesh section that fits within LibGDX's 16-bit index limit.
 *
 * @param vertices packed vertex buffer using {@link MeshData#FLOATS_PER_VERTEX}
 * @param indices 16-bit local indices for this section
 */
public record MeshSectionData(
        float[] vertices,
        short[] indices
) {
    public boolean isEmpty() {
        return vertices.length == 0 || indices.length == 0;
    }

    public int vertexCount() {
        return vertices.length / MeshData.FLOATS_PER_VERTEX;
    }
}
