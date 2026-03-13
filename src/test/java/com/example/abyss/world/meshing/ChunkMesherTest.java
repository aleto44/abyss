package com.example.abyss.world.meshing;

import com.example.abyss.world.VoxelChunk;
import com.example.abyss.world.voxel.VoxelData;
import com.example.abyss.world.voxel.VoxelType;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkMesherTest {

    @Test
    void buildMeshReturnsEmptyForUniformAir() {
        VoxelChunk center = createChunk(0, 0, 0, -1f, VoxelType.AIR);
        VoxelChunk plusX = createChunk(1, 0, 0, -1f, VoxelType.AIR);
        VoxelChunk plusY = createChunk(0, 1, 0, -1f, VoxelType.AIR);
        VoxelChunk plusZ = createChunk(0, 0, 1, -1f, VoxelType.AIR);

        MeshData mesh = ChunkMesher.buildMesh(center, plusX, plusY, plusZ);

        assertTrue(mesh.isEmpty());
    }

    @Test
    void buildMeshReturnsEmptyForUniformSolid() {
        VoxelChunk center = createChunk(0, 0, 0, 1f, VoxelType.STONE);
        VoxelChunk plusX = createChunk(1, 0, 0, 1f, VoxelType.STONE);
        VoxelChunk plusY = createChunk(0, 1, 0, 1f, VoxelType.STONE);
        VoxelChunk plusZ = createChunk(0, 0, 1, 1f, VoxelType.STONE);

        MeshData mesh = ChunkMesher.buildMesh(center, plusX, plusY, plusZ);

        assertTrue(mesh.isEmpty());
    }

    @Test
    void buildMeshGeneratesSurfaceForDensityPlane() {
        VoxelChunk center = createPlaneChunk(0, 0, 0, 15.5f);
        VoxelChunk plusX = createPlaneChunk(1, 0, 0, 15.5f);
        VoxelChunk plusY = createPlaneChunk(0, 1, 0, 15.5f);
        VoxelChunk plusZ = createPlaneChunk(0, 0, 1, 15.5f);

        MeshData mesh = ChunkMesher.buildMesh(center, plusX, plusY, plusZ);

        assertFalse(mesh.isEmpty());
        assertTrue(mesh.opaqueIndexCount() > 0);
        assertEquals(0, mesh.transparentIndexCount());
        assertContainsVertexNear(mesh.firstOpaqueVertices(), 15.5f, 15.5f, 15.5f, 0.76f);
    }

    @Test
    void buildMeshRoutesTransparentSolidTypesSeparately() {
        VoxelChunk center = createChunk(0, 0, 0, -1f, VoxelType.AIR);
        VoxelChunk plusX = createChunk(1, 0, 0, -1f, VoxelType.AIR);
        VoxelChunk plusY = createChunk(0, 1, 0, -1f, VoxelType.AIR);
        VoxelChunk plusZ = createChunk(0, 0, 1, -1f, VoxelType.AIR);

        for (int x = 8; x <= 14; x++) {
            for (int y = 8; y <= 14; y++) {
                for (int z = 8; z <= 14; z++) {
                    float dx = x - 11f;
                    float dy = y - 11f;
                    float dz = z - 11f;
                    float density = 3.2f - (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    center.setDensity(x, y, z, density);
                    center.setVoxel(x, y, z, density > 0f ? VoxelType.CRYSTAL : VoxelType.AIR);
                }
            }
        }

        MeshData mesh = ChunkMesher.buildMesh(center, plusX, plusY, plusZ);

        assertEquals(0, mesh.opaqueIndexCount());
        assertTrue(mesh.transparentIndexCount() > 0);
    }


    private static VoxelChunk createChunk(int cx, int cy, int cz, float density, VoxelType type) {
        VoxelData data = new VoxelData();
        for (int x = 0; x < VoxelChunk.SIZE; x++) {
            for (int y = 0; y < VoxelChunk.SIZE; y++) {
                for (int z = 0; z < VoxelChunk.SIZE; z++) {
                    data.setDensity(x, y, z, density);
                    data.setType(x, y, z, type);
                }
            }
        }
        return new VoxelChunk(cx, cy, cz, data);
    }

    private static VoxelChunk createPlaneChunk(int cx, int cy, int cz, float planeX) {
        VoxelData data = new VoxelData();
        int worldOffsetX = cx * VoxelChunk.SIZE;
        for (int x = 0; x < VoxelChunk.SIZE; x++) {
            for (int y = 0; y < VoxelChunk.SIZE; y++) {
                for (int z = 0; z < VoxelChunk.SIZE; z++) {
                    float density = (worldOffsetX + x) - planeX;
                    data.setDensity(x, y, z, density);
                    data.setType(x, y, z, density > 0f ? VoxelType.STONE : VoxelType.AIR);
                }
            }
        }
        return new VoxelChunk(cx, cy, cz, data);
    }

    private static void assertContainsVertexNear(float[] vertices, float x, float y, float z, float tolerance) {
        Set<Integer> inspected = new HashSet<>();
        for (int i = 0; i < vertices.length; i += MeshData.FLOATS_PER_VERTEX) {
            inspected.add(i);
            float dx = Math.abs(vertices[i] - x);
            float dy = Math.abs(vertices[i + 1] - y);
            float dz = Math.abs(vertices[i + 2] - z);
            if (dx <= tolerance && dy <= tolerance && dz <= tolerance) {
                return;
            }
        }
        throw new AssertionError("Expected a vertex near (" + x + ", " + y + ", " + z + ") but checked " + inspected.size() + " vertices.");
    }
}
