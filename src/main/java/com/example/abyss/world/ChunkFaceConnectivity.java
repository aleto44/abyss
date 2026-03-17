package com.example.abyss.world;

import com.example.abyss.world.voxel.VoxelType;

/**
 * Computes and queries the face-connectivity bitmask for a single chunk.
 * <p>
 * A chunk has 6 faces (indexed 0–5, matching the order +X, -X, +Y, -Y, +Z, -Z).
 * There are 15 unique unordered pairs of those faces.  For each pair (A, B) a bit
 * is set when at least one connected region of non-solid (air/transparent) voxels
 * inside the chunk touches both face A and face B.
 * </p>
 * <p>
 * The bitmask is stored in a {@code short} (15 bits used, bit 15 always 0).
 * Pair encoding: pair(a, b) where a &lt; b maps to index a*5 – a*(a-1)/2 + (b-a-1).
 * See {@link #pairBit(int, int)}.
 * </p>
 * <p>
 * Usage:
 * <pre>
 *   short mask = ChunkFaceConnectivity.compute(chunk);
 *   chunk.setFaceConnectivity(mask);
 *   // later, when traversing the chunk graph:
 *   boolean canPass = ChunkFaceConnectivity.canSeeThrough(mask, entryFace, exitFace);
 * </pre>
 * </p>
 */
public final class ChunkFaceConnectivity {

    // Face index constants — must match the order used in ChunkMesher.FACE_NORMALS.
    public static final int FACE_POS_X = 0;
    public static final int FACE_NEG_X = 1;
    public static final int FACE_POS_Y = 2;
    public static final int FACE_NEG_Y = 3;
    public static final int FACE_POS_Z = 4;
    public static final int FACE_NEG_Z = 5;

    /** The six face directions as (dx, dy, dz) deltas, indexed by the FACE_* constants above. */
    private static final int[][] NEIGHBOUR_DELTAS = {
            { 1,  0,  0},  // +X
            {-1,  0,  0},  // -X
            { 0,  1,  0},  // +Y
            { 0, -1,  0},  // -Y
            { 0,  0,  1},  // +Z
            { 0,  0, -1},  // -Z
    };

    /**
     * A fully open mask: all 15 face-pair bits set.
     * Used when a chunk is not yet loaded so the BFS treats it as passable.
     */
    public static final short FULLY_OPEN = (short) 0x7FFF;

    private ChunkFaceConnectivity() {}

    // -------------------------------------------------------------------------
    // Computation
    // -------------------------------------------------------------------------

