package com.example.abyss.debug.viewer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;

/**
 * Free-fly first-person controller for the 3D debug viewer.
 */
public class FirstPersonDebugController {

    private static final Vector3 WORLD_UP = new Vector3(0f, 1f, 0f);
    private static final float MIN_PITCH_DEGREES = -89f;
    private static final float MAX_PITCH_DEGREES = 89f;

    private final Camera camera;
    private final float moveSpeedUnitsPerSecond;
    private final float fastMoveMultiplier;
    private final float mouseSensitivityDegrees;
    private final MouseLookProcessor mouseLookProcessor = new MouseLookProcessor();
    private final Vector3 tmp = new Vector3();
    private float yawDegrees;
    private float pitchDegrees;

    public FirstPersonDebugController(Camera camera,
                                      float moveSpeedUnitsPerSecond,
                                      float fastMoveMultiplier,
                                      float mouseSensitivityDegrees) {
        this.camera = camera;
        this.moveSpeedUnitsPerSecond = moveSpeedUnitsPerSecond;
        this.fastMoveMultiplier = fastMoveMultiplier;
        this.mouseSensitivityDegrees = mouseSensitivityDegrees;
        Vector3 direction = new Vector3(camera.direction).nor();
        yawDegrees = MathUtils.atan2Deg(direction.z, direction.x);
        pitchDegrees = MathUtils.asin(MathUtils.clamp(direction.y, -1f, 1f));
        installInputProcessor();
        updateCameraDirection();
    }

    public void update(float deltaSeconds) {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            setMouseLookEnabled(false);
        }
        if (Gdx.input.justTouched()) {
            setMouseLookEnabled(true);
        }

        if (mouseLookProcessor.isMouseLookEnabled()) {
            yawDegrees += mouseLookProcessor.drainDeltaX() * mouseSensitivityDegrees;
            pitchDegrees = clampPitch(
                    pitchDegrees - mouseLookProcessor.drainDeltaY() * mouseSensitivityDegrees
            );
            updateCameraDirection();
        } else {
            mouseLookProcessor.resetDeltas();
        }

        float speed = moveSpeedUnitsPerSecond;
        if (Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT)) {
            speed *= fastMoveMultiplier;
        }
        float moveAmount = speed * Math.max(deltaSeconds, 0f);
        Vector3 forward = tmp.set(camera.direction).nor();
        Vector3 strafe = new Vector3(forward).crs(WORLD_UP).nor();

        if (Gdx.input.isKeyPressed(Input.Keys.W)) {
            camera.position.mulAdd(forward, moveAmount);
        }
        if (Gdx.input.isKeyPressed(Input.Keys.S)) {
            camera.position.mulAdd(forward, -moveAmount);
        }
        if (Gdx.input.isKeyPressed(Input.Keys.D)) {
            camera.position.mulAdd(strafe, moveAmount);
        }
        if (Gdx.input.isKeyPressed(Input.Keys.A)) {
            camera.position.mulAdd(strafe, -moveAmount);
        }
        if (Gdx.input.isKeyPressed(Input.Keys.SPACE)) {
            camera.position.y += moveAmount;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT)) {
            camera.position.y -= moveAmount;
        }
        camera.update(true);
    }

    public void setMouseLookEnabled(boolean enabled) {
        mouseLookProcessor.setMouseLookEnabled(enabled);
    }

    public static String getControlSummary() {
        return "Controls: Click capture mouse | WASD move | Space up | Ctrl down | Shift boost | Mouse look | Esc release mouse";
    }

    static float clampPitch(float pitchDegrees) {
        return MathUtils.clamp(pitchDegrees, MIN_PITCH_DEGREES, MAX_PITCH_DEGREES);
    }

    private void updateCameraDirection() {
        float cosPitch = MathUtils.cosDeg(pitchDegrees);
        camera.direction.set(
                MathUtils.cosDeg(yawDegrees) * cosPitch,
                MathUtils.sinDeg(pitchDegrees),
                MathUtils.sinDeg(yawDegrees) * cosPitch
        ).nor();
        camera.up.set(WORLD_UP);
        camera.update(true);
    }

    private void installInputProcessor() {
        InputProcessor existing = Gdx.input.getInputProcessor();
        if (existing == null) {
            Gdx.input.setInputProcessor(mouseLookProcessor);
            return;
        }
        if (existing == mouseLookProcessor) {
            return;
        }
        if (existing instanceof InputMultiplexer multiplexer) {
            multiplexer.addProcessor(mouseLookProcessor);
            return;
        }
        Gdx.input.setInputProcessor(new InputMultiplexer(existing, mouseLookProcessor));
    }

    private static final class MouseLookProcessor extends InputAdapter {
        private boolean mouseLookEnabled;
        private float pendingDeltaX;
        private float pendingDeltaY;

        @Override
        public boolean mouseMoved(int screenX, int screenY) {
            if (!mouseLookEnabled) {
                return false;
            }
            pendingDeltaX += Gdx.input.getDeltaX();
            pendingDeltaY += Gdx.input.getDeltaY();
            return false;
        }

        void setMouseLookEnabled(boolean mouseLookEnabled) {
            this.mouseLookEnabled = mouseLookEnabled;
            Gdx.input.setCursorCatched(mouseLookEnabled);
            resetDeltas();
        }

        boolean isMouseLookEnabled() {
            return mouseLookEnabled;
        }

        float drainDeltaX() {
            float result = pendingDeltaX;
            pendingDeltaX = 0f;
            return result;
        }

        float drainDeltaY() {
            float result = pendingDeltaY;
            pendingDeltaY = 0f;
            return result;
        }

        void resetDeltas() {
            pendingDeltaX = 0f;
            pendingDeltaY = 0f;
        }
    }
}
