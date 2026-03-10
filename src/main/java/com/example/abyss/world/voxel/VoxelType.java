package com.example.abyss.world.voxel;

import com.badlogic.gdx.graphics.Color;

/**
 * Enum of all voxel types in the Abyss world.
 * Each type carries physical and rendering properties used by the
 * generation pipeline, collision system, and mesher.
 */
public enum VoxelType {

    // --- Non-solid ---
    AIR       (false, true,  false, new Color(0, 0, 0, 0)),
    WATER     (false, true,  false, new Color(0.15f, 0.35f, 0.85f, 0.6f)),
    TOXIC_GAS (false, true,  true,  new Color(0.45f, 0.75f, 0.15f, 0.4f)),

    // --- Solid / natural ---
    STONE     (true,  false, false, new Color(0.5f, 0.5f, 0.5f, 1f)),
    DIRT      (true,  false, false, new Color(0.45f, 0.3f, 0.15f, 1f)),
    GRASS     (true,  false, false, new Color(0.2f, 0.7f, 0.25f, 1f)),

    // --- Solid / organic ---
    WOOD      (true,  false, false, new Color(0.55f, 0.35f, 0.15f, 1f)),
    VINE      (true,  true,  false, new Color(0.1f, 0.55f, 0.15f, 0.85f)),
    MUSHROOM  (true,  false, false, new Color(0.7f, 0.2f, 0.55f, 1f)),

    // --- Solid / aquatic ---
    CORAL     (true,  false, false, new Color(0.9f, 0.45f, 0.3f, 1f)),

    // --- Solid / deep ---
    CRYSTAL   (true,  true,  false, new Color(0.6f, 0.85f, 0.95f, 0.9f)),
    OBSIDIAN  (true,  false, false, new Color(0.1f, 0.05f, 0.15f, 1f));

    private final boolean solid;
    private final boolean transparent;
    private final boolean hazardous;
    private final Color baseColor;

    VoxelType(boolean solid, boolean transparent, boolean hazardous, Color baseColor) {
        this.solid = solid;
        this.transparent = transparent;
        this.hazardous = hazardous;
        this.baseColor = baseColor;
    }

    public boolean isSolid() {
        return solid;
    }

    public boolean isTransparent() {
        return transparent;
    }

    public boolean isHazardous() {
        return hazardous;
    }

    public Color getBaseColor() {
        return baseColor;
    }
}
