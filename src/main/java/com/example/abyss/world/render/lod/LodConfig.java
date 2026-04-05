package com.example.abyss.world.render.lod;

import com.example.abyss.WorldConstants;
import java.util.ArrayList;
import java.util.List;

/**
 * Boot-time-only configuration for all LOD streaming tiers.
 * Edit {@link #TIER_SPECS} to adjust the vision range — each entry specifies only
 * the band width/height for that tier; inner boundaries are chained automatically
 * from the previous tier's outer edge, and {@code lodLevel} is derived from order.
 * Tier geometry is fixed at startup — changes require a restart.
 */
public final class LodConfig {

    public static final List<LodTierSpec> TIER_SPECS = List.of(
        LodTierSpec.builder().regionSize(1).bandH(8).bandV(8).sampleStep(2).build(),
        LodTierSpec.builder().regionSize(2).bandH(4).bandV(4).sampleStep(4).build(),
        LodTierSpec.builder().regionSize(8).bandH(2).bandV(2).sampleStep(8).build()
    );

    public static final List<LodTierConfig> TIERS;

    static {
        TIERS = buildTiers(TIER_SPECS);
        validateTiers(TIERS);
    }

    private LodConfig() {}

    static List<LodTierConfig> buildTiers(List<LodTierSpec> specs) {
        List<LodTierConfig> tiers = new ArrayList<>();
        int innerH = WorldConstants.NEAR_FIELD_H_RADIUS;
        int innerV = WorldConstants.NEAR_FIELD_V_RADIUS;
        for (int i = 0; i < specs.size(); i++) {
            LodTierSpec spec = specs.get(i);
            int outerH = innerH + spec.bandHChunks();
            int outerV = innerV + spec.bandVChunks();
            tiers.add(new LodTierConfig(i, innerH, outerH, innerV, outerV, spec.regionSizeChunks(), spec.sampleStep()));
            innerH = outerH;
            innerV = outerV;
        }
        return List.copyOf(tiers);
    }

    static void validateTiers(List<LodTierConfig> tiers) {
        boolean isEmpty = tiers.isEmpty();
        if (isEmpty) {
            throw new IllegalStateException("LodConfig.TIERS must not be empty.");
        }

        LodTierConfig first = tiers.get(0);
        boolean bufferStartsAtNearFieldH = first.innerHRadiusChunks() == WorldConstants.NEAR_FIELD_H_RADIUS;
        if (!bufferStartsAtNearFieldH) {
            throw new IllegalStateException(
                "Tier 0 innerHRadiusChunks (" + first.innerHRadiusChunks()
                + ") must equal NEAR_FIELD_H_RADIUS (" + WorldConstants.NEAR_FIELD_H_RADIUS + ").");
        }

        boolean bufferStartsAtNearFieldV = first.innerVRadiusChunks() == WorldConstants.NEAR_FIELD_V_RADIUS;
        if (!bufferStartsAtNearFieldV) {
            throw new IllegalStateException(
                "Tier 0 innerVRadiusChunks (" + first.innerVRadiusChunks()
                + ") must equal NEAR_FIELD_V_RADIUS (" + WorldConstants.NEAR_FIELD_V_RADIUS + ").");
        }

        for (int i = 0; i < tiers.size(); i++) {
            LodTierConfig tier = tiers.get(i);

            boolean hasNext = i + 1 < tiers.size();
            if (hasNext) {
                LodTierConfig next = tiers.get(i + 1);
                boolean hContiguous = next.innerHRadiusChunks() == tier.outerHRadiusChunks();
                if (!hContiguous) {
                    throw new IllegalStateException(
                        "Tier " + (i + 1) + " innerHRadiusChunks (" + next.innerHRadiusChunks()
                        + ") must equal tier " + i + " outerHRadiusChunks (" + tier.outerHRadiusChunks() + ").");
                }
                boolean vContiguous = next.innerVRadiusChunks() == tier.outerVRadiusChunks();
                if (!vContiguous) {
                    throw new IllegalStateException(
                        "Tier " + (i + 1) + " innerVRadiusChunks (" + next.innerVRadiusChunks()
                        + ") must equal tier " + i + " outerVRadiusChunks (" + tier.outerVRadiusChunks() + ").");
                }
            }
        }
    }
}