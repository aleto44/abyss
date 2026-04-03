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
import com.example.abyss.world.render.lod.LodProxyGenerator;
import com.example.abyss.world.render.lod.LodProxyMeshState;
import com.example.abyss.world.render.lod.LodProxyRegion;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Render helper for the first-person chunk debug world view.
 */
public class FirstPersonWorldRenderer {

    private static final float[] TINT_NORMAL = { 1.0f, 1.0f, 1.0f };
    private static final float[] TINT_LOD    = { 0.72f, 0.85f, 1.0f };

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
        uploadPendingProxyMeshes(world);

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
            boolean isFrustumVisible = chunkCuller.isFrustumVisible(entry, camera,
                    LodProxyGenerator.REGION_VOXELS_H,
                    LodProxyGenerator.REGION_VOXELS_H,
                    LodProxyGenerator.REGION_VOXELS_H);
            if (!isFrustumVisible) {
                continue;
            }
            renderChunkMeshes(entry, camera, RenderMeshSource::opaqueMeshes, TINT_LOD);
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
            boolean isFrustumVisible = chunkCuller.isFrustumVisible(entry, camera,
                    LodProxyGenerator.REGION_VOXELS_H,
                    LodProxyGenerator.REGION_VOXELS_H,
                    LodProxyGenerator.REGION_VOXELS_H);
            if (!isFrustumVisible) {
                continue;
            }
            renderChunkMeshes(entry, camera, RenderMeshSource::transparentMeshes, TINT_LOD);
        }

        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);

        lastFrustumCulledChunks = frustumCulled;
    }

    /**
     * Uploads any proxy {@link MeshData} that arrived via {@code pollCompletedMeshes()}
     * this frame. LibGDX {@link Mesh} objects must be created on the GL thread, so this
     * runs here rather than inside {@code LodProxyManager}.
     */
    private void uploadPendingProxyMeshes(AbyssWorld world) {
        for (LodProxyRegion region : world.getLodProxyManager().getLoadedRegionsForUpload()) {
            LodProxyMeshState meshState = region.meshState();
            MeshData pending = meshState.getPendingMeshData();
            boolean hasPendingData = pending != null;
            if (!hasPendingData) {
                continue;
            }
            List<Mesh> opaqueMeshes = buildMeshList(pending.opaqueSections());
            List<Mesh> transparentMeshes = buildMeshList(pending.transparentSections());
            meshState.setOpaqueMeshes(opaqueMeshes);
            meshState.setTransparentMeshes(transparentMeshes);
            meshState.setPendingMeshData(null);

            long approxBytes = estimateMeshBytes(pending);
            meshState.setApproximateMeshMemoryBytes(approxBytes);
        }
    }

    private List<Mesh> buildMeshList(List<MeshSectionData> sections) {
        List<Mesh> meshes = new ArrayList<>();
        for (MeshSectionData section : sections) {
            boolean isEmpty = section == null || section.isEmpty();
            if (isEmpty) {
                continue;
            }
            float[] vertices = section.vertices();
            short[] indices  = section.indices();
            Mesh mesh = new Mesh(true,
                    vertices.length / MeshData.FLOATS_PER_VERTEX,
                    indices.length,
                    VertexAttribute.Position(),
                    VertexAttribute.Normal(),
                    new VertexAttribute(VertexAttributes.Usage.ColorUnpacked, 4,
                            GL20.GL_FLOAT, false, "a_color"));
            mesh.setVertices(vertices);
            mesh.setIndices(indices);
            mesh.bind(shaderProgram);
            mesh.unbind(shaderProgram);
            meshes.add(mesh);
        }
        return meshes;
    }

    private static long estimateMeshBytes(MeshData data) {
        long bytes = 0L;
        for (MeshSectionData s : data.opaqueSections()) {
            bytes += (long) s.vertices().length * Float.BYTES + (long) s.indices().length * Short.BYTES;
        }
        for (MeshSectionData s : data.transparentSections()) {
            bytes += (long) s.vertices().length * Float.BYTES + (long) s.indices().length * Short.BYTES;
        }
        return bytes;
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