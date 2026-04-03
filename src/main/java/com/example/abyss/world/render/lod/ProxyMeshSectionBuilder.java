package com.example.abyss.world.render.lod;

import com.example.abyss.world.meshing.MeshData;
import com.example.abyss.world.meshing.MeshSectionData;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates vertex and index data for a proxy region mesh, automatically splitting
 * into new sections when the {@code short} index limit is approached.
 */
final class ProxyMeshSectionBuilder {

    // Vertex layout: position(3) + normal(3) + color(4) = 10 floats
    static final int FLOATS_PER_VERTEX = 10;
    private static final int MAX_VERTICES_PER_SECTION = Short.MAX_VALUE;

    private final List<SectionData> sections = new ArrayList<>();

    SectionData currentSection() {
        boolean needsNewSection = sections.isEmpty()
                || sections.get(sections.size() - 1).willOverflowNextQuad();
        if (needsNewSection) {
            sections.add(new SectionData());
        }
        return sections.get(sections.size() - 1);
    }

    boolean isEmpty() {
        return sections.isEmpty() || sections.stream().allMatch(SectionData::isEmpty);
    }

    MeshData toMeshData() {
        List<MeshSectionData> opaque = new ArrayList<>();
        for (SectionData s : sections) {
            if (!s.isEmpty()) {
                opaque.add(new MeshSectionData(s.vertices(), s.indices()));
            }
        }
        return new MeshData(opaque, List.of());
    }

    // ----------------------------------------------------------------
    // Section data
    // ----------------------------------------------------------------

    static final class SectionData {

        private float[] verts = new float[256];
        private short[] inds  = new short[256];
        private int vSize;
        private int iSize;

        void addVertex(float x, float y, float z,
                       float nx, float ny, float nz,
                       float r, float g, float b, float a) {
            ensureVertexCapacity();
            verts[vSize++] = x;  verts[vSize++] = y;  verts[vSize++] = z;
            verts[vSize++] = nx; verts[vSize++] = ny; verts[vSize++] = nz;
            verts[vSize++] = r;  verts[vSize++] = g;  verts[vSize++] = b;  verts[vSize++] = a;
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
