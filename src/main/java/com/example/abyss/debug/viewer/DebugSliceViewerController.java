package com.example.abyss.debug.viewer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.InputProcessor;

/**
 * Shared debug input controller for slice-based worldgen viewers.
 * <p>
 * Controls:
 * </p>
 * <ul>
 *     <li>W/A/S/D move the slice center</li>
 *     <li>Space moves the sampled Z slice forward</li>
 *     <li>Left/Right control moves the sampled Z slice backward</li>
 *     <li>Mouse wheel zooms in/out</li>
 *     <li>G toggles the chunk grid</li>
 * </ul>
 */
public class DebugSliceViewerController {

    private final int viewerMoveStep;
    private final int viewerSliceStep;
    private final ScrollCaptureProcessor scrollCaptureProcessor = new ScrollCaptureProcessor();

    public DebugSliceViewerController(int viewerMoveStep, int viewerSliceStep) {
        this.viewerMoveStep = viewerMoveStep;
        this.viewerSliceStep = viewerSliceStep;
        installInputProcessor();
    }

    public InputResult update(WorldGenSliceRenderer sliceRenderer) {
        boolean moved = false;
        boolean sliceChanged = false;

        if (Gdx.input.isKeyJustPressed(Input.Keys.G)) {
            sliceRenderer.toggleChunkGrid();
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) {
            sliceRenderer.moveSliceZ(viewerSliceStep);
            sliceChanged = true;
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.CONTROL_LEFT)
                || Gdx.input.isKeyJustPressed(Input.Keys.CONTROL_RIGHT)) {
            sliceRenderer.moveSliceZ(-viewerSliceStep);
            sliceChanged = true;
        }

        int scrollAmount = scrollCaptureProcessor.drainScrollY();
        if (scrollAmount != 0) {
            if (scrollAmount < 0) {
                sliceRenderer.zoom(1);
            } else {
                sliceRenderer.zoom(-1);
            }
        }

        int moveX = 0;
        int moveY = 0;
        if (Gdx.input.isKeyPressed(Input.Keys.A)) {
            moveX -= viewerMoveStep;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.D)) {
            moveX += viewerMoveStep;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.W)) {
            moveY += viewerMoveStep;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.S)) {
            moveY -= viewerMoveStep;
        }
        if (moveX != 0 || moveY != 0) {
            sliceRenderer.moveCenter(moveX, moveY);
            moved = true;
        }

        return new InputResult(moved, sliceChanged);
    }

    public static String getControlSummary() {
        return "Controls: WASD move | Space forward slice | Ctrl backward slice | Mouse wheel zoom | G grid";
    }

    private void installInputProcessor() {
        InputProcessor existing = Gdx.input.getInputProcessor();
        if (existing == null) {
            Gdx.input.setInputProcessor(scrollCaptureProcessor);
            return;
        }
        if (existing == scrollCaptureProcessor) {
            return;
        }
        if (existing instanceof InputMultiplexer multiplexer) {
            multiplexer.addProcessor(scrollCaptureProcessor);
            return;
        }
        Gdx.input.setInputProcessor(new InputMultiplexer(existing, scrollCaptureProcessor));
    }

    public record InputResult(boolean moved, boolean sliceChanged) {
        public boolean changedView() {
            return moved || sliceChanged;
        }
    }

    private static final class ScrollCaptureProcessor extends InputAdapter {
        private int pendingScrollY;

        @Override
        public boolean scrolled(float amountX, float amountY) {
            pendingScrollY += Math.round(amountY);
            return false;
        }

        int drainScrollY() {
            int result = pendingScrollY;
            pendingScrollY = 0;
            return result;
        }
    }
}
