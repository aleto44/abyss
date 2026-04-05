package com.example.abyss.world.render.lod;

/**
 * User-editable specification for one LOD tier.
 * Set {@code regionSize} first — {@code bandH} and {@code bandV} are counts of that region unit,
 * so the outer boundary is always aligned by construction.
 * Position in {@link LodConfig#TIER_SPECS} implies the {@code lodLevel}.
 *
 * @param regionSizeChunks chunks per region side for this tier
 * @param bandHRegions     horizontal band width in regions
 * @param bandVRegions     vertical band height in regions
 * @param sampleStep       voxels per LOD grid cell; higher = coarser mesh
 */
public record LodTierSpec(
    int regionSizeChunks,
    int bandHRegions,
    int bandVRegions,
    int sampleStep
) {
    /** Horizontal band width in chunks, used by {@link LodConfig#buildTiers}. */
    int bandHChunks() { return bandHRegions * regionSizeChunks; }

    /** Vertical band height in chunks, used by {@link LodConfig#buildTiers}. */
    int bandVChunks() { return bandVRegions * regionSizeChunks; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int regionSizeChunks;
        private int bandHRegions;
        private int bandVRegions;
        private int sampleStep;

        private Builder() {}

        /** Side length of one LOD region in chunks. Sets the unit for {@link #bandH} and {@link #bandV}. */
        public Builder regionSize(int chunks) { this.regionSizeChunks = chunks;  return this; }
        /** How many regions wide this tier's horizontal band extends. */
        public Builder bandH(int regions)     { this.bandHRegions     = regions; return this; }
        /** How many regions tall this tier's vertical band extends. */
        public Builder bandV(int regions)     { this.bandVRegions     = regions; return this; }
        /** Voxels per LOD grid cell. Higher = coarser mesh, cheaper to generate. */
        public Builder sampleStep(int step)   { this.sampleStep       = step;    return this; }

        public LodTierSpec build() {
            return new LodTierSpec(regionSizeChunks, bandHRegions, bandVRegions, sampleStep);
        }
    }
}