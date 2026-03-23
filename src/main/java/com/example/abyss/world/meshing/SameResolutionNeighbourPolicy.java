package com.example.abyss.world.meshing;

import com.example.abyss.world.ChunkKey;
import com.example.abyss.world.VoxelChunk;

import java.util.function.Function;

/**
 * The standard full-resolution neighbour readiness policy.
 * <p>
 * Requires that all six face-adjacent chunks at the same resolution are
 * loaded before meshing can start. This is the only correct policy for
 * the current single-resolution pipeline and should remain the default
 * until LOD introduces mixed-resolution boundaries.
 * </p>
 */
public final class SameResolutionNeighbourPolicy implements MeshingNeighbourPolicy {

    @Override
    public boolean hasRequiredNeighbours(ChunkKey key, Function<ChunkKey, VoxelChunk> chunkLookup) {
        boolean plusXLoaded  = chunkLookup.apply(new ChunkKey(key.cx() + 1, key.cy(),     key.cz()    )) != null;
        boolean minusXLoaded = chunkLookup.apply(new ChunkKey(key.cx() - 1, key.cy(),     key.cz()    )) != null;
        boolean plusYLoaded  = chunkLookup.apply(new ChunkKey(key.cx(),     key.cy() + 1, key.cz()    )) != null;
        boolean minusYLoaded = chunkLookup.apply(new ChunkKey(key.cx(),     key.cy() - 1, key.cz()    )) != null;
        boolean plusZLoaded  = chunkLookup.apply(new ChunkKey(key.cx(),     key.cy(),     key.cz() + 1)) != null;
        boolean minusZLoaded = chunkLookup.apply(new ChunkKey(key.cx(),     key.cy(),     key.cz() - 1)) != null;

        return plusXLoaded && minusXLoaded && plusYLoaded && minusYLoaded && plusZLoaded && minusZLoaded;
    }
}
