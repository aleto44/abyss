package com.example.abyss.world.gen.features;

import com.example.abyss.lib.FastNoiseLite;
import com.example.abyss.world.gen.GenConfigResolver;
import com.example.abyss.world.gen.LayerFeatureProcessor;
import com.example.abyss.world.voxel.VoxelData;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Places underwater ruin structures in the Oceania layer.
 * <p>
 * Spawns small procedural ruin footprints (rectangular stone/coral
 * walls partially eroded) on the ocean floor.  These serve as
 * exploration landmarks and future loot/puzzle sites.
 * </p>
 */
public class OceaniaFeatureProcessor implements LayerFeatureProcessor {

    private final FastNoiseLite ruinNoise;

    /** Threshold for ruin placement sites. */
    private static final float RUIN_THRESHOLD = 0.62f;
    /** Max wall height of a ruin fragment. */
    private static final int WALL_HEIGHT = 5;
    /** Half-width of a ruin footprint. */
    private static final int RUIN_HALF_SIZE = 3;

    public OceaniaFeatureProcessor(long seed) {
        ruinNoise = new FastNoiseLite((int) (seed ^ 0x4F43_4541L)); // "OCEA"
        ruinNoise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        ruinNoise.SetFrequency(0.045f);
    }

    @Override
    public void process(VoxelData data, int worldBaseX, int worldBaseY, int worldBaseZ, GenConfigResolver resolver) {
        int size = VoxelData.SIZE;

        for (int lz = 0; lz < size; lz++) {
            int wz = worldBaseZ + lz;
            for (int lx = 0; lx < size; lx++) {
                int wx = worldBaseX + lx;

                float n = ruinNoise.GetNoise(wx, wz);
                if (n < RUIN_THRESHOLD) continue;

                // Find ocean floor (topmost solid voxel)
                int floorY = -1;
                for (int ly = size - 1; ly >= 0; ly--) {
                    if (data.getType(lx, ly, lz).isSolid()) {
                        floorY = ly;
                        break;
                    }
                }
                if (floorY < 0 || floorY + WALL_HEIGHT + 1 >= size) continue;

                // Build a partial wall outline (only edges of a rectangle)
                placeRuinWalls(data, lx, floorY, lz, size);
            }
        }
    }

    /**
     * Places a small rectangular ruin outline centered at (cx, floorY, cz).
     * Only the perimeter voxels are placed, and some are randomly omitted
     * (using coordinate hashing) to simulate erosion.
     */
    private void placeRuinWalls(VoxelData data, int cx, int floorY, int cz, int size) {
        for (int dz = -RUIN_HALF_SIZE; dz <= RUIN_HALF_SIZE; dz++) {
            for (int dx = -RUIN_HALF_SIZE; dx <= RUIN_HALF_SIZE; dx++) {
                // Only place on the perimeter
                boolean isEdge = Math.abs(dx) == RUIN_HALF_SIZE || Math.abs(dz) == RUIN_HALF_SIZE;
                if (!isEdge) continue;

                int px = cx + dx;
                int pz = cz + dz;
                if (px < 0 || px >= size || pz < 0 || pz >= size) continue;

                // Erosion: skip some columns via simple hash
                int hash = ((px * 73856093) ^ (pz * 19349663)) & 0x7FFFFFFF;
                int wallH = 1 + (hash % WALL_HEIGHT);

                VoxelType material = (hash % 3 == 0) ? VoxelType.CORAL : VoxelType.STONE;

                for (int dy = 1; dy <= wallH; dy++) {
                    int py = floorY + dy;
                    if (py >= size) break;
                    // Only overwrite water / air
                    VoxelType existing = data.getType(px, py, pz);
                    if (existing == VoxelType.AIR || existing == VoxelType.WATER) {
                        data.setType(px, py, pz, material);
                        data.setDensity(px, py, pz, 0.5f);
                    }
                }
            }
        }
    }
}