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
    private final VoxelTypeResolver typeResolver;
    private final GenConfigResolver resolver;

    /** Per-layer feature processors, applied as a post-processing pass after voxel fill. */
    private final Map<Layer, LayerFeatureProcessor> featureProcessors = new EnumMap<>(Layer.class);

    /**
     * Reusable scratch cache for XZ-column noise values. Safe to reuse because
     * {@link ProceduralGenerator} instances are stored in a {@link ThreadLocal}
     * inside {@link com.example.abyss.world.ChunkManager} — never shared across threads.
     */
    private final ColumnNoiseCache columnNoiseCache = new ColumnNoiseCache();

    /**
     * Reusable scratch cache for trilinear-interpolated terrain and cave noise.
     * Populated once per chunk at 9×9×9 grid corners; all 32,768 voxels read from it
     * via lerp instead of calling GetNoise directly.
     */
    private final DensityLerpCache densityLerpCache = new DensityLerpCache();


    public ProceduralGenerator(long seed) {
        this(seed, GenConfigResolver.DEFAULT);
    }

    public ProceduralGenerator(long seed, GenConfigResolver resolver) {
        this.resolver = resolver;
        densityFunction = new DensityFunction(seed);
        typeResolver    = new VoxelTypeResolver(seed);

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
     * Generate voxel data for a single chunk using the baked {@link Layer} configs.
     *
     * @param chunkX chunk coordinate (world x = chunkX * 32)
     * @param chunkY chunk coordinate (world y = chunkY * 32)
     * @param chunkZ chunk coordinate (world z = chunkZ * 32)
     * @return fully populated {@link VoxelData}
     */
    public VoxelData generateChunk(int chunkX, int chunkY, int chunkZ) {
        return generateChunk(chunkX, chunkY, chunkZ, resolver);
    }

    /**
     * @param chunkX   chunk coordinate (world x = chunkX * 32)
     * @param chunkY   chunk coordinate (world y = chunkY * 32)
     * @param chunkZ   chunk coordinate (world z = chunkZ * 32)
     * @param resolver supplies the {@link LayerGenConfig} for each Y-slice
     * @return fully populated {@link VoxelData}
     */
    public VoxelData generateChunk(int chunkX, int chunkY, int chunkZ, GenConfigResolver resolver) {
        VoxelData data = new VoxelData();
        int worldBaseX = chunkX * SIZE;
        int worldBaseY = chunkY * SIZE;
        int worldBaseZ = chunkZ * SIZE;

        // Populate the XZ column cache — replaces shaft/boundary/sqrt per voxel with a
        // single 1024-entry sweep. Uses centre-Y config (shaftDiameter is layer-constant).
        int centreY = worldBaseY + SIZE / 2;
        LayerGenConfig centreConfig = resolver.resolve(centreY, Layer.forY(centreY));
        densityFunction.populateColumnCache(worldBaseX, worldBaseZ, centreConfig, columnNoiseCache);
        typeResolver.populateTypeJitter(worldBaseX, worldBaseZ, columnNoiseCache);

        // Populate the lerp cache — samples terrain+cave noise on a 9×9×9 coarse grid.
        // The inner voxel loop reads from this via trilinear interpolation instead of
        // calling GetNoise, cutting terrain+cave evaluations from 32,768 to 729 per chunk.
        densityFunction.populateLerpCache(worldBaseX, worldBaseY, worldBaseZ,
                resolver, densityLerpCache);

        // Step 1–3: fill each voxel with density and type
        for (int ly = 0; ly < SIZE; ly++) {
            int wy = worldBaseY + ly;

            // Step 1: resolve layer gen config once per Y-slice (config only depends on Y)
            Layer primaryLayer = Layer.forY(wy);
            LayerGenConfig cfg = resolver.resolve(wy, primaryLayer);

            // Step 3: evaluate density and assign voxel type using both caches
            for (int lz = 0; lz < SIZE; lz++) {
                int wz = worldBaseZ + lz;
                for (int lx = 0; lx < SIZE; lx++) {
                    int wx = worldBaseX + lx;

                    float density = densityFunction.evaluateCached(
                            columnNoiseCache, densityLerpCache, lx, ly, lz);
                    data.setDensity(lx, ly, lz, density);

                    VoxelType type = typeResolver.resolveCached(wx, wy, wz, density, cfg,
                            primaryLayer, columnNoiseCache, lx, lz);
                    data.setType(lx, ly, lz, type);
                }
            }
        }

        // Step 4: apply layer-specific features (post-processing pass)
        Layer chunkCenterLayer = Layer.forY(worldBaseY + SIZE / 2);
        LayerFeatureProcessor processor = featureProcessors.get(chunkCenterLayer);
        if (processor != null) {
            processor.process(data, worldBaseX, worldBaseY, worldBaseZ, resolver);
        }

        return data;
    }

    // ---------------------------------------------------------------
    // Package-private accessors used by tests
    // ---------------------------------------------------------------

    static int getOceaniaWaterlineY() {
        return VoxelTypeResolver.OCEANIA_WATERLINE_Y;
    }

    static float getAbyssBoundaryRadius() {
        return DensityFunction.ABYSS_BOUNDARY_RADIUS;
    }
}