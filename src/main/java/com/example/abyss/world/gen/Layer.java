package com.example.abyss.world.gen;

import com.badlogic.gdx.graphics.Color;
import com.example.abyss.world.voxel.VoxelType;

/**
 * The distinct layers of the Abyss, ordered from the surface down to the deepest reaches.
 * <p>
 * Depth uses the Y axis: the overworld surface sits above y = 0, and abyss depth increases
 * negatively.  Each layer owns a fixed depth range plus a {@link LayerGenConfig} (drives
 * procedural generation) and a {@link LayerRenderConfig} (drives fog and colour tinting).
 * </p>
 */
public enum Layer {

    SURFACE(512, 0,
            new LayerGenConfig(
                    0.12f,   // threshold – lower than before so the bottom of SURFACE blends toward Edge openness
                    0.0045f, // broad, sweeping mountain shapes around the abyss
                    0.90f,   // amplitude – reduced to narrow the gap with THE_EDGE for smoother blending
                    520f,    // very wide opening at the surface
                    new VoxelType[]{VoxelType.STONE, VoxelType.DIRT, VoxelType.GRASS},
                    null
            ),
            new LayerRenderConfig(
                    320f,    // brightest, clearest layer
                    new Color[]{new Color(0.62f, 0.72f, 0.68f, 1f), new Color(0.88f, 0.90f, 0.95f, 1f)}
            )),

    THE_EDGE(0, -250,
            new LayerGenConfig(
                    0.05f,   // baseDensityThreshold – denser solid baseline for thick terrain
                    0.020f,  // noiseFrequency – higher frequency for chunkier, more varied formations
                    0.85f,   // noiseAmplitude – more dramatic variation creates thick protruding chunks
                    420f,    // shaftDiameter – tighter shaft pushes solid mass closer to the centre
                    new VoxelType[]{VoxelType.GRASS, VoxelType.DIRT, VoxelType.STONE},
                    null     // no hazard
            ),
            new LayerRenderConfig(
                    256f,    // visibility – clear
                    new Color[]{new Color(0.35f, 0.75f, 0.3f, 1f), new Color(0.55f, 0.45f, 0.25f, 1f)}
            )),

    FOREST_DEPTHS(-250, -1500,
            new LayerGenConfig(
                    0.28f,   // raised threshold – dense solid terrain with cave corridors
                    0.009f,  // lower frequency – wide cave-friendly formations
                    0.7f,
                    180f,
                    new VoxelType[]{VoxelType.WOOD, VoxelType.VINE, VoxelType.DIRT, VoxelType.STONE},
                    null
            ),
            new LayerRenderConfig(
                    192f,
                    new Color[]{new Color(0.15f, 0.5f, 0.15f, 1f), new Color(0.3f, 0.25f, 0.1f, 1f)}
            )),

    HANGING_GARDENS(-1500, -2500,
            new LayerGenConfig(
                    0.20f,   // moderate threshold – floating shelves with traversable gaps
                    0.010f,  // lower frequency – large platform-scale formations
                    0.75f,   // amplitude – dramatic but not fully disconnected
                    250f,    // wider shaft through floating islands
                    new VoxelType[]{VoxelType.STONE, VoxelType.VINE, VoxelType.GRASS},
                    null
            ),
            new LayerRenderConfig(
                    220f,
                    new Color[]{new Color(0.4f, 0.8f, 0.5f, 1f), new Color(0.7f, 0.85f, 0.6f, 1f)}
            )),

    SKY_WALLS(-2500, -3500,
            new LayerGenConfig(
                    0.45f,   // very high threshold – vast emptiness
                    0.008f,  // low frequency – giant wall features
                    0.5f,
                    400f,    // very wide shaft in the open air
                    new VoxelType[]{VoxelType.STONE, VoxelType.OBSIDIAN},
                    null
            ),
            new LayerRenderConfig(
                    512f,    // huge visibility – can see far across the void
                    new Color[]{new Color(0.6f, 0.7f, 0.85f, 1f), new Color(0.4f, 0.45f, 0.6f, 1f)}
            )),

    MYCELIUM(-3500, -4500,
            new LayerGenConfig(
                    0.08f,   // fairly dense – packed fungal growth
                    0.025f,  // high frequency – fine organic detail
                    0.65f,
                    120f,    // narrow shaft – claustrophobic
                    new VoxelType[]{VoxelType.MUSHROOM, VoxelType.DIRT, VoxelType.STONE},
                    VoxelType.TOXIC_GAS  // spore clouds
            ),
            new LayerRenderConfig(
                    64f,     // very low visibility
                    new Color[]{new Color(0.6f, 0.2f, 0.65f, 1f), new Color(0.3f, 0.8f, 0.5f, 1f)}
            )),

