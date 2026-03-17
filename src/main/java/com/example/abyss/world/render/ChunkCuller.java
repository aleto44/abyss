package com.example.abyss.world.render;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.example.abyss.WorldConstants;
import com.example.abyss.world.ChunkKey;
import com.example.abyss.world.ChunkManager;
import com.example.abyss.world.ChunkVisibilityGraph;
import com.example.abyss.world.VoxelChunk;

import java.util.Collections;
import java.util.Set;

/**
 * Determines which chunks should be rendered from a given camera position.
 * <p>
 * Two culling stages are applied in order:
 * <ol>
 *   <li><b>Frustum culling</b> — rejects chunks whose AABB lies entirely outside
 *       the camera frustum.</li>
 *   <li><b>Connectivity culling</b> — rejects chunks that are not reachable from
 *       the observer's chunk through open face connections (i.e. separated by a
 *       solid wall), as computed by {@link ChunkVisibilityGraph}.</li>
 * </ol>
 * Call {@link #rebuild} once per frame before testing any chunks.
 * </p>
 */
public class ChunkCuller {

    private final ChunkManager chunkManager;
    private final int hRadius;
    private final int vRadius;

    /** Reused each frame to avoid per-chunk allocation during frustum tests. */
    private final BoundingBox chunkBounds = new BoundingBox();

    /** Reachable chunk set produced by the last {@link #rebuild} call. */
    private Set<ChunkKey> visibleChunkKeys = Collections.emptySet();

    public ChunkCuller(ChunkManager chunkManager, int hRadius, int vRadius) {
        this.chunkManager = chunkManager;
        this.hRadius = hRadius;
        this.vRadius = vRadius;
    }

    /**
     * Rebuilds the connectivity-reachability set from the observer's current chunk.
     * Must be called once per frame before any calls to {@link #isFrustumVisible}
     * or {@link #isConnectivityVisible}.
     *
     * @param observerPosition world-space position of the observer (e.g. camera or player)
     */
    public void rebuild(Vector3 observerPosition) {
        int cx = Math.floorDiv((int) observerPosition.x, WorldConstants.CHUNK_SIZE);
        int cy = Math.floorDiv((int) observerPosition.y, WorldConstants.CHUNK_SIZE);
        int cz = Math.floorDiv((int) observerPosition.z, WorldConstants.CHUNK_SIZE);
        visibleChunkKeys = ChunkVisibilityGraph.computeVisible(
                cx, cy, cz,
                hRadius, vRadius,
                chunkManager::getChunk
        );
    }

    /**
     * Returns {@code true} if the chunk's AABB intersects the camera frustum.
     *
     * @param chunk  the chunk to test
     * @param camera the camera whose frustum is used for the test
     */
    public boolean isFrustumVisible(VoxelChunk chunk, Camera camera) {
        float wx = chunk.getWorldX();
        float wy = chunk.getWorldY();
        float wz = chunk.getWorldZ();
        float size = VoxelChunk.SIZE;
        chunkBounds.set(
                new Vector3(wx, wy, wz),
                new Vector3(wx + size, wy + size, wz + size)
        );
        return camera.frustum.boundsInFrustum(chunkBounds);
    }

    /**
     * Returns {@code true} if the chunk is reachable from the observer through
     * open face connections, as determined by the last {@link #rebuild} call.
     *
     * @param chunk the chunk to test
     */
    public boolean isConnectivityVisible(VoxelChunk chunk) {
        ChunkKey key = new ChunkKey(chunk.getChunkX(), chunk.getChunkY(), chunk.getChunkZ());
        return visibleChunkKeys.contains(key);
    }
}
