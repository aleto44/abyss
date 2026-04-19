package com.example.abyss.world.render.distant;

/**
 * Per-column heightmap data for one vertical band of a macro-chunk.
 * Arrays are indexed [gz * gridW + gx] where gridW = MACRO_CHUNK_SIZE_VOXELS / DISTANT_SAMPLING_STEP.
 *
 * @param bandIndex    which vertical band this covers (band N covers chunk-Y range [N*VERTICAL_BAND_UNIT_CHUNKS, (N+1)*VERTICAL_BAND_UNIT_CHUNKS))
 * @param maxHeight    highest solid voxel world-Y per column, or Integer.MIN_VALUE if fully empty
 * @param minHeight    lowest solid voxel world-Y per column, or Integer.MAX_VALUE if fully empty
 * @param densityScore normalized [0,1] solidity fraction per column
 */
public record HeightmapBand(int bandIndex, int[] maxHeight, int[] minHeight, float[] densityScore) {}
