package com.example.abyss.debug;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.example.abyss.debug.viewer.FirstPersonDebugController;
import com.example.abyss.world.AbyssWorld;
import com.example.abyss.world.LoadedChunk;
import com.example.abyss.world.VoxelChunk;

/**
 * First-person debug viewer that renders streamed chunk meshes in 3D.
 */
public class FirstPersonWorldDebugApp extends ApplicationAdapter {

    private static final long WORLD_SEED = 42L;
    private static final int INITIAL_HORIZONTAL_RADIUS = 2;
    private static final int INITIAL_VERTICAL_RADIUS = 2;
    private static final int STREAM_HORIZONTAL_RADIUS = 4;
    private static final int STREAM_VERTICAL_RADIUS = 4;
    private static final float CAMERA_FOV_DEGREES = 75f;
    private static final float CAMERA_NEAR = 0.1f;
    private static final float CAMERA_FAR = 1200f;
    private static final float MOVE_SPEED_UNITS_PER_SECOND = 48f;
    private static final float FAST_MOVE_MULTIPLIER = 3f;
    private static final float MOUSE_SENSITIVITY_DEGREES = 0.15f;
    private static final Vector3 START_POSITION = new Vector3(0f, 0f, 0f);
    private static final Color SKY_COLOR = new Color(0.03f, 0.04f, 0.08f, 1f);

    private final Matrix4 modelMatrix = new Matrix4();
    private final Matrix4 modelViewProjectionMatrix = new Matrix4();
    private SpriteBatch batch;
    private BitmapFont font;
    private ShaderProgram shaderProgram;
    private PerspectiveCamera camera;
    private FirstPersonDebugController controller;
    private AbyssWorld world;
    private String startupInfo;
    private String debugInfo;

