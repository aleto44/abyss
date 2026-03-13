package com.example.abyss.world.gen;

import com.example.abyss.lib.FastNoiseLite;
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
 *   <li>Evaluates a combined density function per voxel (terrain noise − shaft term)</li>
 *   <li>Assigns discrete {@link VoxelType}s based on the density result and layer config</li>
 * </ol>
 * The main shaft is integrated directly into the density function—there is
 * no separate carving pass.
 * </p>
 */
public class ProceduralGenerator {

    private static final int SIZE = VoxelData.SIZE; // 32

    // --- noise instances (all seeded from the world seed) ---
    private final FastNoiseLite terrainNoise;
    private final FastNoiseLite shaftNoise;      // perturbs shaft radius
    private final FastNoiseLite typeNoise;        // secondary octave for voxel-type selection
    private final FastNoiseLite hazardNoise;      // pockets for hazardous voxels
    private final FastNoiseLite caveNoise;        // dedicated cave carving
    private final FastNoiseLite boundaryNoise;    // perturbs the horizontal boundary wall

    /** Per-layer feature processors for generation step 4. */
    private final Map<Layer, LayerFeatureProcessor> featureProcessors = new EnumMap<>(Layer.class);

    /** Margin (in voxels) over which the shaft edge fades via smoothstep. */
    private static final float SHAFT_MARGIN = 30f;

    /** Waterline Y for the Oceania layer (everything below this is water if air). */
    private static final int OCEANIA_WATERLINE_Y = -4600;

    /**
     * Nominal radius of the abyss in voxels (soft horizontal boundary).
     * Terrain density increases sharply beyond this distance from center,
     * forming impassable walls.  ~5 km radius at the top.
     */
    private static final float ABYSS_BOUNDARY_RADIUS = 5000f;

    /** Smoothstep margin for the boundary wall (in voxels). */
    private static final float BOUNDARY_MARGIN = 200f;

    /** Cave noise threshold – voxels with cave noise above this are hollowed out. */
    private static final float CAVE_THRESHOLD = 0.45f;

    public ProceduralGenerator(long seed) {
        terrainNoise = new FastNoiseLite((int) seed);
        terrainNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        terrainNoise.SetFractalType(FastNoiseLite.FractalType.FBm);
        terrainNoise.SetFractalOctaves(4);

        shaftNoise = new FastNoiseLite((int) (seed ^ 0xBEEFCAFEL));
        shaftNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        shaftNoise.SetFrequency(0.005f);

        typeNoise = new FastNoiseLite((int) (seed ^ 0xDEADFACEL));
        typeNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        typeNoise.SetFrequency(0.04f);

        hazardNoise = new FastNoiseLite((int) (seed ^ 0xCAFEBABEL));
        hazardNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        hazardNoise.SetFrequency(0.03f);

        caveNoise = new FastNoiseLite((int) (seed ^ 0xF00D_BA11L));
        caveNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        caveNoise.SetFractalType(FastNoiseLite.FractalType.Ridged);
        caveNoise.SetFractalOctaves(3);
        caveNoise.SetFrequency(0.02f);

        boundaryNoise = new FastNoiseLite((int) (seed ^ 0xABCD_1234L));
        boundaryNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        boundaryNoise.SetFrequency(0.003f);

        // Register layer-specific feature processors (gen step 4)
        featureProcessors.put(Layer.SURFACE, new SurfaceFeatureProcessor(seed));
        featureProcessors.put(Layer.THE_EDGE, new EdgeFeatureProcessor(seed));
        featureProcessors.put(Layer.FOREST_DEPTHS, new ForestFeatureProcessor(seed));
        featureProcessors.put(Layer.HANGING_GARDENS, new HangingGardensFeatureProcessor(seed));
        featureProcessors.put(Layer.SKY_WALLS, new SkyWallsFeatureProcessor(seed));
        featureProcessors.put(Layer.MYCELIUM, new MyceliumFeatureProcessor(seed));
        featureProcessors.put(Layer.OCEANIA, new OceaniaFeatureProcessor(seed));
        featureProcessors.put(Layer.DEEP_EXTREMES, new DeepExtremesFeatureProcessor(seed));
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

        for (int ly = 0; ly < SIZE; ly++) {
            int wy = worldBaseY + ly;

            // 1. Resolve layer config once per Y-slice (config only depends on Y)
            Layer primaryLayer = Layer.forY(wy);
            LayerConfig cfg = resolveConfig(wy, primaryLayer);

            // Only update the terrain noise frequency when it actually changes
            float freq = cfg.noiseFrequency();
            if (freq != lastFrequency) {
                terrainNoise.SetFrequency(freq);
                lastFrequency = freq;
            }

            for (int lz = 0; lz < SIZE; lz++) {
                int wz = worldBaseZ + lz;
                for (int lx = 0; lx < SIZE; lx++) {
                    int wx = worldBaseX + lx;

                    float density = evaluateDensity(wx, wy, wz, cfg);
                    data.setDensity(lx, ly, lz, density);

                    VoxelType type = assignType(wx, wy, wz, density, cfg, primaryLayer);
                    data.setType(lx, ly, lz, type);
                }
            }
        }

        // 4. Apply layer-specific features (post-processing)
        Layer primaryLayer = Layer.forY(worldBaseY + SIZE / 2);
        LayerFeatureProcessor processor = featureProcessors.get(primaryLayer);
        if (processor != null) {
            processor.process(data, worldBaseX, worldBaseY, worldBaseZ);
        }

        return data;
    }

