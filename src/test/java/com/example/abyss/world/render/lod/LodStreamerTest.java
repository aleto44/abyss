package com.example.abyss.world.render.lod;

import com.badlogic.gdx.math.Vector3;
import com.example.abyss.world.render.CylinderStreamer;
import com.example.abyss.world.render.LodRenderEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodStreamerTest {

    @Test
    void constructorAcceptsSharedCylinderStreamerWithoutNearChunkStreamerDependency() {
        FakeLodProxyManager proxyManager = new FakeLodProxyManager();
        CylinderStreamer cylinderStreamer = new CylinderStreamer(14, 8, 20, 12);
        LodStreamer streamer = new LodStreamer(proxyManager, cylinderStreamer);

        streamer.updateForPosition(new Vector3(0f, 0f, 0f), 0.016f);

        assertTrue(proxyManager.polledCompletedMeshes);
        assertFalse(proxyManager.requestedKeys.isEmpty());
    }

    private static final class FakeLodProxyManager extends LodProxyManager {
        private final List<LodRegionKey> requestedKeys = new ArrayList<>();
        private boolean polledCompletedMeshes;

        @Override
        public void pollCompletedMeshes() {
            polledCompletedMeshes = true;
        }

        @Override
        public boolean isRegionKnown(LodRegionKey key) {
            return false;
        }

        @Override
        public boolean requestProxyMeshAsync(LodRegionKey key) {
            requestedKeys.add(key);
            return true;
        }

        @Override
        public List<LodRenderEntry> getRenderEntries() {
            return List.of();
        }
    }
}
