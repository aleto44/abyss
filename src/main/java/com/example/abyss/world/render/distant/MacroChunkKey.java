package com.example.abyss.world.render.distant;

/**
 * Stable XYZ identity for a single distant terrain macro-chunk slab.
 * XZ coordinates are in macro-chunk units (one side = {@code MACRO_CHUNK_SIZE_VOXELS} wide).
 * {@code macroY} is the vertical band index: band N covers chunk-Y range
 * [{@code WORLD_MIN_CHUNK_Y + N * VERTICAL_DISTANT_TERRAIN_HEIGHT},
 *  {@code WORLD_MIN_CHUNK_Y + (N+1) * VERTICAL_DISTANT_TERRAIN_HEIGHT}).
 * Each key represents exactly one independently streamable slab.
 */
public record MacroChunkKey(int macroX, int macroY, int macroZ) {}