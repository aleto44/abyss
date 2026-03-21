package com.example.abyss.debug.viewer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.example.abyss.debug.constants.FirstPersonWorldDebugConstants;
import com.example.abyss.world.AbyssWorld;
import com.example.abyss.world.LoadedChunk;
import com.example.abyss.world.VoxelChunk;
import com.example.abyss.world.render.ChunkCuller;

/**
 * Render helper for the first-person chunk debug world view.
 */
public class FirstPersonWorldRenderer {

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

        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        for (LoadedChunk loadedChunk : world.getChunkManager().iterateLoadedChunks()) {
            VoxelChunk chunk = loadedChunk.chunk();
            boolean isFrustumVisible = chunkCuller.isFrustumVisible(chunk, camera);
            if (!isFrustumVisible) {
                frustumCulled++;
                continue;
            }
            renderChunkMeshes(chunk, loadedChunk, camera, true);
        }

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        Gdx.gl.glDepthMask(false);
        for (LoadedChunk loadedChunk : world.getChunkManager().iterateLoadedChunks()) {
            VoxelChunk chunk = loadedChunk.chunk();
            boolean isFrustumVisible = chunkCuller.isFrustumVisible(chunk, camera);
            if (!isFrustumVisible) {
                continue;
            }
            renderChunkMeshes(chunk, loadedChunk, camera, false);
        }
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);

        lastFrustumCulledChunks = frustumCulled;
    }

    public void dispose() {
        shaderProgram.dispose();
    }

    private void renderChunkMeshes(VoxelChunk chunk, LoadedChunk loadedChunk, PerspectiveCamera camera, boolean opaquePass) {
        modelMatrix.idt().setTranslation(chunk.getWorldX(), chunk.getWorldY(), chunk.getWorldZ());
        modelViewProjectionMatrix.set(camera.combined).mul(modelMatrix);
        shaderProgram.setUniformMatrix("u_projTrans", modelViewProjectionMatrix);
        Iterable<Mesh> meshes = opaquePass
                ? loadedChunk.renderState().getOpaqueMeshes()
                : loadedChunk.renderState().getTransparentMeshes();
        for (Mesh mesh : meshes) {
            renderMesh(mesh);
        }
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