    // ---------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------

    /**
     * Returns the effective {@link LayerConfig} for a given world-Y,
     * blending between two layers if inside a transition zone.
     *
     * @param worldY       the world Y coordinate
     * @param primaryLayer the layer already resolved via {@link Layer#forY(int)}
     */
    private LayerConfig resolveConfig(int worldY, Layer primaryLayer) {
        Layer.TransitionInfo ti = Layer.getTransition(worldY);
        if (ti != null) {
            return ti.blendedConfig();
        }
        return primaryLayer.getConfig();
    }

    /**
     * Combined density = terrain noise − shaft term + boundary term − cave term.
     * <p>
     * The shaft term is 1.0 well inside the shaft (forcing air) and 0.0
     * far outside (no effect), with a smooth falloff controlled by
     * {@link #SHAFT_MARGIN}.
     * <p>
     * The boundary term adds density beyond the abyss perimeter, forming
     * impassable walls that prevent infinite horizontal exploration.
     * <p>
     * The cave term carves out pockets using ridged noise.
     */
    private float evaluateDensity(int wx, int wy, int wz, LayerConfig cfg) {
        // Terrain noise (frequency already set per Y-slice in generateChunk)
        float terrainDensity = terrainNoise.GetNoise(wx, wy, wz) * cfg.noiseAmplitude()
                + cfg.baseDensityThreshold();

        // Shaft term – radial distance from center axis (x=0, z=0)
        float shaftRadius = cfg.shaftDiameter() / 2f;
        // Perturb the radius with a secondary noise for organic edges
        float radiusPerturbation = shaftNoise.GetNoise(wx, wy, wz) * (shaftRadius * 0.25f);
        float effectiveRadius = shaftRadius + radiusPerturbation;

        float horizontalDist = (float) Math.sqrt((double) wx * wx + (double) wz * wz);
        float shaftTerm = 1f - smoothstep(effectiveRadius - SHAFT_MARGIN,
                effectiveRadius + SHAFT_MARGIN,
                horizontalDist);

        // Boundary term – push density high beyond the abyss perimeter
        float boundaryPerturbation = boundaryNoise.GetNoise(wx, wy, wz) * (ABYSS_BOUNDARY_RADIUS * 0.1f);
        float effectiveBoundary = ABYSS_BOUNDARY_RADIUS + boundaryPerturbation;
        float boundaryTerm = smoothstep(effectiveBoundary - BOUNDARY_MARGIN,
                effectiveBoundary + BOUNDARY_MARGIN,
                horizontalDist);

        // Cave term – ridged noise carves tunnels through solid terrain
        float caveSample = caveNoise.GetNoise(wx, wy, wz);
        float caveTerm = (caveSample > CAVE_THRESHOLD)
                ? (caveSample - CAVE_THRESHOLD) / (1f - CAVE_THRESHOLD)  // 0..1 ramp
                : 0f;

        return terrainDensity - shaftTerm + boundaryTerm - caveTerm;
    }

    /**
     * Given the final density, decide the discrete voxel type.
     */
    private VoxelType assignType(int wx, int wy, int wz, float density, LayerConfig cfg, Layer layer) {
        if (density > 0f) {
            // Solid – pick from dominant types via secondary noise
            VoxelType[] types = cfg.dominantVoxelTypes();
            if (types.length == 1) {
                return types[0];
            }

            float n = typeNoise.GetNoise(wx, wy, wz); // range roughly -1..1
            int idx = Math.floorMod((int) ((n + 1f) * 0.5f * types.length * 1000), types.length);
            return types[idx];
        }

        // Air – but may be water or hazard pocket

        // Oceania waterline: static water below the line
        if (layer == Layer.OCEANIA && wy < OCEANIA_WATERLINE_Y) {
            return VoxelType.WATER;
        }

        // Hazard pockets
        if (cfg.hazardType() != null) {
            float h = hazardNoise.GetNoise(wx, wy, wz);
            if (h > 0.55f) { // sparse pockets
                return cfg.hazardType();
            }
        }

        return VoxelType.AIR;
    }

    // ---------------------------------------------------------------
    // Math utilities
    // ---------------------------------------------------------------

    /** Standard smoothstep: 0 when x ≤ edge0, 1 when x ≥ edge1, smooth in between. */
    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }

    static int getOceaniaWaterlineY() {
        return OCEANIA_WATERLINE_Y;
    }

    static float getAbyssBoundaryRadius() {
        return ABYSS_BOUNDARY_RADIUS;
    }
}
