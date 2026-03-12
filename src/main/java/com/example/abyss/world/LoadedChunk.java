package com.example.abyss.world;

import com.example.abyss.world.meshing.ChunkMeshState;

/**
 * A loaded chunk and its render-side state.
 *
 * @param chunk the simulation/data chunk
 * @param renderState the meshing/render lifecycle state for that chunk
 */
public record LoadedChunk(VoxelChunk chunk, ChunkMeshState renderState) {
}
