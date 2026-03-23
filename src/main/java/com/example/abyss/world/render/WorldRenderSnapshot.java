package com.example.abyss.world.render;

import java.util.List;

/**
 * Render-facing snapshot of world geometry sources.
 * <p>
 * Near-field and far-field entries remain separate so each system can keep
 * clear ownership while sharing one renderer entry point.
 * </p>
 *
 * @param nearFieldEntries renderable near-field chunk entries
 * @param farFieldEntries renderable far-field proxy entries
 */
public record WorldRenderSnapshot(
        List<BaseRenderEntry> nearFieldEntries,
        List<LodRenderEntry> farFieldEntries
) {
}