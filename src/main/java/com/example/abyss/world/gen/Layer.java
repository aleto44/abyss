package com.example.abyss.world.gen;

import com.badlogic.gdx.graphics.Color;
import com.example.abyss.world.voxel.VoxelType;

/**
 * The 7 distinct layers of the Abyss, ordered from surface to deepest.
 * <p>
 * Depth uses the Y axis: surface is y = 0, depth increases negatively.
 * Each layer owns a fixed depth range and a {@link LayerConfig} that drives
 * the procedural generation pipeline.
 * </p>
 */
public enum Layer {

    THE_EDGE(0, -500,
            new LayerConfig(
                    0.10f,   // baseDensityThreshold – lots of solid ground
                    0.012f,  // noiseFrequency – large, gentle hills
                    0.6f,    // noiseAmplitude
                    200f,    // shaftDiameter – medium shaft
                    new VoxelType[]{VoxelType.GRASS, VoxelType.DIRT, VoxelType.STONE},
                    null,    // no hazard
                    256f,    // visibility – clear
                    new Color[]{new Color(0.35f, 0.75f, 0.3f, 1f), new Color(0.55f, 0.45f, 0.25f, 1f)}
            )),

    FOREST_DEPTHS(-500, -1500,
            new LayerConfig(
                    0.05f,   // lower threshold – denser terrain
                    0.018f,  // higher frequency – finer detail for trees/caves
                    0.7f,
                    180f,
                    new VoxelType[]{VoxelType.WOOD, VoxelType.VINE, VoxelType.DIRT, VoxelType.STONE},
                    null,
                    192f,
                    new Color[]{new Color(0.15f, 0.5f, 0.15f, 1f), new Color(0.3f, 0.25f, 0.1f, 1f)}
            )),

    HANGING_GARDENS(-1500, -2500,
            new LayerConfig(
                    0.30f,   // high threshold – lots of air, floating islands
                    0.015f,
                    0.9f,    // high amplitude – dramatic formations
                    250f,    // wider shaft through floating islands
                    new VoxelType[]{VoxelType.STONE, VoxelType.VINE, VoxelType.GRASS},
                    null,
                    220f,
                    new Color[]{new Color(0.4f, 0.8f, 0.5f, 1f), new Color(0.7f, 0.85f, 0.6f, 1f)}
            )),

    SKY_WALLS(-2500, -3500,
            new LayerConfig(
                    0.45f,   // very high threshold – vast emptiness
                    0.008f,  // low frequency – giant wall features
                    0.5f,
                    400f,    // very wide shaft in the open air
                    new VoxelType[]{VoxelType.STONE, VoxelType.OBSIDIAN},
                    null,
                    512f,    // huge visibility – can see far across the void
                    new Color[]{new Color(0.6f, 0.7f, 0.85f, 1f), new Color(0.4f, 0.45f, 0.6f, 1f)}
            )),

    MYCELIUM(-3500, -4500,
            new LayerConfig(
                    0.08f,   // fairly dense – packed fungal growth
                    0.025f,  // high frequency – fine organic detail
                    0.65f,
                    120f,    // narrow shaft – claustrophobic
                    new VoxelType[]{VoxelType.MUSHROOM, VoxelType.DIRT, VoxelType.STONE},
                    VoxelType.TOXIC_GAS,  // spore clouds
                    64f,     // very low visibility
                    new Color[]{new Color(0.6f, 0.2f, 0.65f, 1f), new Color(0.3f, 0.8f, 0.5f, 1f)}
            )),

    OCEANIA(-4500, -5500,
            new LayerConfig(
                    0.25f,   // moderate – sparse islands in water
                    0.010f,  // low frequency – large ocean features
                    0.4f,
                    350f,    // wide shaft through the ocean
                    new VoxelType[]{VoxelType.CORAL, VoxelType.STONE, VoxelType.DIRT},
                    null,
                    128f,    // murky underwater
                    new Color[]{new Color(0.1f, 0.3f, 0.7f, 1f), new Color(0.05f, 0.2f, 0.5f, 1f)}
            )),

    DEEP_EXTREMES(-5500, -7000,
            new LayerConfig(
                    0.15f,
                    0.020f,
                    0.85f,   // high amplitude – chaotic terrain
                    150f,    // narrow, dangerous shaft
                    new VoxelType[]{VoxelType.OBSIDIAN, VoxelType.CRYSTAL, VoxelType.STONE},
                    VoxelType.TOXIC_GAS,
                    48f,     // near-zero visibility
                    new Color[]{new Color(0.15f, 0.05f, 0.2f, 1f), new Color(0.5f, 0.1f, 0.1f, 1f)}
            ));

    /** Inclusive upper bound (closer to surface, higher / less negative). */
    private final int yStart;
    /** Inclusive lower bound (deeper, more negative). */
    private final int yEnd;
    private final LayerConfig config;

    /** Width of the transition blend zone in voxels (centred on each boundary). */
    public static final int TRANSITION_SIZE = 64;

    Layer(int yStart, int yEnd, LayerConfig config) {
        this.yStart = yStart;
        this.yEnd = yEnd;
        this.config = config;
    }

    public int getYStart() { return yStart; }
    public int getYEnd()   { return yEnd; }
    public LayerConfig getConfig() { return config; }

    // ---- lookup helpers ----

    /**
     * Returns the primary layer for a given world Y-coordinate.
     * If {@code worldY} is above THE_EDGE it returns THE_EDGE;
     * if below DEEP_EXTREMES it returns DEEP_EXTREMES.
     */
    public static Layer forY(int worldY) {
        for (Layer l : values()) {
            if (worldY <= l.yStart && worldY > l.yEnd) {
                return l;
            }
        }
        // Above the top → surface layer; below the bottom → deepest layer
        if (worldY > values()[0].yStart) return values()[0];
        return values()[values().length - 1];
    }

    /**
     * If {@code worldY} falls within a transition zone, returns the
     * two layers and the blend factor.  Otherwise returns {@code null}.
     */
    public static TransitionInfo getTransition(int worldY) {
        Layer[] layers = values();
        for (int i = 0; i < layers.length - 1; i++) {
            int boundary = layers[i].yEnd; // == layers[i+1].yStart
            int halfZone = TRANSITION_SIZE / 2;
            int upper = boundary + halfZone;
            int lower = boundary - halfZone;
            if (worldY <= upper && worldY > lower) {
                float t = 1f - (float)(worldY - lower) / (float)(upper - lower); // 0 = shallow, 1 = deep
                return new TransitionInfo(layers[i], layers[i + 1], t);
            }
        }
        return null;
    }

    /**
     * Describes a position inside a transition zone between two layers.
     *
     * @param upper      the shallower layer
     * @param lower      the deeper layer
     * @param blendFactor 0.0 = fully upper, 1.0 = fully lower
     */
    public record TransitionInfo(Layer upper, Layer lower, float blendFactor) {
        /** Convenience: returns the interpolated config for this blend position. */
        public LayerConfig blendedConfig() {
            return LayerConfig.lerp(upper.config, lower.config, blendFactor);
        }
    }
}
