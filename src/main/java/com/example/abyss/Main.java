package com.example.abyss;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.example.abyss.world.AbyssWorld;
import com.example.abyss.world.voxel.VoxelType;

public class Main extends ApplicationAdapter {

    private SpriteBatch batch;
    private BitmapFont font;
    private AbyssWorld world;

    /** Stable startup smoke-test snapshot. */
    private String startupInfo;
    /** Live render/meshing status updated every frame. */
    private String debugInfo;

    @Override
    public void create() {
        batch = new SpriteBatch();
        font = new BitmapFont(); // default LibGDX arial-15

        long seed = 42L;
        world = new AbyssWorld(seed);
        world.init();

        long startMs = System.currentTimeMillis();
        world.loadChunksAround(0, 0, 0, 1, 1);
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

        world.pollCompletedChunks();
        world.queueDirtyMeshes();
        world.pollCompletedMeshes();
        updateDebugInfo();

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
        world.dispose();
    }

    private static int count(Iterable<?> values) {
        int total = 0;
        for (Object ignored : values) {
            total++;
        }
        return total;
    }

    private void updateDebugInfo() {
        int pendingMeshCount = world.getChunkManager().getPendingMeshCount();
        int opaqueMeshCount = count(world.getOpaqueMeshes());
        int transparentMeshCount = count(world.getTransparentMeshes());

        debugInfo = String.format(
                "%s\n" +
                        "Pending meshes: %d | Opaque meshes: %d | Transparent meshes: %d\n" +
                        "FPS: %d",
                startupInfo,
                pendingMeshCount,
                opaqueMeshCount,
                transparentMeshCount,
                Gdx.graphics.getFramesPerSecond()
        );
    }

    public static void main(String[] args) {
        com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration config =
                new com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration();
        config.setWindowedMode(800, 600);
        config.setTitle("Abyss");

        new com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application(new Main(), config);
    }
}