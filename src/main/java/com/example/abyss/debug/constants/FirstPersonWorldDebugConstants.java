package com.example.abyss.debug.constants;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector3;

/**
 * Shared configuration constants for the first-person world debug viewer.
 */
public final class FirstPersonWorldDebugConstants {

    public static final long WORLD_SEED = 42L;
    public static final int INITIAL_HORIZONTAL_RADIUS = 8;
    public static final int INITIAL_VERTICAL_RADIUS = 2;
    public static final float CAMERA_FOV_DEGREES = 75f;
    public static final float CAMERA_NEAR = 0.1f;
    public static final float CAMERA_FAR = 1200f;
    public static final float MOVE_SPEED_UNITS_PER_SECOND = 48f;
    public static final float FAST_MOVE_MULTIPLIER = 3f;
    public static final float MOUSE_SENSITIVITY_DEGREES = 0.15f;
    public static final Vector3 START_POSITION = new Vector3(0f, 0f, 0f);
    public static final Color SKY_COLOR = new Color(0.03f, 0.04f, 0.08f, 1f);

    public static final String VERTEX_SHADER_SOURCE = """
            attribute vec3 a_position;
            attribute vec3 a_normal;
            attribute vec4 a_color;
            uniform mat4 u_projTrans;
            varying vec4 v_color;
            varying float v_light;
            void main() {
                vec3 lightDir = normalize(vec3(0.45, 0.8, 0.35));
                v_light = max(dot(normalize(a_normal), lightDir), 0.2);
                v_color = a_color;
                gl_Position = u_projTrans * vec4(a_position, 1.0);
            }
            """;

    public static final String FRAGMENT_SHADER_SOURCE = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec4 v_color;
            varying float v_light;
            void main() {
                gl_FragColor = vec4(v_color.rgb * v_light, v_color.a);
            }
            """;

    private FirstPersonWorldDebugConstants() {
    }
}
