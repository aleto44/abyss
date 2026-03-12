package com.example.abyss.world.gen;

import com.badlogic.gdx.graphics.Color;
import com.example.abyss.world.voxel.VoxelType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayerConfigTest {

    private static final LayerConfig SHALLOW = new LayerConfig(
            0.10f,
            0.02f,
            0.60f,
            120f,
            new VoxelType[]{VoxelType.GRASS, VoxelType.DIRT},
            null,
            240f,
            new Color[]{Color.GREEN, Color.BROWN}
    );

    private static final LayerConfig DEEP = new LayerConfig(
            0.30f,
            0.06f,
            0.90f,
            220f,
            new VoxelType[]{VoxelType.OBSIDIAN, VoxelType.CRYSTAL},
            VoxelType.TOXIC_GAS,
            60f,
            new Color[]{Color.PURPLE, Color.NAVY}
    );

    @Test
    void lerpPreservesEndpointBehavior() {
        LayerConfig atZero = LayerConfig.lerp(SHALLOW, DEEP, 0.0f);
        LayerConfig atOne = LayerConfig.lerp(SHALLOW, DEEP, 1.0f);

        assertEquivalentNumericFields(SHALLOW, atZero);
        assertSame(SHALLOW.dominantVoxelTypes(), atZero.dominantVoxelTypes());
        assertSame(SHALLOW.hazardType(), atZero.hazardType());
        assertSame(SHALLOW.colorPalette(), atZero.colorPalette());

        assertEquivalentNumericFields(DEEP, atOne);
        assertSame(DEEP.dominantVoxelTypes(), atOne.dominantVoxelTypes());
        assertSame(DEEP.hazardType(), atOne.hazardType());
        assertSame(DEEP.colorPalette(), atOne.colorPalette());
    }

    @Test
    void lerpMovesNumericFieldsMonotonicallyBetweenEndpoints() {
        LayerConfig early = LayerConfig.lerp(SHALLOW, DEEP, 0.25f);
        LayerConfig middle = LayerConfig.lerp(SHALLOW, DEEP, 0.5f);
        LayerConfig late = LayerConfig.lerp(SHALLOW, DEEP, 0.75f);

        assertMonotonicBetweenEndpoints(SHALLOW.baseDensityThreshold(), early.baseDensityThreshold(), middle.baseDensityThreshold(), late.baseDensityThreshold(), DEEP.baseDensityThreshold());
        assertMonotonicBetweenEndpoints(SHALLOW.noiseFrequency(), early.noiseFrequency(), middle.noiseFrequency(), late.noiseFrequency(), DEEP.noiseFrequency());
        assertMonotonicBetweenEndpoints(SHALLOW.noiseAmplitude(), early.noiseAmplitude(), middle.noiseAmplitude(), late.noiseAmplitude(), DEEP.noiseAmplitude());
        assertMonotonicBetweenEndpoints(SHALLOW.shaftDiameter(), early.shaftDiameter(), middle.shaftDiameter(), late.shaftDiameter(), DEEP.shaftDiameter());
        assertMonotonicBetweenEndpoints(SHALLOW.visibility(), early.visibility(), middle.visibility(), late.visibility(), DEEP.visibility());
    }

    @Test
    void lerpKeepsDiscreteFieldsOnOneSideThenSwitchesToOtherSide() {
        LayerConfig beforeHalf = LayerConfig.lerp(SHALLOW, DEEP, 0.49f);
        LayerConfig afterHalf = LayerConfig.lerp(SHALLOW, DEEP, 0.51f);

        assertSame(SHALLOW.dominantVoxelTypes(), beforeHalf.dominantVoxelTypes());
        assertSame(SHALLOW.hazardType(), beforeHalf.hazardType());
        assertSame(SHALLOW.colorPalette(), beforeHalf.colorPalette());

        assertSame(DEEP.dominantVoxelTypes(), afterHalf.dominantVoxelTypes());
        assertSame(DEEP.hazardType(), afterHalf.hazardType());
        assertSame(DEEP.colorPalette(), afterHalf.colorPalette());
    }

    private static void assertEquivalentNumericFields(LayerConfig expected, LayerConfig actual) {
        assertEquals(expected.baseDensityThreshold(), actual.baseDensityThreshold(), 0.0001f);
        assertEquals(expected.noiseFrequency(), actual.noiseFrequency(), 0.0001f);
        assertEquals(expected.noiseAmplitude(), actual.noiseAmplitude(), 0.0001f);
        assertEquals(expected.shaftDiameter(), actual.shaftDiameter(), 0.0001f);
        assertEquals(expected.visibility(), actual.visibility(), 0.0001f);
    }

    private static void assertMonotonicBetweenEndpoints(float start, float early, float middle, float late, float end) {
        float min = Math.min(start, end);
        float max = Math.max(start, end);
        assertTrue(early >= min && early <= max);
        assertTrue(middle >= min && middle <= max);
        assertTrue(late >= min && late <= max);

        if (start <= end) {
            assertTrue(early <= middle && middle <= late);
        } else {
            assertTrue(early >= middle && middle >= late);
        }
    }
}