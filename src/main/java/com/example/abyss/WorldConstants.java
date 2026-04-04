package com.example.abyss;

/**
 * Repo-wide constants. All distance values are in chunks (1 chunk = 32 voxels).
 * To adjust the near-field/LOD boundary, change {@link #NEAR_FIELD_H_RADIUS} or
 * {@link #NEAR_FIELD_V_RADIUS} — everything else derives from them automatically.
 */
public final class WorldConstants {

    /** Side length of a single chunk in voxels. */
    public static final int CHUNK_SIZE = 32;

    /** Side length of a single LOD region in chunks. */
    public static final int REGION_CHUNKS = 2;

    /** Horizontal radius of full-detail chunks loaded around the player, in chunks. */
    public static final int NEAR_FIELD_H_RADIUS = 8;

    /** Vertical radius of full-detail chunks loaded around the player, in chunks. */
    public static final int NEAR_FIELD_V_RADIUS = 5;

    /** How far LOD chunks load horizontally from the player, in chunks. Must be > NEAR_FIELD_H_RADIUS. */
    public static final int LOD_BAND_H_RADIUS = 24;

    /** How far LOD chunks load vertically from the player, in chunks. Must be > NEAR_FIELD_V_RADIUS. */
    public static final int LOD_BAND_V_RADIUS = 16;

    /** Voxels per LOD proxy grid cell (XZ). Lower = more detail, higher = fewer quads per region. */
    public static final int LOD_PROXY_SAMPLE_STEP = 3;

    private WorldConstants() {}
}