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

    private static final int SIZE = VoxelData.SIZE;

    // --- constants ---

    /** Margin (in voxels) over which the shaft edge fades via smoothstep. */
    private static final float SHAFT_MARGIN = 30f;

    /** Waterline Y for the Oceania layer (everything below this is water if air). */
    private static final int OCEANIA_WATERLINE_Y = -4600;

    /**
     * Waterline Y for the surface ocean that fills the outer perimeter beyond the abyss
     * boundary. Voxels in the SURFACE layer that are outside the boundary and below this Y
     * become WATER, creating a surrounding ocean on the side of the world away from the abyss.
     */
    private static final int SURFACE_SEA_LEVEL_Y = 48;

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

    /** Hazard-pocket noise threshold – air voxels above this become hazardous. */
    private static final float HAZARD_THRESHOLD = 0.55f;

    // --- noise instances (all seeded from the world seed) ---
    private final FastNoiseLite terrainNoise;
    private final FastNoiseLite shaftNoise;      // perturbs shaft radius
    private final FastNoiseLite typeNoise;        // secondary octave for voxel-type selection
    private final FastNoiseLite hazardNoise;      // pockets for hazardous voxels
    private final FastNoiseLite caveNoise;        // dedicated cave carving
    private final FastNoiseLite boundaryNoise;    // perturbs the horizontal boundary wall

    /** Per-layer feature processors, applied as a post-processing pass after voxel fill. */
    private final Map<Layer, LayerFeatureProcessor> featureProcessors = new EnumMap<>(Layer.class);

    public ProceduralGenerator(long seed) {
        terrainNoise = buildNoise((int) seed,
                FastNoiseLite.NoiseType.OpenSimplex2, FastNoiseLite.FractalType.FBm, 4, -1f);
        shaftNoise = buildNoise((int) (seed ^ 0xBEEF_CAFEL),
                FastNoiseLite.NoiseType.OpenSimplex2, null, -1, 0.005f);
        typeNoise = buildNoise((int) (seed ^ 0xDEAD_FACEL),
                FastNoiseLite.NoiseType.OpenSimplex2, null, -1, 0.04f);
        hazardNoise = buildNoise((int) (seed ^ 0xCAFE_BABEL),
                FastNoiseLite.NoiseType.OpenSimplex2, null, -1, 0.03f);
        caveNoise = buildNoise((int) (seed ^ 0xF00D_BA11L),
                FastNoiseLite.NoiseType.OpenSimplex2, FastNoiseLite.FractalType.Ridged, 2, 0.006f);
        boundaryNoise = buildNoise((int) (seed ^ 0xABCD_1234L),
                FastNoiseLite.NoiseType.OpenSimplex2, null, -1, 0.003f);

        // Register layer-specific feature processors
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
     * Creates and configures a {@link FastNoiseLite} instance.
     *
     * @param seed         integer seed
     * @param noiseType    noise algorithm to use
     * @param fractalType  fractal layering, or {@code null} for none
     * @param octaves      fractal octave count; ignored when {@code fractalType} is {@code null}
     * @param frequency    base frequency, or {@code -1f} to leave at the library default
     */
    private static FastNoiseLite buildNoise(int seed,
                                            FastNoiseLite.NoiseType noiseType,
                                            FastNoiseLite.FractalType fractalType,
                                            int octaves,
                                            float frequency) {
        FastNoiseLite noise = new FastNoiseLite(seed);
        noise.SetNoiseType(noiseType);
        if (fractalType != null) {
            noise.SetFractalType(fractalType);
            noise.SetFractalOctaves(octaves);
        }
        if (frequency > 0f) {
            noise.SetFrequency(frequency);
        }
        return noise;
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

            // Step 1: resolve layer config once per Y-slice (config only depends on Y)
            Layer primaryLayer = Layer.forY(wy);
            LayerConfig cfg = resolveConfig(wy, primaryLayer);

            // Step 2: update terrain noise frequency only when it changes
            float freq = cfg.noiseFrequency();
            if (freq != lastFrequency) {
                terrainNoise.SetFrequency(freq);
                lastFrequency = freq;
            }

            // Step 3: evaluate density and assign voxel type
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
        float caveTerm = caveTerm(caveNoise.GetNoise(wx, wy, wz));

        return terrainDensity - shaftTerm + boundaryTerm - caveTerm;
    }

    /**
     * Maps a raw cave noise sample to a [0..1] carving strength.
     * Returns 0 when the sample is below {@link #CAVE_THRESHOLD}, and ramps
     * linearly to 1 at the maximum noise value.
     */
    private static float caveTerm(float caveSample) {
        if (caveSample <= CAVE_THRESHOLD) {
            return 0f;
        }
        return (caveSample - CAVE_THRESHOLD) / (1f - CAVE_THRESHOLD);
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

        // Surface outer ocean: beyond the boundary, fill below sea level with water
        if (layer == Layer.SURFACE) {
            float horizontalDist = (float) Math.sqrt((double) wx * wx + (double) wz * wz);
            float boundaryPerturbation = boundaryNoise.GetNoise(wx, wy, wz) * (ABYSS_BOUNDARY_RADIUS * 0.1f);
            float effectiveBoundary = ABYSS_BOUNDARY_RADIUS + boundaryPerturbation;
            if (horizontalDist > effectiveBoundary && wy <= SURFACE_SEA_LEVEL_Y) {
                return VoxelType.WATER;
            }
        }

        // Oceania waterline: static water below the line
        if (layer == Layer.OCEANIA && wy < OCEANIA_WATERLINE_Y) {
            return VoxelType.WATER;
        }

        // Hazard pockets
        if (cfg.hazardType() != null) {
            float h = hazardNoise.GetNoise(wx, wy, wz);
            if (h > HAZARD_THRESHOLD) {
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
