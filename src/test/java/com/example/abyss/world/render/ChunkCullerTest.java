package com.example.abyss.world.render;

import com.example.abyss.world.render.distant.DistantRenderEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class ChunkCullerTest {

    @Test
    void nearFieldRenderEntriesExposeTheSharedRenderMeshSourceContract() {
        List<com.badlogic.gdx.graphics.Mesh> opaqueMeshes = List.of();
        List<com.badlogic.gdx.graphics.Mesh> transparentMeshes = List.of();
        RenderMeshSource entry = new BaseRenderEntry(1.0f, 2.0f, 3.0f, opaqueMeshes, transparentMeshes);

        assertInstanceOf(BaseRenderEntry.class, entry);
        assertEquals(1.0f, entry.worldX());
        assertEquals(2.0f, entry.worldY());
        assertEquals(3.0f, entry.worldZ());
        assertSame(opaqueMeshes, entry.opaqueMeshes());
        assertSame(transparentMeshes, entry.transparentMeshes());
    }

    @Test
    void distantTerrainRenderEntriesExposeTheSharedRenderMeshSourceContract() {
        List<com.badlogic.gdx.graphics.Mesh> opaqueMeshes = List.of();
        List<com.badlogic.gdx.graphics.Mesh> transparentMeshes = List.of();
        RenderMeshSource entry = new DistantRenderEntry(4.0f, 5.0f, 6.0f, opaqueMeshes, transparentMeshes);

        assertInstanceOf(BaseRenderEntry.class, entry);
        assertEquals(4.0f, entry.worldX());
        assertEquals(5.0f, entry.worldY());
        assertEquals(6.0f, entry.worldZ());
        assertSame(opaqueMeshes, entry.opaqueMeshes());
        assertSame(transparentMeshes, entry.transparentMeshes());
    }
}