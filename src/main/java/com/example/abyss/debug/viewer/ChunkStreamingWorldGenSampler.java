package com.example.abyss.debug.viewer;

import com.badlogic.gdx.graphics.Color;
import com.example.abyss.world.AbyssWorld;
import com.example.abyss.world.gen.Layer;
import com.example.abyss.world.gen.LayerConfig;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Debug sampler backed by the live {@link AbyssWorld} chunk-streaming pipeline.
 */
public class ChunkStreamingWorldGenSampler implements WorldGenDataSampler {

    private final AbyssWorld world;

    public ChunkStreamingWorldGenSampler(AbyssWorld world) {
        this.world = world;
    }

    @Override
    public void sampleColor(int worldX,
                            int worldY,
                            int worldZ,
                            WorldGenColorResolver colorResolver,
                            Color outColor) {
        Layer layer = Layer.forY(worldY);
        Layer.TransitionInfo transition = Layer.getTransition(worldY);
        LayerConfig effectiveConfig = layer.getConfig();
        boolean inTransition = false;
        if (transition != null) {
            effectiveConfig = transition.blendedConfig();
            inTransition = true;
        }

        float density = world.getDensityAt(worldX, worldY, worldZ);
        VoxelType voxelType = world.getVoxelAt(worldX, worldY, worldZ);
        colorResolver.resolveColor(worldX, worldY, density, voxelType, effectiveConfig, inTransition, outColor);
    }
}
