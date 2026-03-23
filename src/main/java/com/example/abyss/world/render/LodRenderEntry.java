package com.example.abyss.world.render;

import com.badlogic.gdx.graphics.Mesh;

import java.util.List;

/**
 * Render-facing snapshot of a far-field proxy region.
 * <p>
 * This remains separate from {@link ChunkRenderEntry} so proxy ownership does
 * not blur into near-field chunk ownership.
 * </p>
 *
 * @param worldX world-space X origin
 * @param worldY world-space Y origin
 * @param worldZ world-space Z origin
 * @param lodLevel proxy detail level
 * @param opaqueMeshes opaque proxy meshes
 * @param transparentMeshes transparent proxy meshes
 */
public record LodRenderEntry(
        float worldX,
        float worldY,
        float worldZ,
        int lodLevel,
        List<Mesh> opaqueMeshes,
        List<Mesh> transparentMeshes
) {
}
