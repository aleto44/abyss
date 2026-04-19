package com.example.abyss.world.render.distant;

import com.example.abyss.WorldConstants;
import com.example.abyss.world.gen.Layer;

/**
 * Boot-time constants for the banded heightmap distant terrain system.
 * All tunables live here; never hardcode these values elsewhere.
 *
 * Unit key:
 *   voxels       – individual world blocks
 *   chunks       – WorldConstants.CHUNK_SIZE voxels per side
 *   macro-chunks – HORIZONTAL_DISTANT_TERRAIN_WIDTH chunks per side
 *   bands        – VERTICAL_DISTANT_TERRAIN_HEIGHT chunks tall
 */
public final class DistantTerrainConfig {

    // -------------------------------------------------------------------------
    // Macro-chunk shape  (tunables – change these to resize the LOD grid)
    // -------------------------------------------------------------------------

    /** XZ side length of one macro-chunk, in chunks. */
    public static final int HORIZONTAL_DISTANT_TERRAIN_WIDTH = 32;

    /** Height of one vertical band, in chunks. */
    public static final int VERTICAL_DISTANT_TERRAIN_HEIGHT = 2;

    /** Voxels skipped between height-sample grid cells within a macro-chunk column. */
    public static final int DISTANT_SAMPLING_STEP = 4;

    // -------------------------------------------------------------------------
    // Derived sizes  (computed from shape; do not edit directly)
    // -------------------------------------------------------------------------

    /** Voxel footprint of one macro-chunk side. */
    public static final int MACRO_CHUNK_SIZE_VOXELS = HORIZONTAL_DISTANT_TERRAIN_WIDTH * WorldConstants.CHUNK_SIZE;

    /** Total number of vertical bands spanning the full world height. */
    public static final int TOTAL_BAND_COUNT =
            (Math.floorDiv(Layer.SURFACE.getYStart(), WorldConstants.CHUNK_SIZE)
            - Math.floorDiv(Layer.DEEP_EXTREMES.getYEnd(), WorldConstants.CHUNK_SIZE))
            / VERTICAL_DISTANT_TERRAIN_HEIGHT;

    // -------------------------------------------------------------------------
    // World bounds
    // -------------------------------------------------------------------------

    /** Hard XZ boundary: macro-chunk coordinates with |x| or |z| beyond this are rejected. */
    public static final int WORLD_MAX_COORD_MACRO_CHUNKS = 128;

    /** Inclusive minimum valid band index (bottom of the world). */
    public static final int WORLD_MIN_BAND_Y = 0;

    /** Inclusive maximum valid band index (top of the world). */
    public static final int WORLD_MAX_BAND_Y = TOTAL_BAND_COUNT - 1;

    // -------------------------------------------------------------------------
    // XZ load / eviction distances  (in macro-chunks)
    // -------------------------------------------------------------------------

    /**
     * Radius of the XZ load region around the player.
     * 6 macro-chunks ≈ 6 144 voxels — enough to cover the full mountain ring around the abyss shaft.
     */
    public static final int LOAD_DISTANCE_MACRO_CHUNKS = 2;

    /**
     * Hysteresis gap beyond LOAD_DISTANCE_MACRO_CHUNKS before a key is evicted.
     * Prevents thrashing when the player hovers near a load-region edge.
     */
    public static final int EVICTION_PADDING_MACRO_CHUNKS = 2;

    // -------------------------------------------------------------------------
    // Y load / eviction distances  (in bands)
    // -------------------------------------------------------------------------

    /** Chunk-space vertical render radius used to derive LOAD_DISTANCE_BANDS_Y. */
    public static final int BAND_Y_RENDER_DISTANCE_CHUNKS = 32;

    /**
     * Vertical radius around the player's band within which bands are meshed.
     * 64 chunks ≈ 2 048 voxels — covers ~4 full abyss layers in each direction.
     */
    public static final int LOAD_DISTANCE_BANDS_Y = BAND_Y_RENDER_DISTANCE_CHUNKS / VERTICAL_DISTANT_TERRAIN_HEIGHT;

    /**
     * Hysteresis gap beyond LOAD_DISTANCE_BANDS_Y before a band-slab is evicted.
     * Prevents thrashing when the player hovers near a vertical load boundary.
     */
    public static final int EVICTION_PADDING_BANDS_Y = 3;

    // -------------------------------------------------------------------------
    // Worker threads
    // -------------------------------------------------------------------------

    /** Dedicated distant-terrain worker threads, isolated from the shared near-field pool. */
    public static final int WORKER_THREADS = Math.min(6, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));

    private DistantTerrainConfig() {}
}