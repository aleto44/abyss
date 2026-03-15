package com.example.abyss.debug;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.example.abyss.debug.tweak.GenParamOverrides;
import com.example.abyss.debug.tweak.GenParamTweakerPanel;
import com.example.abyss.debug.tweak.TweakableProceduralGeneratorWorldGenSampler;
import com.example.abyss.debug.viewer.DebugSliceViewerController;
import com.example.abyss.debug.viewer.WorldGenColorResolver;
import com.example.abyss.debug.viewer.WorldGenSliceRenderer;

/**
 * Debug desktop client that renders a live X/Y worldgen slice and opens a
 * Swing side-panel for tweaking every numeric {@link com.example.abyss.world.gen.LayerGenConfig}
 * knob in real-time.
 *
 * <p>Moving a slider on the Swing panel immediately invalidates the chunk cache
 * and triggers a re-sample on the next LibGDX frame.</p>
 *
 * <p>Use {@link ProceduralGeneratorTweakerLauncher} to start this app.</p>
 */
public class ProceduralGeneratorTweakerDebugApp extends AbstractSliceDebugApp {

    private static final int VIEWER_MOVE_STEP  = 32;
    private static final int VIEWER_SLICE_STEP = 32;
    private static final long SEED             = 42L;
    private static final Color CLEAR_COLOR     = new Color(0.02f, 0.02f, 0.06f, 1f);

    private final GenParamOverrides overrides;
    private final GenParamTweakerPanel tweakerPanel;

    public ProceduralGeneratorTweakerDebugApp() {
        super(VIEWER_MOVE_STEP, VIEWER_SLICE_STEP);
        this.overrides     = new GenParamOverrides();
        this.tweakerPanel  = new GenParamTweakerPanel(overrides);
    }

    @Override
    protected WorldGenSliceRenderer createSliceRenderer() {
        return new WorldGenSliceRenderer(
                new TweakableProceduralGeneratorWorldGenSampler(SEED, overrides),
                new WorldGenColorResolver()
        );
    }

    @Override
    protected void onCreate() {
        tweakerPanel.show();
    }

    @Override
    protected void onDispose() {
        tweakerPanel.dispose();
    }

    @Override
    protected String buildDebugInfo() {
        return String.format(
                "ProceduralGenerator TWEAKER | Seed: %d\n" +
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
