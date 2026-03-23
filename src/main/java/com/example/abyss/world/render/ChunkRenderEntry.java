package com.example.abyss.world.render;

import com.badlogic.gdx.graphics.Mesh;

import java.util.List;

/**
 * A render-facing snapshot of a single renderable chunk unit.
 * <p>
 * Carries everything the renderer and frustum culler need without exposing
 * {@code VoxelChunk} or {@code LoadedChunk} storage internals. Future LOD
 * proxy meshes can supply the same interface without pretending to be full
 * chunk data.
 * </p>
 *
 * @param worldX        world-space X origin of the renderable unit
 * @param worldY        world-space Y origin of the renderable unit
 * @param worldZ        world-space Z origin of the renderable unit
 * @param opaqueMeshes  fully opaque geometry sections
 * @param transparentMeshes translucent/transparent geometry sections
 */
public record ChunkRenderEntry(
        float worldX,
        float worldY,
        float worldZ,
        List<Mesh> opaqueMeshes,
        List<Mesh> transparentMeshes
) {
}
