package com.example.abyss.world.gen;

import com.example.abyss.world.gen.features.DeepExtremesFeatureProcessor;
import com.example.abyss.world.gen.features.EdgeFeatureProcessor;
import com.example.abyss.world.gen.features.ForestFeatureProcessor;
import com.example.abyss.world.gen.features.HangingGardensFeatureProcessor;
import com.example.abyss.world.gen.features.MyceliumFeatureProcessor;
import com.example.abyss.world.gen.features.OceaniaFeatureProcessor;
import com.example.abyss.world.gen.features.SkyWallsFeatureProcessor;
import com.example.abyss.world.gen.features.SurfaceFeatureProcessor;
import com.example.abyss.world.voxel.VoxelData;
import com.example.abyss.world.voxel.VoxelType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Core procedural-generation engine for the Abyss world.
 * <p>
 * For every 32³ chunk requested, this class:
 * <ol>
 *   <li>Identifies the layer(s) that cover the chunk's Y-range</li>
 *   <li>Delegates per-voxel density evaluation and type assignment to
 *       {@link DensityFunction}</li>
 *   <li>Runs the per-layer {@link LayerFeatureProcessor} as a post-processing pass</li>
 * </ol>
 * The main shaft is integrated directly into the density function — there is
 * no separate carving pass.
 * </p>
 */
public class ProceduralGenerator {

    private static final int SIZE = VoxelData.SIZE;

    private final DensityFunction densityFunction;

    /** Per-layer feature processors, applied as a post-processing pass after voxel fill. */
    private final Map<Layer, LayerFeatureProcessor> featureProcessors = new EnumMap<>(Layer.class);

    public ProceduralGenerator(long seed) {
        densityFunction = new DensityFunction(seed);

        featureProcessors.put(Layer.SURFACE,         new SurfaceFeatureProcessor(seed));
        featureProcessors.put(Layer.THE_EDGE,         new EdgeFeatureProcessor(seed));
        featureProcessors.put(Layer.FOREST_DEPTHS,    new ForestFeatureProcessor(seed));
        featureProcessors.put(Layer.HANGING_GARDENS,  new HangingGardensFeatureProcessor(seed));
        featureProcessors.put(Layer.SKY_WALLS,        new SkyWallsFeatureProcessor(seed));
        featureProcessors.put(Layer.MYCELIUM,         new MyceliumFeatureProcessor(seed));
        featureProcessors.put(Layer.OCEANIA,          new OceaniaFeatureProcessor(seed));
        featureProcessors.put(Layer.DEEP_EXTREMES,    new DeepExtremesFeatureProcessor(seed));
    }

    /**
     * Generate voxel data for a single chunk.
     *
     * @param chunkX chunk coordinate (world x = chunkX * 32)
     * @param chunkY chunk coordinate (world y = chunkY * 32)
     * @param chunkZ chunk coordinate (world z = chunkZ * 32)
     * @return fully populated {@link VoxelData}
     */
    public VoxelData generateChunk(int chunkX, int chunkY, int chunkZ) {
        VoxelData data = new VoxelData();
        int worldBaseX = chunkX * SIZE;
        int worldBaseY = chunkY * SIZE;
        int worldBaseZ = chunkZ * SIZE;

        // Track the last frequency set on terrainNoise to avoid redundant calls
        float lastFrequency = -1f;

        // Step 1–3: fill each voxel with density and type
        for (int ly = 0; ly < SIZE; ly++) {
            int wy = worldBaseY + ly;

            // Step 1: resolve layer gen config once per Y-slice (config only depends on Y)
            Layer primaryLayer = Layer.forY(wy);
            LayerGenConfig cfg = resolveGenConfig(wy, primaryLayer);

            // Step 2: update terrain noise frequency only when it changes
            float freq = cfg.noiseFrequency();
            if (freq != lastFrequency) {
                densityFunction.setTerrainFrequency(freq);
                lastFrequency = freq;
            }

            // Step 3: evaluate density and assign voxel type
            for (int lz = 0; lz < SIZE; lz++) {
                int wz = worldBaseZ + lz;
                for (int lx = 0; lx < SIZE; lx++) {
                    int wx = worldBaseX + lx;

                    float density = densityFunction.evaluate(wx, wy, wz, cfg);
                    data.setDensity(lx, ly, lz, density);

                    VoxelType type = densityFunction.assignType(wx, wy, wz, density, cfg, primaryLayer);
                    data.setType(lx, ly, lz, type);
                }
            }
        }

        // Step 4: apply layer-specific features (post-processing pass)
        Layer chunkCenterLayer = Layer.forY(worldBaseY + SIZE / 2);
        LayerFeatureProcessor processor = featureProcessors.get(chunkCenterLayer);
        if (processor != null) {
            processor.process(data, worldBaseX, worldBaseY, worldBaseZ);
        }

        return data;
    }

    // ---------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------

    /**
     * Returns the effective {@link LayerGenConfig} for a given world-Y,
     * blending between two layers if inside a transition zone.
     *
     * @param worldY       the world Y coordinate
     * @param primaryLayer the layer already resolved via {@link Layer#forY(int)}
     */
    private static LayerGenConfig resolveGenConfig(int worldY, Layer primaryLayer) {
        Layer.TransitionInfo ti = Layer.getTransition(worldY);
        if (ti != null) {
            return ti.blendedGenConfig();
        }
        return primaryLayer.getGenConfig();
    }

    // ---------------------------------------------------------------
    // Package-private accessors used by tests
    // ---------------------------------------------------------------

    static int getOceaniaWaterlineY() {
        return DensityFunction.OCEANIA_WATERLINE_Y;
    }

    static float getAbyssBoundaryRadius() {
        return DensityFunction.ABYSS_BOUNDARY_RADIUS;
    }
}