    @Override
    public void create() {
        ShaderProgram.pedantic = false;
        batch = new SpriteBatch();
        font = new BitmapFont();
        shaderProgram = buildShaderProgram();
        camera = new PerspectiveCamera(CAMERA_FOV_DEGREES, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.near = CAMERA_NEAR;
        camera.far = CAMERA_FAR;
        camera.position.set(START_POSITION);
        camera.lookAt(START_POSITION.x + 1f, START_POSITION.y, START_POSITION.z);
        camera.update(true);
        controller = new FirstPersonDebugController(
                camera,
                MOVE_SPEED_UNITS_PER_SECOND,
                FAST_MOVE_MULTIPLIER,
                MOUSE_SENSITIVITY_DEGREES
        );

        world = new AbyssWorld(WORLD_SEED);
        world.init();
        world.loadChunksAround((int) START_POSITION.x, (int) START_POSITION.y, (int) START_POSITION.z, INITIAL_HORIZONTAL_RADIUS, INITIAL_VERTICAL_RADIUS);

        startupInfo = String.format(
                "3D first-person debug viewer | Seed: %d | Start: (%.0f, %.0f, %.0f)",
                WORLD_SEED,
                START_POSITION.x,
                START_POSITION.y,
                START_POSITION.z
        );
        updateDebugInfo();
    }

    @Override
    public void render() {
        controller.update(Gdx.graphics.getDeltaTime());
        streamWorldAroundCamera();
        world.pollCompletedChunks();
        world.queueDirtyMeshes();
        world.pollCompletedMeshes();
        updateDebugInfo();

        Gdx.gl.glViewport(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        Gdx.gl.glClearColor(SKY_COLOR.r, SKY_COLOR.g, SKY_COLOR.b, SKY_COLOR.a);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glEnable(GL20.GL_CULL_FACE);
        Gdx.gl.glCullFace(GL20.GL_BACK);

        renderWorld();
        renderOverlay();
    }

    @Override
    public void resize(int width, int height) {
        camera.viewportWidth = width;
        camera.viewportHeight = height;
        camera.update(true);
    }

    @Override
    public void dispose() {
        if (controller != null) {
            controller.setMouseLookEnabled(false);
        }
        if (shaderProgram != null) {
            shaderProgram.dispose();
        }
        if (batch != null) {
            batch.dispose();
        }
        if (font != null) {
            font.dispose();
        }
        if (world != null) {
            world.dispose();
        }
    }

    private void streamWorldAroundCamera() {
        int worldX = Math.round(camera.position.x);
        int worldY = Math.round(camera.position.y);
        int worldZ = Math.round(camera.position.z);
        world.getChunkLoader().loadChunksAroundAsync(worldX, worldY, worldZ, STREAM_HORIZONTAL_RADIUS, STREAM_VERTICAL_RADIUS);
        world.getChunkLoader().unloadDistantChunks(worldX, worldY, worldZ, STREAM_HORIZONTAL_RADIUS + 1, STREAM_VERTICAL_RADIUS + 1);
    }

    private void renderWorld() {
        shaderProgram.bind();

        // --- Opaque pass ---
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        for (LoadedChunk loadedChunk : world.getChunkManager().getLoadedChunkEntries()) {
            VoxelChunk chunk = loadedChunk.chunk();
            modelMatrix.idt().setTranslation(chunk.getWorldX(), chunk.getWorldY(), chunk.getWorldZ());
            modelViewProjectionMatrix.set(camera.combined).mul(modelMatrix);
            shaderProgram.setUniformMatrix("u_projTrans", modelViewProjectionMatrix);
            for (Mesh mesh : loadedChunk.renderState().getOpaqueMeshes()) {
                renderMesh(mesh);
            }
        }

        // --- Transparent pass (water, crystal, etc.) ---
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        Gdx.gl.glDepthMask(false);
        for (LoadedChunk loadedChunk : world.getChunkManager().getLoadedChunkEntries()) {
            VoxelChunk chunk = loadedChunk.chunk();
            modelMatrix.idt().setTranslation(chunk.getWorldX(), chunk.getWorldY(), chunk.getWorldZ());
            modelViewProjectionMatrix.set(camera.combined).mul(modelMatrix);
            shaderProgram.setUniformMatrix("u_projTrans", modelViewProjectionMatrix);
            for (Mesh mesh : loadedChunk.renderState().getTransparentMeshes()) {
                renderMesh(mesh);
            }
        }
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void renderMesh(Mesh mesh) {
        if (mesh == null || mesh.getNumIndices() == 0) {
            return;
        }
        mesh.render(shaderProgram, GL20.GL_TRIANGLES);
    }

    private void renderOverlay() {
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        batch.setProjectionMatrix(new Matrix4().setToOrtho2D(0f, 0f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight()));
        batch.setColor(Color.WHITE);
        font.setColor(Color.WHITE);

        batch.begin();
        if (debugInfo != null) {
            font.draw(batch, debugInfo, 16f, Gdx.graphics.getHeight() - 16f);
        }
        batch.end();

        Gdx.gl.glDepthMask(true);
    }

    private void updateDebugInfo() {
        debugInfo = String.format(
                "%s\nPos: (%.1f, %.1f, %.1f) | Dir: (%.2f, %.2f, %.2f)\nLoaded chunks: %d | Pending chunks: %d | Pending meshes: %d\n%s\nFPS: %d",
                startupInfo,
                camera.position.x,
                camera.position.y,
                camera.position.z,
                camera.direction.x,
                camera.direction.y,
                camera.direction.z,
                world.getChunkManager().getLoadedCount(),
                world.getChunkManager().getPendingCount(),
                world.getChunkManager().getPendingMeshCount(),
                FirstPersonDebugController.getControlSummary(),
                Gdx.graphics.getFramesPerSecond()
        );
    }

    private static ShaderProgram buildShaderProgram() {
        ShaderProgram program = new ShaderProgram(VERTEX_SHADER_SOURCE, FRAGMENT_SHADER_SOURCE);
        if (!program.isCompiled()) {
            throw new IllegalStateException("Failed to compile first-person debug shader: " + program.getLog());
        }
        return program;
    }

    private static final String VERTEX_SHADER_SOURCE = """
            attribute vec3 a_position;
            attribute vec3 a_normal;
            attribute vec4 a_color;
            uniform mat4 u_projTrans;
            varying vec4 v_color;
            varying float v_light;
            void main() {
                vec3 lightDir = normalize(vec3(0.45, 0.8, 0.35));
                v_light = max(dot(normalize(a_normal), lightDir), 0.2);
                v_color = a_color;
                gl_Position = u_projTrans * vec4(a_position, 1.0);
            }
            """;

    private static final String FRAGMENT_SHADER_SOURCE = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec4 v_color;
            varying float v_light;
            void main() {
                gl_FragColor = vec4(v_color.rgb * v_light, v_color.a);
            }
            """;
}