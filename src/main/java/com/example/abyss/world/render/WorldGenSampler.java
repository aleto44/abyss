package com.example.abyss.world.render;

import com.badlogic.gdx.graphics.Color;
import com.example.abyss.world.AbyssWorld;
import com.example.abyss.world.gen.Layer;
import com.example.abyss.world.gen.LayerConfig;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Small render-side adapter that samples worldgen data from {@link AbyssWorld}
 * without leaking rendering concerns into generation classes.
 */
public class WorldGenSampler {

    private final AbyssWorld world;

    public WorldGenSampler(AbyssWorld world) {
        this.world = world;
    }

    public WorldGenSample sample(int worldX, int worldY, int worldZ) {
        Layer layer = Layer.forY(worldY);
        Layer.TransitionInfo transition = Layer.getTransition(worldY);
        LayerConfig effectiveConfig = layer.getConfig();
        boolean inTransition = false;
        float transitionBlendFactor = -1f;
        if (transition != null) {
            effectiveConfig = transition.blendedConfig();
            inTransition = true;
            transitionBlendFactor = transition.blendFactor();
        }

        float density = world.getDensityAt(worldX, worldY, worldZ);
        VoxelType voxelType = world.getVoxelAt(worldX, worldY, worldZ);
        return new WorldGenSample(
                worldX,
                worldY,
                worldZ,
                density,
                voxelType,
                layer,
                effectiveConfig,
                inTransition,
                transitionBlendFactor
        );
    }

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