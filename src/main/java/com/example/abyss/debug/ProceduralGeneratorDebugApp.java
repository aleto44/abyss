package com.example.abyss.debug;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.example.abyss.debug.viewer.DebugSliceViewerController;
import com.example.abyss.debug.viewer.ProceduralGeneratorWorldGenSampler;
import com.example.abyss.debug.viewer.WorldGenColorResolver;
import com.example.abyss.debug.viewer.WorldGenSliceRenderer;

/**
 * Debug desktop client that samples {@code ProceduralGenerator} directly.
 */
public class ProceduralGeneratorDebugApp extends ApplicationAdapter {

    private static final int VIEWER_MOVE_STEP = 32;
    private static final int VIEWER_SLICE_STEP = 32;
    private static final long SEED = 42L;

    private SpriteBatch batch;
    private BitmapFont font;
    private ShapeRenderer shapeRenderer;
    private WorldGenSliceRenderer sliceRenderer;
    private DebugSliceViewerController viewerController;
    private String debugInfo;

    @Override
    public void create() {
        batch = new SpriteBatch();
        font = new BitmapFont();
        shapeRenderer = new ShapeRenderer();
        sliceRenderer = new WorldGenSliceRenderer(
                new ProceduralGeneratorWorldGenSampler(SEED),
                new WorldGenColorResolver()
        );
        viewerController = new DebugSliceViewerController(VIEWER_MOVE_STEP, VIEWER_SLICE_STEP);
        updateDebugInfo();
    }

    @Override
    public void render() {
        Gdx.gl.glClearColor(0.02f, 0.02f, 0.06f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        handleViewerInput();
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
    }

    private void handleViewerInput() {
        viewerController.update(sliceRenderer);
    }

    private void updateDebugInfo() {
        debugInfo = String.format(
                "ProceduralGenerator direct-sample debug viewer | Seed: %d\n" +
                        "%s\n" +
                        "%s\n" +
                        "FPS: %d",
                SEED,
                sliceRenderer.buildLegend(),
                DebugSliceViewerController.getControlSummary(),
                Gdx.graphics.getFramesPerSecond()
        );
    }
}