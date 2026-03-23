package com.example.abyss.world.meshing;
import com.example.abyss.world.ChunkKey;
import com.example.abyss.world.VoxelChunk;
import java.util.function.Function;
/**
 * Strategy that decides whether all required neighbour chunks are available
 * before a meshing job can be submitted for a given chunk key.
 * <p>
 * The default full-resolution implementation checks that all six
 * face-adjacent neighbours at the same resolution are loaded. Future LOD
 * implementations can supply a different policy when mixed-detail
 * boundaries require different neighbour sets.
 * </p>
 */
@FunctionalInterface
public interface MeshingNeighbourPolicy {
    /**
     * Returns {@code true} if the required neighbours for {@code key} are
     * all available via {@code chunkLookup}.
     *
     * @param key         the chunk whose meshing readiness is being tested
     * @param chunkLookup function that returns a loaded {@link VoxelChunk} by key, or {@code null} if not loaded
     */
    boolean hasRequiredNeighbours(ChunkKey key, Function<ChunkKey, VoxelChunk> chunkLookup);
}
