package com.example.abyss.world;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * BFS over the loaded chunk graph using per-chunk face-connectivity masks.
 * <p>
 * Starting from the chunk that contains the player, it propagates outward
 * through neighbours only when the connecting pair of faces has an open
 * air corridor (as encoded in {@link ChunkFaceConnectivity}).  This culls
 * chunks that are separated from the player by a solid wall even when they
 * are inside the view frustum and within the load radius.
 * </p>
 * <p>
 * Chunks that are not yet loaded are treated as fully open (passable) so
 * that newly arriving chunks are not permanently blocked.
 * </p>
 */
public final class ChunkVisibilityGraph {

    private ChunkVisibilityGraph() {}

    /**
     * Computes the set of chunk keys reachable from the player's chunk via
     * open face connections.
     *
     * @param playerCx       chunk-space X of the player
     * @param playerCy       chunk-space Y of the player
     * @param playerCz       chunk-space Z of the player
     * @param hRadius        horizontal search radius in chunks (inclusive)
     * @param vRadius        vertical search radius in chunks (inclusive)
     * @param chunkLookup    returns a loaded {@link VoxelChunk} or {@code null} if not loaded
     * @return immutable set of all reachable chunk keys within the search bounds
     */
    public static Set<ChunkKey> computeVisible(int playerCx, int playerCy, int playerCz,
                                               int hRadius, int vRadius,
                                               Function<ChunkKey, VoxelChunk> chunkLookup) {
        ChunkKey origin = new ChunkKey(playerCx, playerCy, playerCz);
        Set<ChunkKey> visited = new HashSet<>();
        Deque<BfsEntry> queue = new ArrayDeque<>();

        // The player's own chunk is always reachable regardless of its connectivity.
        visited.add(origin);
        queue.add(new BfsEntry(origin, ChunkFaceConnectivity.FULLY_OPEN, -1));

        while (!queue.isEmpty()) {
            BfsEntry current = queue.poll();
            ChunkKey key = current.key();
            short currentMask = current.connectivity();
            int entryFace = current.entryFace();

            for (int face = 0; face < 6; face++) {
                boolean canExitThroughFace = canExitThisFace(currentMask, entryFace, face);
                if (!canExitThroughFace) {
                    continue;
                }

                ChunkKey neighbourKey = neighbourKey(key, face);

                boolean withinBounds = isWithinBounds(neighbourKey, playerCx, playerCy, playerCz, hRadius, vRadius);
                if (!withinBounds) {
                    continue;
                }

                boolean alreadyVisited = visited.contains(neighbourKey);
                if (alreadyVisited) {
                    continue;
                }

                visited.add(neighbourKey);

                VoxelChunk neighbourChunk = chunkLookup.apply(neighbourKey);
                short neighbourMask = (neighbourChunk != null)
                        ? neighbourChunk.getFaceConnectivity()
                        : ChunkFaceConnectivity.FULLY_OPEN;

                int entryFaceIntoNeighbour = ChunkFaceConnectivity.oppositeFace(face);

                queue.add(new BfsEntry(neighbourKey, neighbourMask, entryFaceIntoNeighbour));
            }
        }

        return Collections.unmodifiableSet(visited);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true if the BFS is allowed to exit through {@code exitFace} given
     * we entered via {@code entryFace} and the chunk has the given connectivity mask.
     * The first chunk in the BFS has no entry face (-1) so all exits are allowed.
     */
    private static boolean canExitThisFace(short mask, int entryFace, int exitFace) {
        boolean isOriginChunk = entryFace == -1;
        if (isOriginChunk) {
            return true;
        }
        return ChunkFaceConnectivity.canSeeThrough(mask, entryFace, exitFace);
    }

    /** Returns the ChunkKey of the immediate neighbour in the given face direction. */
    private static ChunkKey neighbourKey(ChunkKey key, int face) {
        return switch (face) {
            case ChunkFaceConnectivity.FACE_POS_X -> new ChunkKey(key.cx() + 1, key.cy(),     key.cz());
            case ChunkFaceConnectivity.FACE_NEG_X -> new ChunkKey(key.cx() - 1, key.cy(),     key.cz());
            case ChunkFaceConnectivity.FACE_POS_Y -> new ChunkKey(key.cx(),     key.cy() + 1, key.cz());
            case ChunkFaceConnectivity.FACE_NEG_Y -> new ChunkKey(key.cx(),     key.cy() - 1, key.cz());
            case ChunkFaceConnectivity.FACE_POS_Z -> new ChunkKey(key.cx(),     key.cy(),     key.cz() + 1);
            default                               -> new ChunkKey(key.cx(),     key.cy(),     key.cz() - 1);
        };
    }

    private static boolean isWithinBounds(ChunkKey key,
                                          int playerCx, int playerCy, int playerCz,
                                          int hRadius, int vRadius) {
        boolean withinHorizontal = Math.abs(key.cx() - playerCx) <= hRadius
                && Math.abs(key.cz() - playerCz) <= hRadius;
        boolean withinVertical = Math.abs(key.cy() - playerCy) <= vRadius;
        return withinHorizontal && withinVertical;
    }

    // -------------------------------------------------------------------------
    // BFS node
    // -------------------------------------------------------------------------

    /**
     * Carries the chunk key, its connectivity mask, and the face we entered through
     * so we can test which exit faces are permitted.
     */
    private record BfsEntry(ChunkKey key, short connectivity, int entryFace) {}
}
