package com.example.abyss.world.render;

import com.badlogic.gdx.graphics.Mesh;

import java.util.List;

/**
 * Read-only render contract shared by near-field chunks and far-field proxies.
 * <p>
 * Render callers can use this interface when they only need world position and
 * mesh lists and should not care about ownership or simulation details.
 * </p>
 */
public interface RenderMeshSource {

    float worldX();

    float worldY();

    float worldZ();

    List<Mesh> opaqueMeshes();

    List<Mesh> transparentMeshes();
}
