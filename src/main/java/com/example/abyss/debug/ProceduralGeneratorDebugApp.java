package com.example.abyss.debug;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.example.abyss.debug.viewer.ProceduralGeneratorWorldGenSampler;
import com.example.abyss.debug.viewer.DebugSliceViewerController;
import com.example.abyss.debug.viewer.WorldGenColorResolver;
import com.example.abyss.debug.viewer.WorldGenSliceRenderer;

/**
 * Debug desktop client that samples {@code ProceduralGenerator} directly.
 */
public class ProceduralGeneratorDebugApp extends AbstractSliceDebugApp {

    private static final int VIEWER_MOVE_STEP = 32;
    private static final int VIEWER_SLICE_STEP = 32;
    private static final long SEED = 42L;
    private static final Color CLEAR_COLOR = new Color(0.02f, 0.02f, 0.06f, 1f);

    public ProceduralGeneratorDebugApp() {
        super(VIEWER_MOVE_STEP, VIEWER_SLICE_STEP);
    }

    @Override
    protected WorldGenSliceRenderer createSliceRenderer() {
        return new WorldGenSliceRenderer(
                new ProceduralGeneratorWorldGenSampler(SEED),
                new WorldGenColorResolver()
        );
    }

    @Override
    protected String buildDebugInfo() {
        return String.format(
                "ProceduralGenerator direct-sample debug viewer | Seed: %d\n" +
                        "%s\n" +
                        "%s\n" +
                        "FPS: %d",
                SEED,
                getSliceRenderer().buildLegend(),
                DebugSliceViewerController.getControlSummary(),
                Gdx.graphics.getFramesPerSecond()
        );
    }

    @Override
    protected Color getClearColor() {
        return CLEAR_COLOR;
    }
}