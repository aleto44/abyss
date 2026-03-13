package com.example.abyss.debug.viewer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugSliceViewerControllerTest {

    @Test
    void controlSummaryReflectsSharedBindings() {
        String summary = DebugSliceViewerController.getControlSummary();

        assertTrue(summary.contains("WASD"));
        assertTrue(summary.contains("Space"));
        assertTrue(summary.contains("Ctrl"));
        assertTrue(summary.contains("Mouse wheel"));
    }

    @Test
    void inputResultReportsWhetherTheViewChanged() {
        DebugSliceViewerController.InputResult unchanged = new DebugSliceViewerController.InputResult(false, false);
        DebugSliceViewerController.InputResult movedOnly = new DebugSliceViewerController.InputResult(true, false);
        DebugSliceViewerController.InputResult slicedOnly = new DebugSliceViewerController.InputResult(false, true);

        assertFalse(unchanged.changedView());
        assertTrue(movedOnly.changedView());
        assertTrue(slicedOnly.changedView());
        assertTrue(new DebugSliceViewerController.InputResult(true, true).changedView());
    }
}
