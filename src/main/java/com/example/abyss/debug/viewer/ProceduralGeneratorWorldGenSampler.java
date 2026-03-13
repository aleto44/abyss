package com.example.abyss.debug.viewer;

import com.badlogic.gdx.graphics.Color;
import com.example.abyss.world.gen.Layer;
import com.example.abyss.world.gen.LayerConfig;
import com.example.abyss.world.gen.ProceduralGenerator;
import com.example.abyss.world.voxel.VoxelData;
import com.example.abyss.world.voxel.VoxelType;

import java.util.HashMap;
import java.util.Map;

/**
 * Debug sampler that queries {@link ProceduralGenerator} chunk data directly,
 * bypassing {@link com.example.abyss.world.AbyssWorld} streaming and meshing.
 */
public class ProceduralGeneratorWorldGenSampler implements WorldGenDataSampler {

    private final ProceduralGenerator generator;
    private final Map<ChunkKey, VoxelData> chunkCache = new HashMap<>();

    public ProceduralGeneratorWorldGenSampler(long seed) {
        this.generator = new ProceduralGenerator(seed);
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

        int chunkX = Math.floorDiv(worldX, VoxelData.SIZE);
        int chunkY = Math.floorDiv(worldY, VoxelData.SIZE);
        int chunkZ = Math.floorDiv(worldZ, VoxelData.SIZE);
        ChunkKey chunkKey = new ChunkKey(chunkX, chunkY, chunkZ);
        VoxelData data = chunkCache.computeIfAbsent(chunkKey, key -> generator.generateChunk(key.x(), key.y(), key.z()));

        int localX = Math.floorMod(worldX, VoxelData.SIZE);
        int localY = Math.floorMod(worldY, VoxelData.SIZE);
        int localZ = Math.floorMod(worldZ, VoxelData.SIZE);
        float density = data.getDensity(localX, localY, localZ);
        VoxelType voxelType = data.getType(localX, localY, localZ);
        colorResolver.resolveColor(worldX, worldY, density, voxelType, effectiveConfig, inTransition, outColor);
    }

    private record ChunkKey(int x, int y, int z) {
    }
}
