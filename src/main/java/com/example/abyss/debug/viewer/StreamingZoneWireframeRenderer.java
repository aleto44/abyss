package com.example.abyss.debug.viewer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.example.abyss.WorldConstants;
import com.example.abyss.world.render.CylinderStreamer;
import com.example.abyss.world.render.LodRenderEntry;
import com.example.abyss.world.render.WorldRenderSnapshot;
import com.example.abyss.world.render.lod.LodConfig;
import com.example.abyss.world.render.lod.LodTierConfig;

import java.util.List;

/**
 * Draws axis-aligned wireframe boxes for each streaming zone in the 3D debug view.
 * Toggle with {@code F3}.
 */
public class StreamingZoneWireframeRenderer {

    private static final float[] COLOR_NEAR_FIELD = { 1.0f, 0.2f, 0.9f, 0.85f };

    /** RGBA tints per LOD level index (0 = finest, higher = coarser). */
    private static final float[][] LOD_LEVEL_COLORS = {
        { 0.3f, 1.0f, 0.3f, 0.7f },  // level 0 — green
        { 1.0f, 0.9f, 0.1f, 0.7f },  // level 1 — yellow
        { 1.0f, 0.5f, 0.1f, 0.7f },  // level 2 — orange
        { 1.0f, 0.2f, 0.2f, 0.7f },  // level 3 — red
    };

    private static final String VERTEX_SHADER = """
            attribute vec3 a_position;
            uniform mat4 u_projTrans;
            uniform vec4 u_color;
            varying vec4 v_color;
            void main() {
                v_color = u_color;
                gl_Position = u_projTrans * vec4(a_position, 1.0);
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec4 v_color;
            void main() {
                gl_FragColor = v_color;
            }
            """;

    /** 12 edges × 2 vertices = 24 vertices per box, reused for every draw call. */
    private static final int BOX_VERTEX_COUNT = 24;
    private static final int FLOATS_PER_VERTEX = 3;

    private final ShaderProgram shader;
    private final Mesh boxMesh;
    private final Matrix4 mvp = new Matrix4();
    private final float[] vertexBuffer = new float[BOX_VERTEX_COUNT * FLOATS_PER_VERTEX];

