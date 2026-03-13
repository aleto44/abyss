package com.example.abyss.debug;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.example.abyss.debug.viewer.DebugSliceViewerController;
import com.example.abyss.debug.viewer.WorldGenSliceRenderer;

/**
 * Shared LibGDX shell for 2D slice-based debug viewers.
 */
public abstract class AbstractSliceDebugApp extends ApplicationAdapter {

    private static final float DEFAULT_MARGIN = 16f;
    private static final float MIN_SLICE_HEIGHT = 240f;
    private static final float SLICE_HEIGHT_RATIO = 0.65f;

    private final int viewerMoveStep;
    private final int viewerSliceStep;
    private SpriteBatch batch;
    private BitmapFont font;
    private ShapeRenderer shapeRenderer;
    private WorldGenSliceRenderer sliceRenderer;
    private DebugSliceViewerController viewerController;
    private String debugInfo;

    protected AbstractSliceDebugApp(int viewerMoveStep, int viewerSliceStep) {
        this.viewerMoveStep = viewerMoveStep;
        this.viewerSliceStep = viewerSliceStep;
    }

    @Override
    public final void create() {
        batch = new SpriteBatch();
        font = new BitmapFont();
        shapeRenderer = new ShapeRenderer();
        sliceRenderer = createSliceRenderer();
        viewerController = new DebugSliceViewerController(viewerMoveStep, viewerSliceStep);
        onCreate();
        refreshDebugInfo();
    }

    @Override
    public final void render() {
        Color clearColor = getClearColor();
        Gdx.gl.glClearColor(clearColor.r, clearColor.g, clearColor.b, clearColor.a);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        handleViewerInput();
        onBeforeRender();
        refreshDebugInfo();

        float sliceWidth = Gdx.graphics.getWidth() - DEFAULT_MARGIN * 2f;
        float sliceHeight = Math.max(MIN_SLICE_HEIGHT, Gdx.graphics.getHeight() * SLICE_HEIGHT_RATIO);
        sliceRenderer.render(shapeRenderer, DEFAULT_MARGIN, DEFAULT_MARGIN, sliceWidth, sliceHeight);

        batch.begin();
        if (debugInfo != null) {
            font.draw(batch, debugInfo, DEFAULT_MARGIN, Gdx.graphics.getHeight() - DEFAULT_MARGIN);
        }
        batch.end();
    }

    @Override
    public final void dispose() {
        onDispose();
        if (batch != null) {
            batch.dispose();
        }
        if (font != null) {
            font.dispose();
        }
        if (shapeRenderer != null) {
            shapeRenderer.dispose();
        }
    }

    protected abstract WorldGenSliceRenderer createSliceRenderer();

    protected abstract String buildDebugInfo();

    protected abstract Color getClearColor();

    protected void onCreate() {
    }

    protected void onBeforeRender() {
    }

    protected void onViewChanged(DebugSliceViewerController.InputResult result) {
    }

    protected void onDispose() {
    }

    protected final WorldGenSliceRenderer getSliceRenderer() {
        return sliceRenderer;
    }

    private void handleViewerInput() {
        DebugSliceViewerController.InputResult result = viewerController.update(sliceRenderer);
        if (result.changedView()) {
            onViewChanged(result);
        }
    }

    private void refreshDebugInfo() {
        debugInfo = buildDebugInfo();
    }
}
