package com.example.abyss.world.render.distant;

import com.badlogic.gdx.graphics.Mesh;
import com.example.abyss.world.render.BaseRenderEntry;

import java.util.List;

/**
 * Render-facing snapshot of a distant terrain background region.
 * <p>
 * Kept separate from {@link com.example.abyss.world.render.BaseRenderEntry} so the renderer
 * can select the distant terrain draw path independently of near-field chunks.
 * </p>
 */
public final class DistantRenderEntry extends BaseRenderEntry {

    public DistantRenderEntry(
            float worldX,
            float worldY,
            float worldZ,
            List<Mesh> opaqueMeshes,
            List<Mesh> transparentMeshes
    ) {
        super(worldX, worldY, worldZ, opaqueMeshes, transparentMeshes);
    }
}
