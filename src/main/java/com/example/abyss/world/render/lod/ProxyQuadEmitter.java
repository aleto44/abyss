package com.example.abyss.world.render.lod;

import com.badlogic.gdx.graphics.Color;

/**
 * Pure-static quad emission helpers for proxy region surface meshes.
 * All methods write directly into a {@link ProxyMeshSectionBuilder}.
 */
final class ProxyQuadEmitter {

    private ProxyQuadEmitter() {}

    /** Emits an upward-facing quad on the XZ plane. Size is {@code step × step} voxels. */
    static void emitQuadY(ProxyMeshSectionBuilder b, float lx, float ly, float lz,
                          int step, Color color) {
        emitQuad(b, color, 0f, 1f, 0f,
                lx,        ly, lz,
                lx,        ly, lz + step,
                lx + step, ly, lz + step,
                lx + step, ly, lz);
    }

    /**
     * Emits a vertical wall quad parallel to the YZ plane at x = {@code lx}.
     * {@code facingPositiveX} controls the outward normal direction.
     */
    static void emitQuadXFace(ProxyMeshSectionBuilder b, float lx, float wallBottom, float lz,
                               float height, int step, Color color, boolean facingPositiveX) {
        float nx  = facingPositiveX ? 1f : -1f;
        float top  = wallBottom + height;
        float zFar = lz + step;
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
    static void emitQuadZFace(ProxyMeshSectionBuilder b, float lx, float wallBottom, float lz,
                               float height, int step, Color color, boolean facingPositiveZ) {
        float nz  = facingPositiveZ ? 1f : -1f;
        float top  = wallBottom + height;
        float xFar = lx + step;
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

    private static void emitQuad(ProxyMeshSectionBuilder b, Color color,
                                  float nx, float ny, float nz,
                                  float x0, float y0, float z0,
                                  float x1, float y1, float z1,
                                  float x2, float y2, float z2,
                                  float x3, float y3, float z3) {
        ProxyMeshSectionBuilder.SectionData section = b.currentSection();
        short base = (short) section.vertexCount();

        section.addVertex(x0, y0, z0, nx, ny, nz, color.r, color.g, color.b, color.a);
        section.addVertex(x1, y1, z1, nx, ny, nz, color.r, color.g, color.b, color.a);
        section.addVertex(x2, y2, z2, nx, ny, nz, color.r, color.g, color.b, color.a);
        section.addVertex(x3, y3, z3, nx, ny, nz, color.r, color.g, color.b, color.a);

        section.addIndex(base);
        section.addIndex((short) (base + 1));
        section.addIndex((short) (base + 2));
        section.addIndex(base);
        section.addIndex((short) (base + 2));
        section.addIndex((short) (base + 3));
    }
}
