package com.example.abyss.debug.viewer;

import com.badlogic.gdx.graphics.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ProceduralGeneratorWorldGenSamplerTest {

    @Test
    void directGeneratorSamplerIsDeterministicForSameSeedAndCoordinate() {
        ProceduralGeneratorWorldGenSampler first = new ProceduralGeneratorWorldGenSampler(42L);
        ProceduralGeneratorWorldGenSampler second = new ProceduralGeneratorWorldGenSampler(42L);
        WorldGenColorResolver resolver = new WorldGenColorResolver();
        Color firstColor = new Color();
        Color secondColor = new Color();

        first.sampleColor(64, -2048, 32, resolver, firstColor);
        second.sampleColor(64, -2048, 32, resolver, secondColor);

        assertEquals(firstColor.r, secondColor.r, 0.0001f);
        assertEquals(firstColor.g, secondColor.g, 0.0001f);
        assertEquals(firstColor.b, secondColor.b, 0.0001f);
        assertEquals(firstColor.a, secondColor.a, 0.0001f);
    }

    @Test
    void directGeneratorSamplerProducesDifferentSamplesAcrossDistinctDepths() {
        ProceduralGeneratorWorldGenSampler sampler = new ProceduralGeneratorWorldGenSampler(42L);
        WorldGenColorResolver resolver = new WorldGenColorResolver();
        Color shallow = new Color();
        Color deep = new Color();

        sampler.sampleColor(64, -100, 32, resolver, shallow);
        sampler.sampleColor(64, -4200, 32, resolver, deep);

        assertNotEquals(toPackedRgba(shallow), toPackedRgba(deep));
    }

    private static int toPackedRgba(Color color) {
        int r = Math.round(color.r * 255f);
        int g = Math.round(color.g * 255f);
        int b = Math.round(color.b * 255f);
        int a = Math.round(color.a * 255f);
        return (r << 24) | (g << 16) | (b << 8) | a;
    }
}