    OCEANIA(-4500, -5500,
            new LayerGenConfig(
                    0.25f,   // moderate – sparse islands in water
                    0.010f,  // low frequency – large ocean features
                    0.4f,
                    350f,    // wide shaft through the ocean
                    new VoxelType[]{VoxelType.CORAL, VoxelType.STONE, VoxelType.DIRT},
                    null
            ),
            new LayerRenderConfig(
                    128f,    // murky underwater
                    new Color[]{new Color(0.1f, 0.3f, 0.7f, 1f), new Color(0.05f, 0.2f, 0.5f, 1f)}
            )),

    DEEP_EXTREMES(-5500, -7000,
            new LayerGenConfig(
                    0.32f,   // raised threshold – solid dangerous terrain with cave corridors
                    0.008f,  // lower frequency – large chaotic passages
                    0.85f,   // high amplitude – chaotic terrain
                    150f,    // narrow, dangerous shaft
                    new VoxelType[]{VoxelType.OBSIDIAN, VoxelType.CRYSTAL, VoxelType.STONE},
                    VoxelType.TOXIC_GAS
            ),
            new LayerRenderConfig(
                    48f,     // near-zero visibility
                    new Color[]{new Color(0.15f, 0.05f, 0.2f, 1f), new Color(0.5f, 0.1f, 0.1f, 1f)}
            ));

    /** Inclusive upper bound (closer to the surface, higher / less negative). */
    private final int yStart;
    /** Inclusive lower bound (deeper, more negative). */
    private final int yEnd;
    private final LayerGenConfig    genConfig;
    private final LayerRenderConfig renderConfig;

    /** Width of the transition blend zone in voxels (centred on each boundary). */
    public static final int TRANSITION_SIZE = 192;

    Layer(int yStart, int yEnd, LayerGenConfig genConfig, LayerRenderConfig renderConfig) {
        this.yStart       = yStart;
        this.yEnd         = yEnd;
        this.genConfig    = genConfig;
        this.renderConfig = renderConfig;
    }

    public int getYStart() {
        return yStart;
    }

    public int getYEnd() {
        return yEnd;
    }

    /** Generation parameters for this layer (density thresholds, noise, shaft size, etc.). */
    public LayerGenConfig getGenConfig() {
        return genConfig;
    }

    /** Rendering parameters for this layer (visibility, colour palette). */
    public LayerRenderConfig getRenderConfig() {
        return renderConfig;
    }

    // ---- lookup helpers ----

    /**
     * Returns the primary layer for a given world Y-coordinate.
     * If {@code worldY} is above {@link #SURFACE} it returns {@link #SURFACE};
     * if below {@link #DEEP_EXTREMES} it returns {@link #DEEP_EXTREMES}.
     */
    public static Layer forY(int worldY) {
        for (Layer l : values()) {
            if (worldY <= l.yStart && worldY > l.yEnd) {
                return l;
            }
        }
        if (worldY > values()[0].yStart) {
            return values()[0];
        }
        return values()[values().length - 1];
    }

    /**
     * If {@code worldY} falls within a transition zone, returns the two layers
     * and the blend factor.  Otherwise returns {@code null}.
     */
    public static TransitionInfo getTransition(int worldY) {
        Layer[] layers = values();
        for (int i = 0; i < layers.length - 1; i++) {
            int boundary = layers[i].yEnd; // == layers[i+1].yStart
            int halfZone = TRANSITION_SIZE / 2;
            int upper = boundary + halfZone;
            int lower = boundary - halfZone;
            if (worldY <= upper && worldY > lower) {
                float t = 1f - (float) (worldY - lower) / (float) (upper - lower); // 0 = shallow, 1 = deep
                return new TransitionInfo(layers[i], layers[i + 1], t);
            }
        }
        return null;
    }

    /**
     * Describes a position inside a transition zone between two layers.
     *
     * @param upper       the shallower layer
     * @param lower       the deeper layer
     * @param blendFactor 0.0 = fully upper, 1.0 = fully lower
     */
    public record TransitionInfo(Layer upper, Layer lower, float blendFactor) {

        /** Interpolated generation config at this blend position. */
        public LayerGenConfig blendedGenConfig() {
            return LayerGenConfig.lerp(upper.genConfig, lower.genConfig, blendFactor);
        }

        /** Interpolated render config at this blend position. */
        public LayerRenderConfig blendedRenderConfig() {
            return LayerRenderConfig.lerp(upper.renderConfig, lower.renderConfig, blendFactor);
        }
    }
}