package com.example.abyss.debug.viewer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirstPersonDebugControllerTest {

    @Test
    void controlSummaryReflectsFirstPersonBindings() {
        String summary = FirstPersonDebugController.getControlSummary();

        assertTrue(summary.contains("Click"));
        assertTrue(summary.contains("WASD"));
        assertTrue(summary.contains("Space"));
        assertTrue(summary.contains("Ctrl"));
        assertTrue(summary.contains("Shift"));
        assertTrue(summary.contains("Esc"));
    }

    @Test
    void clampPitchLimitsToSafeMouseLookRange() {
        assertEquals(-89f, FirstPersonDebugController.clampPitch(-120f));
        assertEquals(89f, FirstPersonDebugController.clampPitch(120f));
        assertEquals(12.5f, FirstPersonDebugController.clampPitch(12.5f));
    }
}
