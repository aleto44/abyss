package com.example.abyss.world.gen.features;

import com.example.abyss.lib.FastNoiseLite;
import com.example.abyss.world.gen.GenConfigResolver;
import com.example.abyss.world.gen.LayerFeatureProcessor;
import com.example.abyss.world.voxel.VoxelData;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Grows dense tree canopy and vine networks in the Forest Depths layer.
 * <p>
 * Scans each XZ column: on top of existing solid ground, places WOOD trunks
 * upward and VINE canopy around them, creating a thick forest feel.
 * </p>
 */
public class ForestFeatureProcessor implements LayerFeatureProcessor {

    private final FastNoiseLite treePlacement;

    private static final float TREE_THRESHOLD = 0.55f;
    private static final int TRUNK_HEIGHT = 8;
    private static final int CANOPY_RADIUS = 2;

    public ForestFeatureProcessor(long seed) {
        treePlacement = new FastNoiseLite((int) (seed ^ 0x54_52_45_45L)); // "TREE"
        treePlacement.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        treePlacement.SetFrequency(0.05f);
    }

    @Override
    public void process(VoxelData data, int worldBaseX, int worldBaseY, int worldBaseZ, GenConfigResolver resolver) {
        int size = VoxelData.SIZE;

        for (int lz = 0; lz < size; lz++) {
            int wz = worldBaseZ + lz;
            for (int lx = 0; lx < size; lx++) {
                int wx = worldBaseX + lx;

                float n = treePlacement.GetNoise(wx, wz);
                if (n < TREE_THRESHOLD) continue;

                // Find ground
                int groundY = -1;
                for (int ly = size - 1; ly >= 0; ly--) {
                    if (data.getType(lx, ly, lz).isSolid()) {
                        groundY = ly;
                        break;
                    }
                }
                if (groundY < 0 || groundY + TRUNK_HEIGHT + CANOPY_RADIUS + 1 >= size) continue;

                // Trunk
                for (int dy = 1; dy <= TRUNK_HEIGHT; dy++) {
                    int ly = groundY + dy;
                    data.setType(lx, ly, lz, VoxelType.WOOD);
                    data.setDensity(lx, ly, lz, 0.5f);
                }

                // Canopy (sphere of vines)
                int canopyCenterY = groundY + TRUNK_HEIGHT + 1;
                for (int dy = -CANOPY_RADIUS; dy <= CANOPY_RADIUS; dy++) {
                    for (int dz = -CANOPY_RADIUS; dz <= CANOPY_RADIUS; dz++) {
                        for (int dx = -CANOPY_RADIUS; dx <= CANOPY_RADIUS; dx++) {
                            if (dx * dx + dy * dy + dz * dz > CANOPY_RADIUS * CANOPY_RADIUS) continue;
                            int px = lx + dx;
                            int py = canopyCenterY + dy;
                            int pz = lz + dz;
                            if (px < 0 || px >= size || py < 0 || py >= size || pz < 0 || pz >= size) continue;
                            if (data.getType(px, py, pz) != VoxelType.AIR) continue;
                            data.setType(px, py, pz, VoxelType.VINE);
                            data.setDensity(px, py, pz, 0.3f);
                        }
                    }
                }
            }
        }
    }
}