    public StreamingZoneWireframeRenderer() {
        shader = buildShader();
        boxMesh = new Mesh(false, BOX_VERTEX_COUNT, 0,
                new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"));
    }

    /** Renders all streaming zone wireframes for the current frame. */
    public void render(PerspectiveCamera camera, CylinderStreamer cylinderStreamer,
                       WorldRenderSnapshot snapshot) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glDepthMask(false);

        shader.bind();

        renderNearFieldZone(camera, cylinderStreamer);
        renderAllTierBandZones(camera);
        renderLoadedRegions(camera, snapshot.farFieldEntries());

        Gdx.gl.glDepthMask(true);
        Gdx.gl.glEnable(GL20.GL_CULL_FACE);
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    public void dispose() {
        shader.dispose();
        boxMesh.dispose();
    }

    // ----------------------------------------------------------------
    // Near-field zone
    // ----------------------------------------------------------------

    private void renderNearFieldZone(PerspectiveCamera camera, CylinderStreamer cylinderStreamer) {
        float hExtent = cylinderStreamer.getHRadius() * WorldConstants.CHUNK_SIZE;
        float vExtent = cylinderStreamer.getVRadius() * WorldConstants.CHUNK_SIZE;
        float cx = camera.position.x;
        float cy = camera.position.y;
        float cz = camera.position.z;
        drawBox(camera, cx - hExtent, cy - vExtent, cz - hExtent,
                         cx + hExtent, cy + vExtent, cz + hExtent,
                         COLOR_NEAR_FIELD);
    }

    // ----------------------------------------------------------------
    // Tier band zones
    // ----------------------------------------------------------------

    private void renderAllTierBandZones(PerspectiveCamera camera) {
        float cx = camera.position.x;
        float cy = camera.position.y;
        float cz = camera.position.z;
        for (LodTierConfig tier : LodConfig.TIERS) {
            float hExtent = tier.outerHRadiusChunks() * WorldConstants.CHUNK_SIZE;
            float vExtent = tier.outerVRadiusChunks() * WorldConstants.CHUNK_SIZE;
            float[] color = LOD_LEVEL_COLORS[tier.lodLevel() % LOD_LEVEL_COLORS.length];
            drawBox(camera, cx - hExtent, cy - vExtent, cz - hExtent,
                             cx + hExtent, cy + vExtent, cz + hExtent, color);
        }
    }

    // ----------------------------------------------------------------
    // Loaded LOD regions
    // ----------------------------------------------------------------

    private void renderLoadedRegions(PerspectiveCamera camera, List<LodRenderEntry> farFieldEntries) {
        for (LodRenderEntry entry : farFieldEntries) {
            LodTierConfig tier = LodConfig.TIERS.get(entry.lodLevel());
            float regionSize = tier.regionSizeVoxels();
            float[] color = lodLevelColor(entry.lodLevel());
            drawBox(camera,
                    entry.worldX(),              entry.worldY(),              entry.worldZ(),
                    entry.worldX() + regionSize, entry.worldY() + regionSize, entry.worldZ() + regionSize,
                    color);
        }
    }

    private static float[] lodLevelColor(int level) {
        boolean isInRange = level >= 0 && level < LOD_LEVEL_COLORS.length;
        if (isInRange) {
            return LOD_LEVEL_COLORS[level];
        }
        return LOD_LEVEL_COLORS[LOD_LEVEL_COLORS.length - 1];
    }

    // ----------------------------------------------------------------
    // Drawing
    // ----------------------------------------------------------------

    private void drawBox(PerspectiveCamera camera,
                         float x0, float y0, float z0,
                         float x1, float y1, float z1,
                         float[] rgba) {
        fillBoxVertices(x0, y0, z0, x1, y1, z1);
        boxMesh.setVertices(vertexBuffer);

        mvp.set(camera.combined);
        shader.setUniformMatrix("u_projTrans", mvp);
        shader.setUniform4fv("u_color", rgba, 0, 4);

        boxMesh.render(shader, GL20.GL_LINES, 0, BOX_VERTEX_COUNT);
    }

    private void fillBoxVertices(float x0, float y0, float z0,
                                 float x1, float y1, float z1) {
        int i = 0;
        // Bottom face
        i = edge(i, x0, y0, z0, x1, y0, z0);
        i = edge(i, x1, y0, z0, x1, y0, z1);
        i = edge(i, x1, y0, z1, x0, y0, z1);
        i = edge(i, x0, y0, z1, x0, y0, z0);
        // Top face
        i = edge(i, x0, y1, z0, x1, y1, z0);
        i = edge(i, x1, y1, z0, x1, y1, z1);
        i = edge(i, x1, y1, z1, x0, y1, z1);
        i = edge(i, x0, y1, z1, x0, y1, z0);
        // Vertical pillars
        i = edge(i, x0, y0, z0, x0, y1, z0);
        i = edge(i, x1, y0, z0, x1, y1, z0);
        i = edge(i, x1, y0, z1, x1, y1, z1);
        edge(i, x0, y0, z1, x0, y1, z1);
    }

    private int edge(int i,
                     float ax, float ay, float az,
                     float bx, float by, float bz) {
        vertexBuffer[i    ] = ax; vertexBuffer[i + 1] = ay; vertexBuffer[i + 2] = az;
        vertexBuffer[i + 3] = bx; vertexBuffer[i + 4] = by; vertexBuffer[i + 5] = bz;
        return i + 6;
    }

    // ----------------------------------------------------------------
    // Shader
    // ----------------------------------------------------------------

    private static ShaderProgram buildShader() {
        ShaderProgram program = new ShaderProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        boolean compiled = program.isCompiled();
        if (!compiled) {
            throw new IllegalStateException(
                    "StreamingZoneWireframeRenderer shader failed to compile: " + program.getLog());
        }
        return program;
    }
}