package com.example.abyss.world.render.lod;

import com.example.abyss.WorldConstants;

/**
 * Immutable configuration for one tier of the LOD streaming system.
 * All radii are in chunks. Tiers are ordered innermost-first in {@link LodConfig#TIERS}.
 *
 * @param lodLevel            index into {@link LodConfig#TIERS}; 0 = buffer (finest), higher = coarser
 * @param innerHRadiusChunks  inner horizontal boundary in chunks (exclusive); must equal previous tier's outer
 * @param outerHRadiusChunks  outer horizontal boundary in chunks (exclusive); must be a multiple of regionSizeChunks
 * @param innerVRadiusChunks  inner vertical boundary in chunks (exclusive)
 * @param outerVRadiusChunks  outer vertical boundary in chunks (exclusive)
 * @param regionSizeChunks    chunks per region side; 1 for buffer/LOD1, 4 for LOD2, etc.
 * @param sampleStep          voxels skipped per LOD grid cell; higher = coarser mesh, cheaper generation
 */
public record LodTierConfig(
    int lodLevel,
    int innerHRadiusChunks,
    int outerHRadiusChunks,
    int innerVRadiusChunks,
    int outerVRadiusChunks,
    int regionSizeChunks,
    int sampleStep
) {
    /** World-voxel size of one region side for this tier. */
    public int regionSizeVoxels() {
        return regionSizeChunks * WorldConstants.CHUNK_SIZE;
    }

    /** Horizontal band half-width in region units (how many regions out to sweep). */
    public int outerHRadiusRegions() {
        return outerHRadiusChunks / regionSizeChunks;
    }

    /** Inner horizontal exclusion in region units. */
    public int innerHRadiusRegions() {
        return (int) Math.ceil((double) innerHRadiusChunks / regionSizeChunks);
    }

    /** Outer vertical half-height in region units. */
    public int outerVRadiusRegions() {
        return outerVRadiusChunks / regionSizeChunks;
    }

    /** Inner vertical exclusion in region units. */
    public int innerVRadiusRegions() {
        return (int) Math.ceil((double) innerVRadiusChunks / regionSizeChunks);
    }
}
