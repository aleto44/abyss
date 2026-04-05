package com.example.abyss.debug.viewer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.example.abyss.debug.constants.FirstPersonWorldDebugConstants;
import com.example.abyss.world.AbyssWorld;
import com.example.abyss.world.meshing.MeshData;
import com.example.abyss.world.meshing.MeshSectionData;
import com.example.abyss.world.render.ChunkCuller;
import com.example.abyss.world.render.LodRenderEntry;
import com.example.abyss.world.render.RenderMeshSource;
import com.example.abyss.world.render.WorldRenderSnapshot;
import com.example.abyss.world.render.lod.LodConfig;
import com.example.abyss.world.render.lod.LodProxyMeshState;
import com.example.abyss.world.render.lod.LodProxyRegion;
import com.example.abyss.world.render.lod.LodTierConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Render helper for the first-person chunk debug world view.
 */
public class FirstPersonWorldRenderer {

    private static final float[] TINT_NORMAL = { 1.0f, 1.0f, 1.0f };
    private final Matrix4 modelMatrix = new Matrix4();
    private final Matrix4 modelViewProjectionMatrix = new Matrix4();
    private final ChunkCuller chunkCuller = new ChunkCuller();
    private final ShaderProgram shaderProgram;

    private int lastFrustumCulledChunks;

    public FirstPersonWorldRenderer() {
        shaderProgram = buildShaderProgram();
    }

    public ShaderProgram getShaderProgram() {
        return shaderProgram;
    }

    public int getLastFrustumCulledChunks() {
        return lastFrustumCulledChunks;
    }

    public void renderWorld(AbyssWorld world, PerspectiveCamera camera) {
        shaderProgram.bind();
        int frustumCulled = 0;

        // Upload any proxy mesh data that arrived this frame before taking the snapshot,
        // so the snapshot captures the freshly-uploaded meshes rather than empty lists.
        world.getLodProxyManager().uploadPendingMeshes(shaderProgram);

        WorldRenderSnapshot renderSnapshot = world.getRenderSnapshot();

        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);

        // --- Near-field opaque pass ---
        for (RenderMeshSource entry : renderSnapshot.nearFieldEntries()) {
            boolean isFrustumVisible = chunkCuller.isFrustumVisible(entry, camera);
            if (!isFrustumVisible) {
                frustumCulled++;
                continue;
            }
            renderChunkMeshes(entry, camera, RenderMeshSource::opaqueMeshes, TINT_NORMAL);
        }

        // --- Far-field opaque pass ---
        for (LodRenderEntry entry : renderSnapshot.farFieldEntries()) {
            LodTierConfig tier = LodConfig.TIERS.get(entry.lodLevel());
            float regionSize = tier.regionSizeVoxels();
            boolean isFrustumVisible = chunkCuller.isFrustumVisible(entry, camera,
                    regionSize, regionSize, regionSize);
            if (!isFrustumVisible) {
                continue;
            }
            renderChunkMeshes(entry, camera, RenderMeshSource::opaqueMeshes, TINT_NORMAL);
        }

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        Gdx.gl.glDepthMask(false);

        // --- Near-field transparent pass ---
        for (RenderMeshSource entry : renderSnapshot.nearFieldEntries()) {
            boolean isFrustumVisible = chunkCuller.isFrustumVisible(entry, camera);
            if (!isFrustumVisible) {
                continue;
            }
            renderChunkMeshes(entry, camera, RenderMeshSource::transparentMeshes, TINT_NORMAL);
        }

        // --- Far-field transparent pass ---
        for (LodRenderEntry entry : renderSnapshot.farFieldEntries()) {
            LodTierConfig tier = LodConfig.TIERS.get(entry.lodLevel());
            float regionSize = tier.regionSizeVoxels();
            boolean isFrustumVisible = chunkCuller.isFrustumVisible(entry, camera,
                    regionSize, regionSize, regionSize);
            if (!isFrustumVisible) {
                continue;
            }
            renderChunkMeshes(entry, camera, RenderMeshSource::transparentMeshes, TINT_NORMAL);
        }

        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);

        lastFrustumCulledChunks = frustumCulled;
    }

    public void dispose() {
        shaderProgram.dispose();
    }

    private void renderChunkMeshes(RenderMeshSource entry, PerspectiveCamera camera,
                                   Function<RenderMeshSource, List<Mesh>> meshSelector,
                                   float[] tint) {
        setChunkTransform(entry, camera, tint);
        for (Mesh mesh : meshSelector.apply(entry)) {
            renderMesh(mesh);
        }
    }

    private void setChunkTransform(RenderMeshSource entry, PerspectiveCamera camera, float[] tint) {
        modelMatrix.idt().setTranslation(entry.worldX(), entry.worldY(), entry.worldZ());
        modelViewProjectionMatrix.set(camera.combined).mul(modelMatrix);
        shaderProgram.setUniformMatrix("u_projTrans", modelViewProjectionMatrix);
        shaderProgram.setUniform3fv("u_lodTint", tint, 0, 3);
    }

    private void renderMesh(Mesh mesh) {
        boolean isMissingGeometry = mesh == null || mesh.getNumIndices() == 0;
        if (isMissingGeometry) {
            return;
        }
        mesh.render(shaderProgram, GL20.GL_TRIANGLES);
    }

    private static ShaderProgram buildShaderProgram() {
        ShaderProgram program = new ShaderProgram(
                FirstPersonWorldDebugConstants.VERTEX_SHADER_SOURCE,
                FirstPersonWorldDebugConstants.FRAGMENT_SHADER_SOURCE
        );
        boolean isCompiled = program.isCompiled();
        if (!isCompiled) {
            throw new IllegalStateException("Failed to compile first-person debug shader: " + program.getLog());
        }
        return program;
    }
}