package com.example.abyss.debug.viewer;

import com.badlogic.gdx.graphics.Color;
import com.example.abyss.world.AbyssWorld;
import com.example.abyss.world.gen.Layer;
import com.example.abyss.world.gen.LayerConfig;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Debug sampler backed by the live {@link AbyssWorld} chunk-streaming pipeline.
 * <p>
 * Never blocks the main thread — if a chunk is not yet loaded it queues async
 * generation, writes a transparent fallback color, and returns {@code false} so
 * the caller knows the result is incomplete and should be re-sampled later.
 * </p>
 */
public class ChunkStreamingWorldGenSampler implements WorldGenDataSampler {

    private final AbyssWorld world;

    public ChunkStreamingWorldGenSampler(AbyssWorld world) {
        this.world = world;
    }

    /**
     * Samples a color for the given world position without blocking.
     *
     * @return {@code true} if the chunk was loaded and a real color was written;
     *         {@code false} if the chunk was missing (async request queued, fallback written).
     */
    @Override
    public boolean sampleColor(int worldX,
                                int worldY,
                                int worldZ,
                                WorldGenColorResolver colorResolver,
                                Color outColor) {
        float density = world.getVoxelAccess().tryGetDensityAt(worldX, worldY, worldZ);
        if (Float.isNaN(density)) {
            // Chunk not yet loaded — write a dim placeholder and signal a miss.
            outColor.set(0.04f, 0.04f, 0.06f, 1f);
            return false;
        }

        VoxelType voxelType = world.getVoxelAccess().tryGetVoxelAt(worldX, worldY, worldZ);
        if (voxelType == null) {
            outColor.set(0.04f, 0.04f, 0.06f, 1f);
            return false;
        }

        Layer layer = Layer.forY(worldY);
        Layer.TransitionInfo transition = Layer.getTransition(worldY);
        LayerConfig effectiveConfig = layer.getConfig();
        boolean inTransition = false;
        if (transition != null) {
            effectiveConfig = transition.blendedConfig();
            inTransition = true;
        }

        colorResolver.resolveColor(worldX, worldY, density, voxelType, effectiveConfig, inTransition, outColor);
        return true;
    }
}