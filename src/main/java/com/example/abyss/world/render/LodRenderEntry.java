package com.example.abyss.world.render;

import com.badlogic.gdx.graphics.Mesh;

import java.util.List;

/**
 * Render-facing snapshot of a far-field proxy region.
 * <p>
 * This remains separate from the near-field base render entry so proxy
 * ownership does not blur into near-field chunk ownership.
 * </p>
 */
public final class LodRenderEntry extends BaseRenderEntry {

    private final int lodLevel;

    public LodRenderEntry(
            float worldX,
            float worldY,
            float worldZ,
            int lodLevel,
            List<Mesh> opaqueMeshes,
            List<Mesh> transparentMeshes
    ) {
        super(worldX, worldY, worldZ, opaqueMeshes, transparentMeshes);
        this.lodLevel = lodLevel;
    }

    public int lodLevel() {
        return lodLevel;
    }
}