package com.example.abyss.debug.viewer;

import com.badlogic.gdx.graphics.Color;
import com.example.abyss.world.gen.LayerConfig;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Resolves deterministic debug colors from sampled worldgen metadata.
 */
public class WorldGenColorResolver {

    private static final Color OUTLINE_COLOR = new Color(0.95f, 0.95f, 0.95f, 1f);
    private static final Color DEEP_AIR_COLOR = new Color(0.02f, 0.02f, 0.04f, 1f);
    private static final Color DEFAULT_AIR_COLOR = new Color(0.08f, 0.08f, 0.12f, 1f);
    private static final float TRANSITION_STRIPE_SPACING = 6f;

    public Color resolveColor(WorldGenSample sample) {
        Color outColor = new Color();
        resolveColor(
                sample.worldX(),
                sample.worldY(),
                sample.density(),
                sample.voxelType(),
                sample.effectiveConfig(),
                sample.inTransition(),
                outColor
        );
        return outColor;
    }

    public void resolveColor(int worldX,
                             int worldY,
                             float density,
                             VoxelType voxelType,
                             LayerConfig effectiveConfig,
                             boolean inTransition,
                             Color outColor) {
        if (voxelType == VoxelType.AIR) {
            resolveAirColor(density, effectiveConfig, outColor);
        } else {
            outColor.set(voxelType.getBaseColor());
        }

        applyLayerTint(outColor, effectiveConfig);
        if (inTransition) {
            applyTransitionOverlay(outColor, worldX, worldY);
        }
        outColor.a = 1f;
    }

    public Color getOutlineColor() {
        return OUTLINE_COLOR;
    }

    private static void resolveAirColor(float density, LayerConfig config, Color outColor) {
        Color[] palette = config.colorPalette();
        if (palette != null && palette.length > 0) {
            outColor.set(palette[0]);
        } else {
            outColor.set(DEFAULT_AIR_COLOR);
        }

        float visibilityFactor = clamp01(config.visibility() / 512f);
        float densityFactor = clamp01((density + 1f) * 0.5f);
        outColor.lerp(DEEP_AIR_COLOR, 0.55f - visibilityFactor * 0.35f);
        outColor.mul(0.55f + densityFactor * 0.35f);
        outColor.a = 1f;
    }

    private static void applyLayerTint(Color color, LayerConfig config) {
        Color[] palette = config.colorPalette();
        if (palette == null || palette.length == 0) {
            return;
        }

        Color tint = palette[Math.min(1, palette.length - 1)];
        float visibilityFactor = clamp01(config.visibility() / 512f);
        float tintAmount = 0.12f + (1f - visibilityFactor) * 0.18f;
        color.lerp(tint, tintAmount);
        color.a = 1f;
    }

    private static void applyTransitionOverlay(Color color, int worldX, int worldY) {
        int stripeBucket = (int) Math.floor((worldX + worldY) / TRANSITION_STRIPE_SPACING);
        if ((stripeBucket & 1) == 0) {
            color.lerp(OUTLINE_COLOR, 0.18f);
        }
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
