package com.example.abyss.debug;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.example.abyss.debug.constants.FirstPersonWorldDebugConstants;
import com.example.abyss.debug.viewer.FirstPersonDebugController;
import com.example.abyss.debug.viewer.FirstPersonWorldDebugOverlay;
import com.example.abyss.debug.viewer.FirstPersonWorldRenderer;
import com.example.abyss.debug.viewer.StreamingZoneWireframeRenderer;
import com.example.abyss.util.MainThreadWatchdog;
import com.example.abyss.world.AbyssWorld;
import com.example.abyss.world.render.NearChunkStreamer;
import com.example.abyss.world.render.lod.LodStreamer;

/**
 * First-person debug viewer that renders streamed chunk meshes in 3D.
 */
public class FirstPersonWorldDebugApp extends ApplicationAdapter {

    private SpriteBatch batch;
    private BitmapFont font;
    private PerspectiveCamera camera;
    private FirstPersonDebugController controller;
    private AbyssWorld world;
    private NearChunkStreamer chunkStreamer;
    private LodStreamer lodStreamer;
    private MainThreadWatchdog watchdog;
    private FirstPersonWorldRenderer worldRenderer;
    private StreamingZoneWireframeRenderer wireframeRenderer;
    private FirstPersonWorldDebugOverlay debugOverlay;
    private String startupInfo;
    private String debugInfo;

    @Override
    public void create() {
        batch = new SpriteBatch();
        font = new BitmapFont();
        worldRenderer = new FirstPersonWorldRenderer();
        wireframeRenderer = new StreamingZoneWireframeRenderer();
        debugOverlay = new FirstPersonWorldDebugOverlay(batch, font);

        camera = new PerspectiveCamera(
                FirstPersonWorldDebugConstants.CAMERA_FOV_DEGREES,
                Gdx.graphics.getWidth(),
                Gdx.graphics.getHeight()
        );
        camera.near = FirstPersonWorldDebugConstants.CAMERA_NEAR;
        camera.far = FirstPersonWorldDebugConstants.CAMERA_FAR;
        camera.position.set(FirstPersonWorldDebugConstants.START_POSITION);
        camera.lookAt(
                FirstPersonWorldDebugConstants.START_POSITION.x + 1f,
                FirstPersonWorldDebugConstants.START_POSITION.y,
                FirstPersonWorldDebugConstants.START_POSITION.z
        );
        camera.update(true);
        controller = new FirstPersonDebugController(
                camera,
                FirstPersonWorldDebugConstants.MOVE_SPEED_UNITS_PER_SECOND,
                FirstPersonWorldDebugConstants.FAST_MOVE_MULTIPLIER,
                FirstPersonWorldDebugConstants.MOUSE_SENSITIVITY_DEGREES
        );

        world = new AbyssWorld(FirstPersonWorldDebugConstants.WORLD_SEED);
        world.init();
        world.getChunkLoader().loadChunksAround(
                (int) FirstPersonWorldDebugConstants.START_POSITION.x,
                (int) FirstPersonWorldDebugConstants.START_POSITION.y,
                (int) FirstPersonWorldDebugConstants.START_POSITION.z,
                FirstPersonWorldDebugConstants.INITIAL_HORIZONTAL_RADIUS,
                FirstPersonWorldDebugConstants.INITIAL_VERTICAL_RADIUS
        );
        // Both streamers come from the world — LodStreamer's exclusion zone is derived
        // directly from chunkStreamer's radii, so they are always consistent.
        chunkStreamer = world.getChunkStreamer();
        lodStreamer = world.getLodStreamer();

        startupInfo = String.format(
                "3D first-person debug viewer | Seed: %d | Start: (%.0f, %.0f, %.0f)",
                FirstPersonWorldDebugConstants.WORLD_SEED,
                FirstPersonWorldDebugConstants.START_POSITION.x,
                FirstPersonWorldDebugConstants.START_POSITION.y,
                FirstPersonWorldDebugConstants.START_POSITION.z
        );
        updateDebugInfo();

        watchdog = new MainThreadWatchdog(Thread.currentThread(), 500);
        watchdog.start();
    }

    @Override
    public void render() {
        watchdog.tick();
        controller.update(Gdx.graphics.getDeltaTime());
        chunkStreamer.update(camera, Gdx.graphics.getDeltaTime());
        lodStreamer.update(camera, Gdx.graphics.getDeltaTime());
        world.getChunkLoader().pollCompletedChunks();
        world.getMeshCoordinator().pollCompletedMeshes(worldRenderer.getShaderProgram());
        updateDebugInfo();

        Gdx.gl.glViewport(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        Gdx.gl.glClearColor(
                FirstPersonWorldDebugConstants.SKY_COLOR.r,
                FirstPersonWorldDebugConstants.SKY_COLOR.g,
                FirstPersonWorldDebugConstants.SKY_COLOR.b,
                FirstPersonWorldDebugConstants.SKY_COLOR.a
        );
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glEnable(GL20.GL_CULL_FACE);
        Gdx.gl.glCullFace(GL20.GL_BACK);

        worldRenderer.renderWorld(world, camera);
        boolean zoneVisualizationEnabled = controller.isZoneVisualizationEnabled();
        if (zoneVisualizationEnabled) {
            wireframeRenderer.render(camera, world.getCylinderStreamer(), world.getRenderSnapshot());
        }
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
        if (watchdog != null) {
            watchdog.stop();
        }
        if (controller != null) {
            controller.setMouseLookEnabled(false);
        }
        if (worldRenderer != null) {
            worldRenderer.dispose();
        }
        if (wireframeRenderer != null) {
            wireframeRenderer.dispose();
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

    private void renderOverlay() {
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        debugOverlay.renderOverlay(debugInfo);
        Gdx.gl.glDepthMask(true);
    }

    private void updateDebugInfo() {
        debugInfo = debugOverlay.buildDebugInfo(
                startupInfo,
                camera.position,
                camera.direction,
                world.getWorldDebugStats(),
                chunkStreamer.getLastStats(),
                worldRenderer.getLastFrustumCulledChunks(),
                FirstPersonDebugController.getControlSummary()
        );
    }
}