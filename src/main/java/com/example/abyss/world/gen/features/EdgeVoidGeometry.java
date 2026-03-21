package com.example.abyss.world.gen.features;

/**
 * Shared constants that define the Edge void's radial geometry.
 * <p>
 * Both {@link EdgeFeatureProcessor} and {@link AbyssRimProcessor} must agree on
 * exactly where the void edge sits for a given XZ position.  Keeping the seed
 * XORs, noise frequencies, and amplitudes here prevents them from drifting
 * apart if either class is edited independently.
 * </p>
 */
final class EdgeVoidGeometry {

    /** XOR applied to the world seed to produce the void-jitter noise seed. */
    static final long VOID_JITTER_SEED_XOR = 0x564A_5452L; // "VJTR"

    /** Noise frequency for the void-edge jitter. */
    static final float VOID_JITTER_FREQUENCY = 0.007f;

    /**
     * Maximum radial displacement (voxels) the jitter noise can apply to the void edge.
     * Positive → void widens; negative → void narrows.
     */
    static final float VOID_JITTER_AMPLITUDE = 14f;

    /** XOR applied to the world seed to produce the rim-erosion noise seed. */
    static final long RIM_EROSION_SEED_XOR = 0x524D_4552L; // "RMER"

    /** Noise frequency for the rim erosion. */
    static final float RIM_EROSION_FREQUENCY = 0.005f;

    /** Fractal octaves for the rim erosion noise. */
    static final int RIM_EROSION_OCTAVES = 3;

    /**
     * Maximum extra void radius (voxels) the erosion can eat into the wall at y=0.
     * Strongest at the rim, fades to zero at RIM_EROSION_DEPTH below.
     */
    static final float RIM_EROSION_AMPLITUDE = 20f;

    private EdgeVoidGeometry() {}
}
