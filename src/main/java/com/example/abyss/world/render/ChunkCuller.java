package com.example.abyss.world.render;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.example.abyss.world.VoxelChunk;

/**
 * Determines which chunks should be rendered from a given camera position
 * using frustum culling.
 */
public class ChunkCuller {

    /** Reused each frame to avoid per-chunk allocation during frustum tests. */
    private final BoundingBox chunkBounds = new BoundingBox();

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
}