package com.example.abyss.world.render;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.example.abyss.WorldConstants;

/**
 * Determines which render entries should be rendered from a given camera position
 * using frustum culling.
 */
public class ChunkCuller {

    /** Reused each frame to avoid per-chunk allocation during frustum tests. */
    private final BoundingBox chunkBounds = new BoundingBox();

    /**
     * Returns {@code true} if the render entry's AABB intersects the camera frustum.
     * Uses {@link WorldConstants#CHUNK_SIZE} as the bounding box size — suitable for
     * near-field chunk entries.
     */
    public boolean isFrustumVisible(RenderMeshSource entry, Camera camera) {
        float size = WorldConstants.CHUNK_SIZE;
        return isFrustumVisible(entry, camera, size, size, size);
    }

    /**
     * Returns {@code true} if the render entry's AABB intersects the camera frustum,
     * using explicit bounding box dimensions. Use this for entries whose size differs
     * from a single chunk (e.g. LOD proxy regions).
     */
    public boolean isFrustumVisible(RenderMeshSource entry, Camera camera,
                                    float sizeX, float sizeY, float sizeZ) {
        float wx = entry.worldX();
        float wy = entry.worldY();
        float wz = entry.worldZ();
        chunkBounds.set(
                new Vector3(wx, wy, wz),
                new Vector3(wx + sizeX, wy + sizeY, wz + sizeZ)
        );
        return camera.frustum.boundsInFrustum(chunkBounds);
    }
}