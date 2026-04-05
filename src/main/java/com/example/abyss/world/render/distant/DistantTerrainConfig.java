package com.example.abyss.world.render.distant;

import com.example.abyss.WorldConstants;

/**
 * Boot-time constants for the distant terrain background layer.
 * <p>
 * There is exactly one uniform resolution and no tier concept — every region
 * outside the near-field is generated at the same {@link #SAMPLE_STEP} and
 * {@link #REGION_SIZE_CHUNKS}. This eliminates seam artefacts at tier boundaries.
 * </p>
 * All tunables live here. Never hardcode these values elsewhere.
 */
public final class DistantTerrainConfig {

    /** Side length of one distant terrain region, in chunks. */
    public static final int REGION_SIZE_CHUNKS = 4;

    /**
     * Voxels skipped per height-sample grid cell.
     * One height sample is taken per {@code SAMPLE_STEP × SAMPLE_STEP} voxel column.
     */
    public static final int SAMPLE_STEP = 4;

    /** World-voxel footprint of one region side. */
    public static final int REGION_SIZE_VOXELS = REGION_SIZE_CHUNKS * WorldConstants.CHUNK_SIZE;

    /**
     * Horizontal coverage radius in regions. The streamer will submit new regions up to this
     * distance from the player. Acts as a generation cap — not a streaming band edge.
     */
    public static final int HORIZON_H_RADIUS_REGIONS = 64;

    /** Vertical coverage radius in regions. Generous to cover the full abyss depth. */
    public static final int HORIZON_V_RADIUS_REGIONS = 32;

    /**
     * Hard eviction radius in regions. Regions further than this are eligible for removal
     * even under the permanent-background model. Exists only to cap VRAM when the player
     * teleports extreme distances. Set well beyond {@link #HORIZON_H_RADIUS_REGIONS} so
     * that previously-loaded background terrain is kept during normal backtracking.
     */
    public static final int EVICTION_H_RADIUS_REGIONS = 80;

    /** Vertical counterpart to {@link #EVICTION_H_RADIUS_REGIONS}. */
    public static final int EVICTION_V_RADIUS_REGIONS = 40;

    /**
     * Hard cap on the number of distant terrain generation tasks in-flight at once.
     * Prevents the shared worker pool from being flooded with distant tasks that
     * block near-field chunk generation from completing.
     */
    public static final int MAX_IN_FLIGHT_TASKS = 128;

    /**
     * How often the safety-eviction scan runs. Eviction is a last resort, not a
     * routine operation — 30 seconds is intentionally long.
     */
    public static final float EVICTION_INTERVAL_SECONDS = 30.0f;

    private DistantTerrainConfig() {}
}
