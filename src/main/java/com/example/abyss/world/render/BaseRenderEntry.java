package com.example.abyss.world.render;

import com.badlogic.gdx.graphics.Mesh;

import java.util.List;

/**
 * Shared immutable render entry fields for near-field and far-field geometry.
 * <p>
 * This keeps the common render-facing payload in one place while allowing
 * specialized entry types to add only their extra fields.
 * </p>
 */
public class BaseRenderEntry implements RenderMeshSource {

    private final float worldX;
    private final float worldY;
    private final float worldZ;
    private final List<Mesh> opaqueMeshes;
    private final List<Mesh> transparentMeshes;

    public BaseRenderEntry(
            float worldX,
            float worldY,
            float worldZ,
            List<Mesh> opaqueMeshes,
            List<Mesh> transparentMeshes
    ) {
        this.worldX = worldX;
        this.worldY = worldY;
        this.worldZ = worldZ;
        this.opaqueMeshes = opaqueMeshes;
        this.transparentMeshes = transparentMeshes;
    }

    @Override
    public final float worldX() {
        return worldX;
    }

    @Override
    public final float worldY() {
        return worldY;
    }

    @Override
    public final float worldZ() {
        return worldZ;
    }

    @Override
    public final List<Mesh> opaqueMeshes() {
        return opaqueMeshes;
    }

    @Override
    public final List<Mesh> transparentMeshes() {
        return transparentMeshes;
    }
}