package com.example.abyss.world.render;

import com.example.abyss.world.render.distant.DistantRenderEntry;

import java.util.List;

/**
 * Render-facing snapshot of world geometry sources.
 * <p>
 * Near-field and distant terrain entries remain separate so each system keeps
 * clear ownership while sharing one renderer entry point. The renderer draws
 * distant terrain first so near-field chunks occlude it via the depth buffer.
 * </p>
 *
 * @param nearFieldEntries      renderable near-field chunk entries
 * @param distantTerrainEntries renderable distant terrain background entries
 */
public record WorldRenderSnapshot(
        List<BaseRenderEntry> nearFieldEntries,
        List<DistantRenderEntry> distantTerrainEntries
) {
}