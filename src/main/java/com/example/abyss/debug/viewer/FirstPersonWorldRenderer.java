package com.example.abyss.debug.viewer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.example.abyss.WorldConstants;
import com.example.abyss.debug.constants.FirstPersonWorldDebugConstants;
import com.example.abyss.world.AbyssWorld;
import com.example.abyss.world.render.ChunkCuller;
import com.example.abyss.world.render.RenderMeshSource;
import com.example.abyss.world.render.WorldRenderSnapshot;
import com.example.abyss.world.render.distant.DistantTerrainConfig;

import java.util.List;
import java.util.function.Function;

/**
 * Render helper for the first-person chunk debug world view.
 */
public class FirstPersonWorldRenderer {

    private static final float[] TINT_NORMAL = { 1.0f, 1.0f, 1.0f };
    /** XZ radius of the cylindrical exclusion zone that hides distant terrain inside the near-field. */
    private static final float NEAR_FIELD_EXCLUSION_RADIUS =
            WorldConstants.NEAR_FIELD_H_RADIUS * WorldConstants.CHUNK_SIZE;
    /** Y half-extent of the cylindrical exclusion zone, matching the near-field vertical radius. */
    private static final float NEAR_FIELD_EXCLUSION_RADIUS_Y =
            WorldConstants.NEAR_FIELD_V_RADIUS * WorldConstants.CHUNK_SIZE;
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
        world.getDistantTerrainManager().uploadPendingMeshes(shaderProgram);

        WorldRenderSnapshot renderSnapshot = world.getRenderSnapshot();

        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);

        // --- Distant terrain opaque pass (background) ---
        // Fragments inside the near-field cylinder (XZ radius + Y half-extent) are discarded
        // so underground/all-air near-chunks that write no depth geometry don't let distant
        // terrain show through. Y-bounding ensures cliff faces below the player still render.
        shaderProgram.setUniformf("u_nearFieldCenter", camera.position.x, camera.position.z);
        shaderProgram.setUniformf("u_nearFieldCenterY", camera.position.y);
        shaderProgram.setUniformf("u_nearFieldRadius", NEAR_FIELD_EXCLUSION_RADIUS);
        shaderProgram.setUniformf("u_nearFieldRadiusY", NEAR_FIELD_EXCLUSION_RADIUS_Y);
        renderDistantTerrainPass(renderSnapshot, camera, RenderMeshSource::opaqueMeshes);

        // --- Near-field opaque pass (foreground override) ---
        // Disable the exclusion zone so near-field geometry is never discarded.
        shaderProgram.setUniformf("u_nearFieldRadius", 0.0f);
        for (RenderMeshSource entry : renderSnapshot.nearFieldEntries()) {
            boolean isFrustumVisible = chunkCuller.isFrustumVisible(entry, camera);
            if (!isFrustumVisible) {
                frustumCulled++;
                continue;
            }
            renderChunkMeshes(entry, camera, RenderMeshSource::opaqueMeshes, TINT_NORMAL);
        }

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        Gdx.gl.glDepthMask(false);

        // --- Distant terrain transparent pass (background) ---
        shaderProgram.setUniformf("u_nearFieldCenter", camera.position.x, camera.position.z);
        shaderProgram.setUniformf("u_nearFieldCenterY", camera.position.y);
        shaderProgram.setUniformf("u_nearFieldRadius", NEAR_FIELD_EXCLUSION_RADIUS);
        shaderProgram.setUniformf("u_nearFieldRadiusY", NEAR_FIELD_EXCLUSION_RADIUS_Y);
        renderDistantTerrainPass(renderSnapshot, camera, RenderMeshSource::transparentMeshes);

        // --- Near-field transparent pass (foreground override) ---
        shaderProgram.setUniformf("u_nearFieldRadius", 0.0f);
        for (RenderMeshSource entry : renderSnapshot.nearFieldEntries()) {
            boolean isFrustumVisible = chunkCuller.isFrustumVisible(entry, camera);
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

    private void renderDistantTerrainPass(WorldRenderSnapshot renderSnapshot, PerspectiveCamera camera,
                                          Function<RenderMeshSource, List<Mesh>> meshSelector) {
        // Push distant geometry slightly away from the camera in clip space so near
        // chunks always win the depth test at the LOD transition boundary.
        Gdx.gl.glEnable(GL20.GL_POLYGON_OFFSET_FILL);
        Gdx.gl.glPolygonOffset(1.0f, 1.0f);

        float regionSize = DistantTerrainConfig.MACRO_CHUNK_SIZE_VOXELS;
        for (RenderMeshSource entry : renderSnapshot.distantTerrainEntries()) {
            boolean isFrustumVisible = chunkCuller.isFrustumVisible(entry, camera,
                    regionSize, regionSize, regionSize);
            if (!isFrustumVisible) {
                continue;
            }
            renderChunkMeshes(entry, camera, meshSelector, TINT_NORMAL);
        }

        Gdx.gl.glPolygonOffset(0.0f, 0.0f);
        Gdx.gl.glDisable(GL20.GL_POLYGON_OFFSET_FILL);
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
        shaderProgram.setUniformf("u_modelOriginXZ", entry.worldX(), entry.worldZ());
        shaderProgram.setUniformf("u_modelOriginY", entry.worldY());
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
