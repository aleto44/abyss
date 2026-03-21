package com.example.abyss.world.gen;

import com.example.abyss.world.voxel.VoxelData;
import com.example.abyss.world.voxel.VoxelType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProceduralGeneratorTest {

    @Test
    void generateChunkIsDeterministicForSameSeedAndChunkCoordinates() {
        ProceduralGenerator firstGenerator = new ProceduralGenerator(9876L);
        ProceduralGenerator secondGenerator = new ProceduralGenerator(9876L);

        VoxelData firstChunk = firstGenerator.generateChunk(3, -15, 2);
        VoxelData secondChunk = secondGenerator.generateChunk(3, -15, 2);

        assertArrayEquals(firstChunk.getDensityField(), secondChunk.getDensityField());
        assertArrayEquals(firstChunk.getTypeField(), secondChunk.getTypeField());
    }

    @Test
    void differentSeedsProduceMeaningfullyDifferentChunkSignatures() {
        ProceduralGenerator firstGenerator = new ProceduralGenerator(111L);
        ProceduralGenerator secondGenerator = new ProceduralGenerator(222L);

        List<String> firstSignature = collectChunkSignature(firstGenerator);
        List<String> secondSignature = collectChunkSignature(secondGenerator);

        assertNotEquals(firstSignature, secondSignature);
    }

    @Test
    void oceaniaOpenSpaceBelowWaterlineContainsWater() {
        ProceduralGenerator generator = new ProceduralGenerator(321L);
        int worldY = ProceduralGenerator.getOceaniaWaterlineY() - 1;
        VoxelData chunk = generator.generateChunk(0, Math.floorDiv(worldY, VoxelData.SIZE), 0);

        assertEquals(Layer.OCEANIA, Layer.forY(worldY));
        assertTrue(hasTypeOnWorldY(chunk, worldY, VoxelType.WATER));
    }

    @Test
    void boundaryWallGetsNoLessSolidFartherOutsideAbyssRadius() {
        ProceduralGenerator generator = new ProceduralGenerator(777L);
        int chunkX = (int) ProceduralGenerator.getAbyssBoundaryRadius() / VoxelData.SIZE;
        int chunkY = Math.floorDiv(-2000, VoxelData.SIZE);

        VoxelData nearBoundaryChunk = generator.generateChunk(chunkX, chunkY, 0);
        VoxelData beyondBoundaryChunk = generator.generateChunk(chunkX + 10, chunkY, 0);

        int nearSolidCount = countSolidSamplesOnMidSlice(nearBoundaryChunk);
        int beyondSolidCount = countSolidSamplesOnMidSlice(beyondBoundaryChunk);

        assertTrue(nearSolidCount > 0, "Expected some solidification near the abyss boundary.");
        assertTrue(beyondSolidCount >= nearSolidCount, "Expected generation farther beyond the boundary to be at least as solid.");
    }

    @Test
    void transitionSlicesStayFunctionallyContinuousAcrossLayerBoundaries() {
        ProceduralGenerator generator = new ProceduralGenerator(123L);
        int chunkX = 20;
        int chunkZ = 20;

        for (Layer layer : Layer.values()) {
            if (Layer.getTransition(layer.getYEnd()) == null) {
                continue;
            }

            int shallowerY = layer.getYEnd() + 1;
            int boundaryY = layer.getYEnd();
            int deeperY = layer.getYEnd() - 1;

            VoxelData shallowerChunk = generator.generateChunk(chunkX, Math.floorDiv(shallowerY, VoxelData.SIZE), chunkZ);
            VoxelData boundaryChunk = generator.generateChunk(chunkX, Math.floorDiv(boundaryY, VoxelData.SIZE), chunkZ);
            VoxelData deeperChunk = generator.generateChunk(chunkX, Math.floorDiv(deeperY, VoxelData.SIZE), chunkZ);

            assertSliceContainsOnlyKnownVoxelTypes(shallowerChunk, shallowerY);
            assertSliceContainsOnlyKnownVoxelTypes(boundaryChunk, boundaryY);
            assertSliceContainsOnlyKnownVoxelTypes(deeperChunk, deeperY);
            assertTransitionDoesNotFlipEverySample(shallowerChunk, boundaryChunk, deeperChunk, shallowerY, boundaryY, deeperY);
        }
    }

    // ---------------------------------------------------------------
    // Surface ring & Edge wall tests
    // ---------------------------------------------------------------

    @Test
    void edgeCenterChunkIsMostlyAir() {
        ProceduralGenerator generator = new ProceduralGenerator(555L);
        // A chunk at (0, 0, 0) in THE_EDGE Y range sits dead-centre of the abyss void.
        int edgeWorldY = Layer.THE_EDGE.getYStart() - VoxelData.SIZE / 2;
        VoxelData chunk = generator.generateChunk(0, Math.floorDiv(edgeWorldY, VoxelData.SIZE), 0);

        int totalVoxels = VoxelData.SIZE * VoxelData.SIZE * VoxelData.SIZE;
        int airCount = 0;
        for (int y = 0; y < VoxelData.SIZE; y++) {
            for (int z = 0; z < VoxelData.SIZE; z++) {
                for (int x = 0; x < VoxelData.SIZE; x++) {
                    boolean isAir = !chunk.getType(x, y, z).isSolid();
                    if (isAir) {
                        airCount++;
                    }
                }
            }
        }

        float airRatio = (float) airCount / totalVoxels;
        assertTrue(airRatio > 0.80f,
                "Centre of THE_EDGE should be >80% air (void carve). Got: " + airRatio);
    }

    @Test
    void edgeOuterWallChunkIsMostlySolid() {
        ProceduralGenerator generator = new ProceduralGenerator(555L);
        // A chunk well outside the void radius — the wall should be almost entirely solid.
        float wallRadius = Layer.THE_EDGE.getGenConfig().shaftDiameter() * 0.5f;
        int wallChunkX = (int) ((wallRadius + 200f) / VoxelData.SIZE);
        int edgeWorldY = Layer.THE_EDGE.getYStart() - VoxelData.SIZE / 2;
        VoxelData chunk = generator.generateChunk(wallChunkX, Math.floorDiv(edgeWorldY, VoxelData.SIZE), 0);

        int totalVoxels = VoxelData.SIZE * VoxelData.SIZE * VoxelData.SIZE;
        int solidCount = 0;
        for (int y = 0; y < VoxelData.SIZE; y++) {
            for (int z = 0; z < VoxelData.SIZE; z++) {
                for (int x = 0; x < VoxelData.SIZE; x++) {
                    if (chunk.getType(x, y, z).isSolid()) {
                        solidCount++;
                    }
                }
            }
        }

        float solidRatio = (float) solidCount / totalVoxels;
        assertTrue(solidRatio > 0.80f,
                "Outer wall of THE_EDGE should be >80% solid. Got: " + solidRatio);
    }

    @Test
    void surfaceRingChunkHasSolidVoxelsAboveLayerFloor() {
        ProceduralGenerator generator = new ProceduralGenerator(555L);
        // A chunk at the ring's peak radius should have mountains rising above the layer floor.
        int peakChunkX = (int) (600f / VoxelData.SIZE); // RING_PEAK_RADIUS
        // Y chunk just above the SURFACE layer floor so we see the mountain tops.
        int floorWorldY = Layer.SURFACE.getYEnd();
        int chunkY = Math.floorDiv(floorWorldY + VoxelData.SIZE, VoxelData.SIZE);
        VoxelData chunk = generator.generateChunk(peakChunkX, chunkY, 0);

        boolean hasSolid = false;
        outer:
        for (int y = 0; y < VoxelData.SIZE; y++) {
            for (int z = 0; z < VoxelData.SIZE; z++) {
                for (int x = 0; x < VoxelData.SIZE; x++) {
                    if (chunk.getType(x, y, z).isSolid()) {
                        hasSolid = true;
                        break outer;
                    }
                }
            }
        }

        assertTrue(hasSolid,
                "Surface ring chunk at peak radius should contain solid mountain voxels above the layer floor.");
    }

    private static List<String> collectChunkSignature(ProceduralGenerator generator) {
        VoxelData chunk = generator.generateChunk(6, -44, 4);
        List<String> signature = new ArrayList<>();
        for (int y = 0; y < VoxelData.SIZE; y += 7) {
            for (int z = 0; z < VoxelData.SIZE; z += 9) {
                for (int x = 0; x < VoxelData.SIZE; x += 11) {
                    signature.add(Math.round(chunk.getDensity(x, y, z) * 1000f) + ":" + chunk.getType(x, y, z));
                }
            }
        }
        return signature;
    }

    private static boolean hasTypeOnWorldY(VoxelData chunk, int worldY, VoxelType expectedType) {
        int localY = Math.floorMod(worldY, VoxelData.SIZE);
        for (int z = 0; z < VoxelData.SIZE; z++) {
            for (int x = 0; x < VoxelData.SIZE; x++) {
                if (chunk.getType(x, localY, z) == expectedType) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int countSolidSamplesOnMidSlice(VoxelData chunk) {
        int solidCount = 0;
        int localY = VoxelData.SIZE / 2;
        for (int z = 0; z < VoxelData.SIZE; z += 4) {
            for (int x = 0; x < VoxelData.SIZE; x += 4) {
                if (chunk.getDensity(x, localY, z) > 0f) {
                    solidCount++;
                }
            }
        }
        return solidCount;
    }

    private static void assertSliceContainsOnlyKnownVoxelTypes(VoxelData chunk, int worldY) {
        int localY = Math.floorMod(worldY, VoxelData.SIZE);
        for (int z = 0; z < VoxelData.SIZE; z += 4) {
            for (int x = 0; x < VoxelData.SIZE; x += 4) {
                VoxelType type = chunk.getType(x, localY, z);
                boolean knownType = false;
                for (VoxelType candidate : VoxelType.values()) {
                    if (candidate == type) {
                        knownType = true;
                        break;
                    }
                }
                assertTrue(knownType, "Unexpected voxel type at y=" + worldY);
            }
        }
    }

    private static void assertTransitionDoesNotFlipEverySample(VoxelData shallowerChunk, VoxelData boundaryChunk, VoxelData deeperChunk, int shallowerY, int boundaryY, int deeperY) {
        int shallowerLocalY = Math.floorMod(shallowerY, VoxelData.SIZE);
        int boundaryLocalY = Math.floorMod(boundaryY, VoxelData.SIZE);
        int deeperLocalY = Math.floorMod(deeperY, VoxelData.SIZE);

        int abruptFlips = 0;
        int samples = 0;
        for (int z = 0; z < VoxelData.SIZE; z += 4) {
            for (int x = 0; x < VoxelData.SIZE; x += 4) {
                boolean shallowerSolid = shallowerChunk.getDensity(x, shallowerLocalY, z) > 0f;
                boolean boundarySolid = boundaryChunk.getDensity(x, boundaryLocalY, z) > 0f;
                boolean deeperSolid = deeperChunk.getDensity(x, deeperLocalY, z) > 0f;
                if (shallowerSolid == deeperSolid && boundarySolid != shallowerSolid) {
                    abruptFlips++;
                }
                samples++;
            }
        }
        assertTrue(abruptFlips < samples, "Transition should not devolve into complete one-voxel flip-flopping.");
    }
}