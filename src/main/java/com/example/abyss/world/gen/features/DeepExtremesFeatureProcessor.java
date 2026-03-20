package com.example.abyss.world.gen.features;

import com.example.abyss.lib.FastNoiseLite;
import com.example.abyss.world.gen.GenConfigResolver;
import com.example.abyss.world.gen.LayerFeatureProcessor;
import com.example.abyss.world.voxel.VoxelData;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Marks voxels in the Deep Extremes layer as unstable / collapse-prone
 * and places crystal formations.
 * <p>
 * Specifically:
 * <ul>
 *   <li>Replaces some solid voxels near cave surfaces with CRYSTAL for
 *       visual interest and as high-value resource markers.</li>
 *   <li>Converts thin support pillars (solid voxels with air on most sides)
 *       to OBSIDIAN to flag them as structurally weak — the collapse
 *       simulator can treat OBSIDIAN in this layer as prone to breaking.</li>
 * </ul>
 * </p>
 */
public class DeepExtremesFeatureProcessor implements LayerFeatureProcessor {

    private final FastNoiseLite crystalNoise;

    /** Noise above this threshold places crystal formations. */
    private static final float CRYSTAL_THRESHOLD = 0.50f;
    /** Minimum number of air neighbours to consider a voxel "exposed." */
    private static final int EXPOSED_THRESHOLD = 3;

    public DeepExtremesFeatureProcessor(long seed) {
        crystalNoise = new FastNoiseLite((int) (seed ^ 0x4445_4550L)); // "DEEP"
        crystalNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        crystalNoise.SetFrequency(0.06f);
    }

    @Override
    public void process(VoxelData data, int worldBaseX, int worldBaseY, int worldBaseZ, GenConfigResolver resolver) {
        int size = VoxelData.SIZE;

        for (int ly = 0; ly < size; ly++) {
            int wy = worldBaseY + ly;
            for (int lz = 0; lz < size; lz++) {
                int wz = worldBaseZ + lz;
                for (int lx = 0; lx < size; lx++) {
                    VoxelType current = data.getType(lx, ly, lz);
                    if (!current.isSolid()) continue;

                    int wx = worldBaseX + lx;
                    int airNeighbours = countAirNeighbours(data, lx, ly, lz, size);

                    // Crystal formation: exposed solid voxels near cave surfaces
                    if (airNeighbours >= 1 && airNeighbours <= 3) {
                        float n = crystalNoise.GetNoise(wx, wy, wz);
                        if (n > CRYSTAL_THRESHOLD) {
                            data.setType(lx, ly, lz, VoxelType.CRYSTAL);
                            // Keep density as-is for mesher
                        }
                    }

                    // Unstable pillar detection: mostly surrounded by air
                    if (airNeighbours >= EXPOSED_THRESHOLD && current == VoxelType.STONE) {
                        data.setType(lx, ly, lz, VoxelType.OBSIDIAN);
                        // Tag as structurally weak — CollapseSimulator can
                        // treat OBSIDIAN in Deep Extremes as collapse-prone
                    }
                }
            }
        }
    }

    /**
     * Counts how many of the 6 cardinal neighbours are AIR (or out-of-bounds,
     * treated as air for edge voxels).
     */
    private int countAirNeighbours(VoxelData data, int x, int y, int z, int size) {
        int count = 0;
        if (x <= 0     || data.getType(x - 1, y, z) == VoxelType.AIR) count++;
        if (x >= size-1 || data.getType(x + 1, y, z) == VoxelType.AIR) count++;
        if (y <= 0     || data.getType(x, y - 1, z) == VoxelType.AIR) count++;
        if (y >= size-1 || data.getType(x, y + 1, z) == VoxelType.AIR) count++;
        if (z <= 0     || data.getType(x, y, z - 1) == VoxelType.AIR) count++;
        if (z >= size-1 || data.getType(x, y, z + 1) == VoxelType.AIR) count++;
        return count;
    }
}