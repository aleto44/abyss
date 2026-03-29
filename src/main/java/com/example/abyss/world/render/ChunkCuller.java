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

    /**
     * Outward expansion (in voxels) applied to every bounding box before the frustum test.
     * <p>
     * At high world-Y positions the frustum plane equations operate on large floating-point
     * values, which can cause small precision errors that make chunks right at the frustum
     * boundary flicker in and out as the camera rotates. Expanding the AABB by a small
     * constant before the test ensures those boundary chunks are conservatively kept visible,
     * eliminating the pop-in/pop-out artefact. The cost is a tiny amount of over-rendering
     * for chunks just outside the true frustum edge, which is imperceptible.
     * </p>
     */
    private static final float FRUSTUM_BIAS = 2.0f;

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
                new Vector3(wx - FRUSTUM_BIAS, wy - FRUSTUM_BIAS, wz - FRUSTUM_BIAS),
                new Vector3(wx + sizeX + FRUSTUM_BIAS, wy + sizeY + FRUSTUM_BIAS, wz + sizeZ + FRUSTUM_BIAS)
        );
        return camera.frustum.boundsInFrustum(chunkBounds);
    }
}