    /**
     * Runs a flood-fill over the chunk's non-solid voxels and returns the
     * packed 15-bit face-connectivity bitmask.
     * <p>
     * Safe to call from a background meshing thread — reads only immutable
     * voxel data via {@link VoxelChunk#getVoxel}.
     * </p>
     */
    public static short compute(VoxelChunk chunk) {
        int size = VoxelChunk.SIZE;
        boolean[] visited = new boolean[size * size * size];
        short connectivity = 0;

        for (int startY = 0; startY < size; startY++) {
            for (int startZ = 0; startZ < size; startZ++) {
                for (int startX = 0; startX < size; startX++) {
                    int startIdx = indexOf(startX, startY, startZ);
                    boolean alreadyVisited = visited[startIdx];
                    boolean isPassable = isPassable(chunk.getVoxel(startX, startY, startZ));
                    if (alreadyVisited || !isPassable) {
                        continue;
                    }

                    // BFS over this connected air/transparent region.
                    int touchedFaces = floodFill(chunk, startX, startY, startZ, visited);

                    // Every pair of touched faces gets its connectivity bit set.
                    for (int a = 0; a < 6; a++) {
                        boolean touchesA = (touchedFaces & (1 << a)) != 0;
                        if (!touchesA) {
                            continue;
                        }
                        for (int b = a + 1; b < 6; b++) {
                            boolean touchesB = (touchedFaces & (1 << b)) != 0;
                            if (touchesB) {
                                connectivity |= pairBit(a, b);
                            }
                        }
                    }
                }
            }
        }

        return connectivity;
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the connectivity mask indicates that a ray entering
     * through {@code entryFace} can exit through {@code exitFace}.
     * The relationship is symmetric — entry/exit order does not matter.
     */
    public static boolean canSeeThrough(short mask, int entryFace, int exitFace) {
        if (entryFace == exitFace) {
            return true;
        }
        int a = Math.min(entryFace, exitFace);
        int b = Math.max(entryFace, exitFace);
        return (mask & pairBit(a, b)) != 0;
    }

    /**
     * Returns the face index that is the opposite of the given face
     * (i.e. the face on the neighbour chunk that points back toward us).
     * +X(0) ↔ -X(1), +Y(2) ↔ -Y(3), +Z(4) ↔ -Z(5).
     */
    public static int oppositeFace(int face) {
        return face ^ 1;
    }

    /**
     * Returns the face index of chunk A that points toward its neighbour chunk B,
     * given the delta (B - A) in chunk-space coordinates.
     * Only unit-distance axis-aligned deltas are valid inputs.
     */
    public static int faceToward(int dcx, int dcy, int dcz) {
        if (dcx == 1)  { return FACE_POS_X; }
        if (dcx == -1) { return FACE_NEG_X; }
        if (dcy == 1)  { return FACE_POS_Y; }
        if (dcy == -1) { return FACE_NEG_Y; }
        if (dcz == 1)  { return FACE_POS_Z; }
        return FACE_NEG_Z;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * BFS from (startX, startY, startZ) through passable voxels, marking visited.
     * Returns a 6-bit int where bit i is set if the region touched face i.
     */
    private static int floodFill(VoxelChunk chunk, int startX, int startY, int startZ,
                                 boolean[] visited) {
        int size = VoxelChunk.SIZE;
        int touchedFaces = 0;

        // Simple iterative BFS using an int[] stack encoded as flat indices.
        int[] stack = new int[size * size * size];
        int stackTop = 0;

        int startIdx = indexOf(startX, startY, startZ);
        stack[stackTop++] = startIdx;
        visited[startIdx] = true;

        while (stackTop > 0) {
            int idx = stack[--stackTop];
            int x = idx % size;
            int z = (idx / size) % size;
            int y = idx / (size * size);

            touchedFaces |= faceTouchMask(x, y, z, size);

            for (int[] delta : NEIGHBOUR_DELTAS) {
                int nx = x + delta[0];
                int ny = y + delta[1];
                int nz = z + delta[2];

                boolean outOfBounds = nx < 0 || ny < 0 || nz < 0 || nx >= size || ny >= size || nz >= size;
                if (outOfBounds) {
                    continue;
                }

                int nIdx = indexOf(nx, ny, nz);
                if (visited[nIdx]) {
                    continue;
                }

                boolean neighbourPassable = isPassable(chunk.getVoxel(nx, ny, nz));
                if (!neighbourPassable) {
                    continue;
                }

                visited[nIdx] = true;
                stack[stackTop++] = nIdx;
            }
        }

        return touchedFaces;
    }

    /**
     * Returns a 6-bit mask of which chunk faces the voxel at (x, y, z) sits on.
     * A voxel can touch up to three faces (e.g. a corner voxel).
     */
    private static int faceTouchMask(int x, int y, int z, int size) {
        int mask = 0;
        if (x == size - 1) { mask |= (1 << FACE_POS_X); }
        if (x == 0)        { mask |= (1 << FACE_NEG_X); }
        if (y == size - 1) { mask |= (1 << FACE_POS_Y); }
        if (y == 0)        { mask |= (1 << FACE_NEG_Y); }
        if (z == size - 1) { mask |= (1 << FACE_POS_Z); }
        if (z == 0)        { mask |= (1 << FACE_NEG_Z); }
        return mask;
    }

    /**
     * A voxel is passable (air can flow through it) when it is null (unset/air),
     * not solid, or transparent but not opaque-solid.
     * Transparent solids like vines still let light through visually, but for
     * the purposes of occlusion culling we treat them as passable too — they do
     * not form hard opaque walls.
     */
    private static boolean isPassable(VoxelType type) {
        if (type == null) {
            return true;
        }
        boolean opaqueAndSolid = type.isSolid() && !type.isTransparent();
        return !opaqueAndSolid;
    }

    /** Flat index into the visited/stack arrays: y*size² + z*size + x. */
    private static int indexOf(int x, int y, int z) {
        int size = VoxelChunk.SIZE;
        return y * size * size + z * size + x;
    }

    /**
     * Returns the bitmask for the unordered pair (a, b) where a &lt; b.
     * Packed into 15 bits:
     * <pre>
     *   a=0: bits 0-4  (pairs with 1,2,3,4,5)
     *   a=1: bits 5-8  (pairs with 2,3,4,5)
     *   a=2: bits 9-11 (pairs with 3,4,5)
     *   a=3: bits 12-13(pairs with 4,5)
     *   a=4: bit 14    (pair with 5)
     * </pre>
     */
    static int pairBit(int a, int b) {
        // offset = sum of (5, 4, 3, 2, 1) for a = 0..a-1 = a*(9-a)/2
        int offset = a * (9 - a) / 2;
        return 1 << (offset + (b - a - 1));
    }
}
