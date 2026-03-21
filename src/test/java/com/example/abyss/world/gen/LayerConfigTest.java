package com.example.abyss.world.gen;

import com.example.abyss.world.voxel.VoxelType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayerGenConfigTest {

    private static final LayerGenConfig SHALLOW = new LayerGenConfig(
            0.10f,
            0.02f,
            0.60f,
            120f,
            new VoxelType[]{VoxelType.GRASS, VoxelType.DIRT}
    );

    private static final LayerGenConfig DEEP = new LayerGenConfig(
            0.30f,
            0.06f,
            0.90f,
            220f,
            new VoxelType[]{VoxelType.OBSIDIAN, VoxelType.CRYSTAL}
    );

    @Test
    void lerpPreservesEndpointBehavior() {
        LayerGenConfig atZero = LayerGenConfig.lerp(SHALLOW, DEEP, 0.0f);
        LayerGenConfig atOne  = LayerGenConfig.lerp(SHALLOW, DEEP, 1.0f);

        assertEquivalentNumericFields(SHALLOW, atZero);
        assertSame(SHALLOW.dominantVoxelTypes(), atZero.dominantVoxelTypes());

        assertEquivalentNumericFields(DEEP, atOne);
        assertSame(DEEP.dominantVoxelTypes(), atOne.dominantVoxelTypes());
    }

    @Test
    void lerpMovesNumericFieldsMonotonicallyBetweenEndpoints() {
        LayerGenConfig early  = LayerGenConfig.lerp(SHALLOW, DEEP, 0.25f);
        LayerGenConfig middle = LayerGenConfig.lerp(SHALLOW, DEEP, 0.5f);
        LayerGenConfig late   = LayerGenConfig.lerp(SHALLOW, DEEP, 0.75f);

        assertMonotonicBetweenEndpoints(SHALLOW.baseDensityThreshold(), early.baseDensityThreshold(), middle.baseDensityThreshold(), late.baseDensityThreshold(), DEEP.baseDensityThreshold());
        assertMonotonicBetweenEndpoints(SHALLOW.noiseFrequency(),        early.noiseFrequency(),        middle.noiseFrequency(),        late.noiseFrequency(),        DEEP.noiseFrequency());
        assertMonotonicBetweenEndpoints(SHALLOW.noiseAmplitude(),       early.noiseAmplitude(),       middle.noiseAmplitude(),       late.noiseAmplitude(),        DEEP.noiseAmplitude());
        assertMonotonicBetweenEndpoints(SHALLOW.shaftDiameter(),         early.shaftDiameter(),         middle.shaftDiameter(),         late.shaftDiameter(),         DEEP.shaftDiameter());
    }

    @Test
    void lerpKeepsDiscreteFieldsOnOneSideThenSwitchesToOtherSide() {
        LayerGenConfig beforeHalf = LayerGenConfig.lerp(SHALLOW, DEEP, 0.49f);
        LayerGenConfig afterHalf  = LayerGenConfig.lerp(SHALLOW, DEEP, 0.51f);

        assertSame(SHALLOW.dominantVoxelTypes(), beforeHalf.dominantVoxelTypes());

        assertSame(DEEP.dominantVoxelTypes(), afterHalf.dominantVoxelTypes());
    }

    private static void assertEquivalentNumericFields(LayerGenConfig expected, LayerGenConfig actual) {
        assertEquals(expected.baseDensityThreshold(), actual.baseDensityThreshold(), 0.0001f);
        assertEquals(expected.noiseFrequency(),       actual.noiseFrequency(),       0.0001f);
        assertEquals(expected.noiseAmplitude(),       actual.noiseAmplitude(),       0.0001f);
        assertEquals(expected.shaftDiameter(),        actual.shaftDiameter(),        0.0001f);
    }

    private static void assertMonotonicBetweenEndpoints(float start, float early, float middle, float late, float end) {
        float min = Math.min(start, end);
        float max = Math.max(start, end);
        assertTrue(early  >= min && early  <= max);
        assertTrue(middle >= min && middle <= max);
        assertTrue(late   >= min && late   <= max);

        if (start <= end) {
            assertTrue(early <= middle && middle <= late);
        } else {
            assertTrue(early >= middle && middle >= late);
        }
    }
}