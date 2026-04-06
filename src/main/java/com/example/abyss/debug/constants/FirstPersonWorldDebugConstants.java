package com.example.abyss.debug.constants;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector3;
import com.example.abyss.WorldConstants;

/**
 * Shared configuration constants for the first-person world debug viewer.
 */
public final class FirstPersonWorldDebugConstants {

    public static final long WORLD_SEED = 42L;
    public static final int INITIAL_HORIZONTAL_RADIUS = WorldConstants.NEAR_FIELD_H_RADIUS;
    public static final int INITIAL_VERTICAL_RADIUS   = WorldConstants.NEAR_FIELD_V_RADIUS;
    public static final float CAMERA_FOV_DEGREES = 75f;
    public static final float CAMERA_NEAR = 0.1f;
    public static final float CAMERA_FAR = 4000f;
    public static final float MOVE_SPEED_UNITS_PER_SECOND = 48f;
    public static final float FAST_MOVE_MULTIPLIER = 3f;
    public static final float MOUSE_SENSITIVITY_DEGREES = 0.15f;
    // Positioned just inside the Edge wall face at the Forest top boundary (y ≈ -490)
    // so the ceiling flare is immediately visible on launch. The wall inner face sits at
    // roughly r=656 at this depth; x=580 places us 76 voxels inside it, looking inward.
    public static final Vector3 START_POSITION = new Vector3(500f, 300f, 0f);
    public static final Color SKY_COLOR = new Color(0.45f, 0.68f, 0.92f, 1f);

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
            uniform vec3 u_lodTint;
            void main() {
                vec3 lit = v_color.rgb * v_light;
                gl_FragColor = vec4(lit * u_lodTint, v_color.a);
            }
            """;

    private FirstPersonWorldDebugConstants() {
    }
}
