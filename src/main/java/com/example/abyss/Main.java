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

    /** Debug info computed once after generation. */
    private String debugInfo;

    @Override
    public void create() {
        batch = new SpriteBatch();
        font = new BitmapFont(); // default LibGDX arial-15

        // --- World generation smoke test ---
        long seed = 42L;
        world = new AbyssWorld(seed);
        world.init();

        // Generate a small 3×3×3 cluster of chunks around the origin
        long startMs = System.currentTimeMillis();
        world.loadChunksAround(0, 0, 0, 1, 1);
        long elapsed = System.currentTimeMillis() - startMs;

        // Sample a few voxels for debug display
        VoxelType atOrigin    = world.getVoxelAt(0, 0, 0);
        VoxelType atShaftEdge = world.getVoxelAt(100, 0, 0);
        VoxelType atDeep      = world.getVoxelAt(0, -2000, 0);
        int chunkCount = world.getChunkManager().getLoadedCount();

        debugInfo = String.format(
            "Seed: %d | Chunks loaded: %d | Gen time: %d ms\n" +
            "Voxel @ (0,0,0): %s\n" +
            "Voxel @ (100,0,0) shaft edge: %s\n" +
            "Voxel @ (0,-2000,0) deep: %s",
            seed, chunkCount, elapsed,
            atOrigin, atShaftEdge, atDeep
        );
        Gdx.app.log("Abyss", debugInfo);
    }

    @Override
    public void render() {
        Gdx.gl.glClearColor(0.05f, 0.0f, 0.1f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

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

    public static void main(String[] args) {
        com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration config =
            new com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration();
        config.setWindowedMode(800, 600);
        config.setTitle("Abyss");

        new com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application(new Main(), config);
    }
}
