package com.example.abyss.debug;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.example.abyss.debug.viewer.ChunkStreamingWorldGenSampler;
import com.example.abyss.debug.viewer.DebugSliceViewerController;
import com.example.abyss.debug.viewer.WorldGenColorResolver;
import com.example.abyss.debug.viewer.WorldGenSliceRenderer;
import com.example.abyss.world.AbyssWorld;
import com.example.abyss.world.voxel.VoxelType;

/**
 * Debug desktop client that visualizes worldgen through the live chunk-streaming pipeline.
 */
public class ChunkStreamingDebugApp extends AbstractSliceDebugApp {

    private static final int INITIAL_HORIZONTAL_RADIUS = 2;
    private static final int INITIAL_VERTICAL_RADIUS = 2;
    private static final int VIEWER_MOVE_STEP = 32;
    private static final int VIEWER_SLICE_STEP = 32;
    private static final long SEED = 42L;
    private static final Color CLEAR_COLOR = new Color(0.05f, 0.0f, 0.1f, 1f);

    private AbyssWorld world;
    private String startupInfo;

    public ChunkStreamingDebugApp() {
        super(VIEWER_MOVE_STEP, VIEWER_SLICE_STEP);
    }

    @Override
    protected WorldGenSliceRenderer createSliceRenderer() {
        world = new AbyssWorld(SEED);
        world.init();
        return new WorldGenSliceRenderer(
                new ChunkStreamingWorldGenSampler(world),
                new WorldGenColorResolver()
        );
    }

    @Override
    protected void onCreate() {
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
                SEED,
                world.getChunkManager().getLoadedCount(),
                initialLoadElapsedMs,
                atOrigin,
                atShaftEdge,
                atDeep
        );
    }

    @Override
    protected void onBeforeRender() {
        world.pollCompletedChunks();
        world.queueDirtyMeshes();
        world.pollCompletedMeshes();
    }

    @Override
    protected void onViewChanged(DebugSliceViewerController.InputResult result) {
        world.loadChunksAroundAsync(0, getSliceRenderer().getCenterWorldY(), getSliceRenderer().getSliceWorldZ(), INITIAL_HORIZONTAL_RADIUS + 1, INITIAL_VERTICAL_RADIUS + 2);
    }

    @Override
    protected String buildDebugInfo() {
        int pendingMeshCount = world.getChunkManager().getPendingMeshCount();
        int opaqueMeshCount = count(world.getOpaqueMeshes());
        int transparentMeshCount = count(world.getTransparentMeshes());

        return String.format(
                "%s\n" +
                        "%s\n" +
                        "%s\n" +
                        "Pending meshes: %d | Opaque meshes: %d | Transparent meshes: %d\n" +
                        "FPS: %d",
                startupInfo,
                getSliceRenderer().buildLegend(),
                DebugSliceViewerController.getControlSummary(),
                pendingMeshCount,
                opaqueMeshCount,
                transparentMeshCount,
                Gdx.graphics.getFramesPerSecond()
        );
    }

    @Override
    protected Color getClearColor() {
        return CLEAR_COLOR;
    }

    @Override
    protected void onDispose() {
        if (world != null) {
            world.dispose();
        }
    }

    private static int count(Iterable<?> values) {
        int total = 0;
        for (Object ignored : values) {
            total++;
        }
        return total;
    }
}
