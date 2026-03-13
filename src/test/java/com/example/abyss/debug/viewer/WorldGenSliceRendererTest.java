package com.example.abyss.debug.viewer;

import com.example.abyss.world.AbyssWorld;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldGenSliceRendererTest {

    @Test
    void viewerControlsUpdateLegendStateDeterministically() {
        AbyssWorld world = new AbyssWorld(42L);
        world.init();
        try {
            WorldGenSliceRenderer renderer = new WorldGenSliceRenderer(
                    new ChunkStreamingWorldGenSampler(world),
                    new WorldGenColorResolver()
            );

            assertTrue(renderer.isCacheDirty());
            assertEquals(16, renderer.getWorldStep());
            assertFalse(renderer.isShowChunkGrid());
            assertTrue(renderer.buildLegend().contains("Mode=blocky"));

            renderer.moveCenter(64, -96);
            renderer.moveSliceZ(32);
            renderer.zoom(1);
            renderer.toggleChunkGrid();

            assertEquals(32, renderer.getSliceWorldZ());
            assertEquals(-96, renderer.getCenterWorldY());
            assertEquals(8, renderer.getWorldStep());
            assertTrue(renderer.isShowChunkGrid());
            assertTrue(renderer.isCacheDirty());
            assertTrue(renderer.buildLegend().contains("Z=32"));
            assertTrue(renderer.buildLegend().contains("Step=8"));
        } finally {
            world.dispose();
        }
    }
}
