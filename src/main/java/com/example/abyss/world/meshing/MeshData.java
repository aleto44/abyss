package com.example.abyss.world.meshing;

/**
 * Raw CPU-side mesh geometry produced by the chunk mesher.
 * <p>
 * Kept as plain arrays so it can be created safely on a background thread.
 * The main (GL) thread promotes this into a real LibGDX {@link com.badlogic.gdx.graphics.Mesh}
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
 * @param opaqueVertices      vertex buffer for opaque geometry (may be empty, never null)
 * @param opaqueIndices       index buffer for opaque geometry  (may be empty, never null)
 * @param transparentVertices vertex buffer for transparent geometry (may be empty, never null)
 * @param transparentIndices  index buffer for transparent geometry  (may be empty, never null)
 */
public record MeshData(
        float[] opaqueVertices,
        short[] opaqueIndices,
        float[] transparentVertices,
        short[] transparentIndices
) {
    /** Number of floats per vertex: position(3) + normal(3) + color(4). */
    public static final int FLOATS_PER_VERTEX = 10;

    /** Singleton empty result — reused when a chunk produces no geometry. */
    public static final MeshData EMPTY = new MeshData(
            new float[0], new short[0], new float[0], new short[0]);

    /** Returns true if both the opaque and transparent buffers are empty. */
    public boolean isEmpty() {
        return opaqueIndices.length == 0 && transparentIndices.length == 0;
    }
}