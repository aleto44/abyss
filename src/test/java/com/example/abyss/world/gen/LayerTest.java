package com.example.abyss.world.gen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayerTest {

    @Test
    void forYClampsAboveAndBelowDefinedRanges() {
        assertEquals(Layer.SURFACE, Layer.forY(Layer.SURFACE.getYStart() + 200));
        assertEquals(Layer.DEEP_EXTREMES, Layer.forY(Layer.DEEP_EXTREMES.getYEnd() - 200));
    }

    @Test
    void forYCoversEveryLayerInteriorWithoutGapsOrWrongAssignments() {
        for (Layer layer : Layer.values()) {
            int interiorY = layer.getYStart() == layer.getYEnd()
                    ? layer.getYStart()
                    : layer.getYEnd() + Math.max(1, (layer.getYStart() - layer.getYEnd()) / 2);
            assertEquals(layer, Layer.forY(interiorY), "Expected interior y to resolve to " + layer);
        }
    }

    @Test
    void forYHandsBoundaryToDeeperLayer() {
        Layer[] layers = Layer.values();
        for (int i = 0; i < layers.length - 1; i++) {
            int boundaryY = layers[i].getYEnd();
            assertEquals(layers[i + 1], Layer.forY(boundaryY), "Boundary should resolve to deeper layer at y=" + boundaryY);
            assertEquals(layers[i], Layer.forY(boundaryY + 1), "One voxel above boundary should stay in shallower layer at y=" + (boundaryY + 1));
        }
    }

    @Test
    void getTransitionReturnsNullWellInsideEachLayerInterior() {
        for (Layer layer : Layer.values()) {
            int interiorY = layer.getYStart() == layer.getYEnd()
                    ? layer.getYStart()
                    : layer.getYEnd() + Math.max(1, (layer.getYStart() - layer.getYEnd()) / 2);
            assertNull(Layer.getTransition(interiorY), "Interior y should not be in transition zone for " + layer);
        }
    }

    @Test
    void getTransitionLinksAdjacentLayersAndBlendMovesDeeperAcrossBoundary() {
        Layer[] layers = Layer.values();
        for (int i = 0; i < layers.length - 1; i++) {
            int boundaryY = layers[i].getYEnd();
            Layer.TransitionInfo above = Layer.getTransition(boundaryY + 1);
            Layer.TransitionInfo at = Layer.getTransition(boundaryY);
            Layer.TransitionInfo below = Layer.getTransition(boundaryY - 1);

            assertNotNull(above);
            assertNotNull(at);
            assertNotNull(below);

            assertEquals(layers[i], above.upper());
            assertEquals(layers[i + 1], above.lower());
            assertEquals(layers[i], at.upper());
            assertEquals(layers[i + 1], at.lower());
            assertEquals(layers[i], below.upper());
            assertEquals(layers[i + 1], below.lower());

            assertTrue(above.blendFactor() < at.blendFactor(), "Blend should increase as Y moves deeper near boundary " + boundaryY);
            assertTrue(at.blendFactor() < below.blendFactor(), "Blend should keep increasing past boundary " + boundaryY);
            assertTrue(above.blendFactor() >= 0f && below.blendFactor() <= 1f);
        }
    }

    @Test
    void surfaceBoundaryTransitionsIntoTheEdge() {
        int boundaryY = Layer.SURFACE.getYEnd();

        assertEquals(Layer.THE_EDGE, Layer.forY(boundaryY), "Surface floor should hand off to The Edge at y=" + boundaryY);
        assertEquals(Layer.SURFACE, Layer.forY(boundaryY + 1), "One voxel above the handoff should remain in Surface.");

        Layer.TransitionInfo transitionAbove = Layer.getTransition(boundaryY + 1);
        Layer.TransitionInfo transitionAt = Layer.getTransition(boundaryY);
        Layer.TransitionInfo transitionBelow = Layer.getTransition(boundaryY - 1);

        assertNotNull(transitionAbove);
        assertNotNull(transitionAt);
        assertNotNull(transitionBelow);
        assertEquals(Layer.SURFACE, transitionAbove.upper());
        assertEquals(Layer.THE_EDGE, transitionAbove.lower());
        assertTrue(transitionAbove.blendFactor() < transitionAt.blendFactor());
        assertTrue(transitionAt.blendFactor() < transitionBelow.blendFactor());
    }
}