package com.example.abyss.debug.viewer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FirstPersonDebugControllerTest {

    @Test
    void clampPitchLimitsToSafeMouseLookRange() {
        assertEquals(-89f, FirstPersonDebugController.clampPitch(-120f));
        assertEquals(89f, FirstPersonDebugController.clampPitch(120f));
        assertEquals(12.5f, FirstPersonDebugController.clampPitch(12.5f));
    }
}