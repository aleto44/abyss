package com.example.abyss;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.example.abyss.world.AbyssWorld;
import com.example.abyss.world.render.WorldGenColorResolver;
import com.example.abyss.world.render.WorldGenSampler;
import com.example.abyss.world.render.WorldGenSliceRenderer;
import com.example.abyss.world.voxel.VoxelType;

public class Main extends ApplicationAdapter {

    private static final int INITIAL_HORIZONTAL_RADIUS = 2;
    private static final int INITIAL_VERTICAL_RADIUS = 2;
    private static final int VIEWER_MOVE_STEP = 32;
    private static final int VIEWER_SLICE_STEP = 32;

    private SpriteBatch batch;
    private BitmapFont font;
    private ShapeRenderer shapeRenderer;
    private AbyssWorld world;
    private WorldGenSliceRenderer sliceRenderer;

    /** Stable startup smoke-test snapshot. */
    private String startupInfo;
    /** Live render/meshing status updated every frame. */
    private String debugInfo;

    @Override
    public void create() {
        batch = new SpriteBatch();
        font = new BitmapFont(); // default LibGDX arial-15
        shapeRenderer = new ShapeRenderer();

        long seed = 42L;
        world = new AbyssWorld(seed);
        world.init();
        sliceRenderer = new WorldGenSliceRenderer(
                new WorldGenSampler(world),
                new WorldGenColorResolver()
        );

        long startMs = System.currentTimeMillis();
        world.loadChunksAround(0, 0, 0, INITIAL_HORIZONTAL_RADIUS, INITIAL_VERTICAL_RADIUS);
        long initialLoadElapsedMs = System.currentTimeMillis() - startMs;

        VoxelType atOrigin = world.getVoxelAt(0, 0, 0);
        VoxelType atShaftEdge = world.getVoxelAt(100, 0, 0);
        VoxelType atDeep = world.getVoxelAt(0, -2000, 0);

        startupInfo = String.format(
                "Seed: %d | Initial chunks loaded: %d | Initial load time: %d ms\n" +
                        "Voxel @ (0,0,0): %s\n" +
                        "Voxel @ (100,0,0) shaft edge: %s\n" +
                        "Voxel @ (0,-2000,0) deep: %s",
                seed,
                world.getChunkManager().getLoadedCount(),
                initialLoadElapsedMs,
                atOrigin,
                atShaftEdge,
                atDeep
        );
        updateDebugInfo();
        Gdx.app.log("Abyss", startupInfo + "\n" + debugInfo);
    }

    @Override
    public void render() {
        Gdx.gl.glClearColor(0.05f, 0.0f, 0.1f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        handleViewerInput();
        world.pollCompletedChunks();
        world.queueDirtyMeshes();
        world.pollCompletedMeshes();
        updateDebugInfo();

        float margin = 16f;
        float sliceWidth = Gdx.graphics.getWidth() - margin * 2f;
        float sliceHeight = Math.max(240f, Gdx.graphics.getHeight() * 0.65f);
        float sliceOriginY = margin;
        sliceRenderer.render(shapeRenderer, margin, sliceOriginY, sliceWidth, sliceHeight);

        batch.begin();
        if (debugInfo != null) {
            font.draw(batch, debugInfo, 16, Gdx.graphics.getHeight() - 16);
        }
        batch.end();
    }

    @Override
    public void dispose() {
        batch.dispose();
        font.dispose();
        shapeRenderer.dispose();
        world.dispose();
    }

    private static int count(Iterable<?> values) {
        int total = 0;
        for (Object ignored : values) {
            total++;
        }
        return total;
    }

    private void handleViewerInput() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.G)) {
            sliceRenderer.toggleChunkGrid();
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.PAGE_UP)) {
            sliceRenderer.moveSliceZ(VIEWER_SLICE_STEP);
            requestVisualizationChunks();
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.PAGE_DOWN)) {
            sliceRenderer.moveSliceZ(-VIEWER_SLICE_STEP);
            requestVisualizationChunks();
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.EQUALS) || Gdx.input.isKeyJustPressed(Input.Keys.PLUS)) {
            sliceRenderer.zoom(1);
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.MINUS)) {
            sliceRenderer.zoom(-1);
        }

        int moveX = 0;
        int moveY = 0;
        if (Gdx.input.isKeyPressed(Input.Keys.LEFT)) {
            moveX -= VIEWER_MOVE_STEP;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.RIGHT)) {
            moveX += VIEWER_MOVE_STEP;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.UP)) {
            moveY += VIEWER_MOVE_STEP;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.DOWN)) {
            moveY -= VIEWER_MOVE_STEP;
        }
        if (moveX != 0 || moveY != 0) {
            sliceRenderer.moveCenter(moveX, moveY);
            requestVisualizationChunks();
        }
    }

    private void requestVisualizationChunks() {
        world.loadChunksAroundAsync(0, sliceRenderer.getCenterWorldY(), sliceRenderer.getSliceWorldZ(), INITIAL_HORIZONTAL_RADIUS + 1, INITIAL_VERTICAL_RADIUS + 2);
    }

    private void updateDebugInfo() {
        int pendingMeshCount = world.getChunkManager().getPendingMeshCount();
        int opaqueMeshCount = count(world.getOpaqueMeshes());
        int transparentMeshCount = count(world.getTransparentMeshes());

        debugInfo = String.format(
                "%s\n" +
                        "%s\n" +
                        "Controls: Arrows move | PgUp/PgDn change Z slice | +/- zoom | G grid\n" +
                        "Pending meshes: %d | Opaque meshes: %d | Transparent meshes: %d\n" +
                        "FPS: %d",
                startupInfo,
                sliceRenderer.buildLegend(),
                pendingMeshCount,
                opaqueMeshCount,
                transparentMeshCount,
                Gdx.graphics.getFramesPerSecond()
        );
    }

    public static void main(String[] args) {
        com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration config =
                new com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration();
        config.setWindowedMode(1280, 800);
        config.setTitle("Abyss");

        new com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application(new Main(), config);
    }
}
