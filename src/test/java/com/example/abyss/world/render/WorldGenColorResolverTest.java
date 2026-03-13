package com.example.abyss.world.render;

import com.badlogic.gdx.graphics.Color;
import com.example.abyss.world.gen.Layer;
import com.example.abyss.world.gen.LayerConfig;
import com.example.abyss.world.voxel.VoxelType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenColorResolverTest {

    @Test
    void airSamplesUseLayerPaletteAndStayOpaque() {
        WorldGenColorResolver resolver = new WorldGenColorResolver();
        WorldGenSample sample = sampleOf(VoxelType.AIR, Layer.THE_EDGE, -0.75f, false, -1f, 0, -200);

        Color resolved = resolver.resolveColor(sample);

        assertEquals(1f, resolved.a, 0.0001f);
        assertTrue(resolved.g > resolved.b, "Expected the Edge palette to bias airy samples toward green terrain tones.");
    }

    @Test
    void solidSamplesStayCloseToTheirVoxelBaseColor() {
        WorldGenColorResolver resolver = new WorldGenColorResolver();
        WorldGenSample sample = sampleOf(VoxelType.STONE, Layer.SKY_WALLS, 0.8f, false, -1f, 8, -2800);

        Color resolved = resolver.resolveColor(sample);

        assertTrue(Math.abs(resolved.r - VoxelType.STONE.getBaseColor().r) < 0.3f);
        assertTrue(Math.abs(resolved.g - VoxelType.STONE.getBaseColor().g) < 0.3f);
        assertTrue(Math.abs(resolved.b - VoxelType.STONE.getBaseColor().b) < 0.3f);
    }

    @Test
    void transitionOverlayProducesDeterministicAlternatingStripeTint() {
        WorldGenColorResolver resolver = new WorldGenColorResolver();
        WorldGenSample stripeA = sampleOf(VoxelType.AIR, Layer.MYCELIUM, -0.25f, true, 0.5f, 0, -3500);
        WorldGenSample stripeB = sampleOf(VoxelType.AIR, Layer.MYCELIUM, -0.25f, true, 0.5f, 6, -3500);

        Color resolvedA = resolver.resolveColor(stripeA);
        Color resolvedB = resolver.resolveColor(stripeB);

        assertNotEquals(toPackedRgba(resolvedA), toPackedRgba(resolvedB));
    }

    private static WorldGenSample sampleOf(VoxelType voxelType,
                                           Layer layer,
                                           float density,
                                           boolean inTransition,
                                           float transitionBlend,
                                           int worldX,
                                           int worldY) {
        LayerConfig config = inTransition
                ? LayerConfig.lerp(layer.getConfig(), Layer.values()[Math.min(layer.ordinal() + 1, Layer.values().length - 1)].getConfig(), 0.5f)
                : layer.getConfig();
        return new WorldGenSample(worldX, worldY, 0, density, voxelType, layer, config, inTransition, transitionBlend);
    }

    private static int toPackedRgba(Color color) {
        int r = Math.round(color.r * 255f);
        int g = Math.round(color.g * 255f);
        int b = Math.round(color.b * 255f);
        int a = Math.round(color.a * 255f);
        return (r << 24) | (g << 16) | (b << 8) | a;
    }
}
