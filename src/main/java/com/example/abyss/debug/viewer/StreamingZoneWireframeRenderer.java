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
import com.example.abyss.world.render.WorldRenderSnapshot;
import com.example.abyss.world.render.distant.DistantRenderEntry;
import com.example.abyss.world.render.distant.DistantTerrainConfig;

import java.util.List;

/**
 * Draws axis-aligned wireframe boxes for each streaming zone in the 3D debug view.
 * <ul>
 *   <li>Pink  — near-field chunk cylinder</li>
 *   <li>Green — distant terrain load horizon (generation budget wall)</li>
 *   <li>Red   — distant terrain eviction boundary (hard removal threshold)</li>
 *   <li>Yellow — each individually loaded distant terrain region</li>
 * </ul>
 * Toggle with {@code F3}.
 */
public class StreamingZoneWireframeRenderer {

    private static final float[] COLOR_NEAR_FIELD      = { 1.0f, 0.2f, 0.9f, 0.85f };
    private static final float[] COLOR_DISTANT_REGION = { 1.0f, 0.9f, 0.1f, 0.7f };

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
        renderDistantRegions(camera, snapshot.distantTerrainEntries());

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
    // Distant terrain region bands
    // ----------------------------------------------------------------

    private static final float REGION_XZ = DistantTerrainConfig.MACRO_CHUNK_SIZE_VOXELS;
    private static final float REGION_Y  = DistantTerrainConfig.VERTICAL_DISTANT_TERRAIN_HEIGHT
                                         * WorldConstants.CHUNK_SIZE;

    /** Draws one yellow wireframe box per loaded distant terrain region. */
    private void renderDistantRegions(PerspectiveCamera camera, List<DistantRenderEntry> entries) {
        for (DistantRenderEntry entry : entries) {
            drawBox(camera,
                    entry.worldX(),            entry.worldY(),            entry.worldZ(),
                    entry.worldX() + REGION_XZ, entry.worldY() + REGION_Y, entry.worldZ() + REGION_XZ,
                    COLOR_DISTANT_REGION);
        }
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