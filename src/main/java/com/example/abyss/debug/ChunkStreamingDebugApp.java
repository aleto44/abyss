package com.example.abyss.debug;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.example.abyss.debug.viewer.ChunkStreamingWorldGenSampler;
import com.example.abyss.debug.viewer.DebugSliceViewerController;
import com.example.abyss.debug.viewer.WorldGenColorResolver;
import com.example.abyss.debug.viewer.WorldGenSliceRenderer;
import com.example.abyss.world.AbyssWorld;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Debug desktop client that visualizes worldgen through the live chunk-streaming pipeline.
 */
public class ChunkStreamingDebugApp extends ApplicationAdapter {

    private static final int INITIAL_HORIZONTAL_RADIUS = 2;
    private static final int INITIAL_VERTICAL_RADIUS = 2;
    private static final int VIEWER_MOVE_STEP = 32;
    private static final int VIEWER_SLICE_STEP = 32;

    private SpriteBatch batch;
    private BitmapFont font;
    private ShapeRenderer shapeRenderer;
    private AbyssWorld world;
    private WorldGenSliceRenderer sliceRenderer;
    private DebugSliceViewerController viewerController;
    private String startupInfo;
    private String debugInfo;

    @Override
    public void create() {
        batch = new SpriteBatch();
        font = new BitmapFont();
        shapeRenderer = new ShapeRenderer();

        long seed = 42L;
        world = new AbyssWorld(seed);
        world.init();
        sliceRenderer = new WorldGenSliceRenderer(
                new ChunkStreamingWorldGenSampler(world),
                new WorldGenColorResolver()
        );
        viewerController = new DebugSliceViewerController(VIEWER_MOVE_STEP, VIEWER_SLICE_STEP);

        long startMs = System.currentTimeMillis();
        world.loadChunksAround(0, 0, 0, INITIAL_HORIZONTAL_RADIUS, INITIAL_VERTICAL_RADIUS);
        long initialLoadElapsedMs = System.currentTimeMillis() - startMs;

        VoxelType atOrigin = world.getVoxelAt(0, 0, 0);
        VoxelType atShaftEdge = world.getVoxelAt(100, 0, 0);
        VoxelType atDeep = world.getVoxelAt(0, -2000, 0);

        startupInfo = String.format(
                "Chunk-streaming debug viewer | Seed: %d | Initial chunks loaded: %d | Initial load time: %d ms\n" +
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
        sliceRenderer.render(shapeRenderer, margin, margin, sliceWidth, sliceHeight);

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
        DebugSliceViewerController.InputResult result = viewerController.update(sliceRenderer);
        if (result.changedView()) {
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
                        "%s\n" +
                        "Pending meshes: %d | Opaque meshes: %d | Transparent meshes: %d\n" +
                        "FPS: %d",
                startupInfo,
                sliceRenderer.buildLegend(),
                DebugSliceViewerController.getControlSummary(),
                pendingMeshCount,
                opaqueMeshCount,
                transparentMeshCount,
                Gdx.graphics.getFramesPerSecond()
        );
    }
}
