package com.example.abyss.world.render;

import com.badlogic.gdx.math.Vector3;
import com.example.abyss.world.ChunkKey;
import com.example.abyss.world.ChunkLoader;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NearChunkStreamerTest {

    @Test
    void initialUpdateTriggersInitialSweepAndTracksSubmittedCounts() {
        FakeChunkLoader chunkLoader = new FakeChunkLoader();
        NearChunkStreamer streamer = new NearChunkStreamer(chunkLoader, new CylinderStreamer(1, 0, 0, 0));

        Vector3 observerPosition = createOriginPosition();
        streamer.updateForPosition(observerPosition, 0.016f);

        ChunkStreamingStats stats = streamer.getLastStats();
        assertTrue(stats.sweepTriggered());
        assertEquals(ChunkStreamingSweepReason.INITIAL, stats.lastSweepReason());
        // loadHRadius = hRadius+1 = 2, loadVRadius = vRadius+1 = 1 → 5*3*5 = 75
        assertEquals(75, stats.candidatesConsidered());
        assertEquals(75, stats.submittedThisUpdate());
        assertEquals(0, stats.skippedKnownThisUpdate());
        assertEquals(1L, stats.totalSweepCount());
        assertEquals(75L, stats.totalSubmittedCount());
        assertEquals(0L, stats.totalSkippedKnownCount());
    }

    @Test
    void secondIdleUpdateCountsKnownRequestsDuringIdleResweep() {
        FakeChunkLoader chunkLoader = new FakeChunkLoader();
        NearChunkStreamer streamer = new NearChunkStreamer(chunkLoader, new CylinderStreamer(1, 0, 0, 0));

        Vector3 observerPosition = createOriginPosition();
        streamer.updateForPosition(observerPosition, 0.016f);
        streamer.updateForPosition(observerPosition, 0.30f);

        ChunkStreamingStats stats = streamer.getLastStats();
        assertTrue(stats.sweepTriggered());
        assertEquals(ChunkStreamingSweepReason.IDLE_RESWEEP, stats.lastSweepReason());
        // loadHRadius = 2, loadVRadius = 1 → 5*3*5 = 75
        assertEquals(75, stats.candidatesConsidered());
        assertEquals(0, stats.submittedThisUpdate());
        assertEquals(75, stats.skippedKnownThisUpdate());
        assertEquals(2L, stats.totalSweepCount());
        assertEquals(75L, stats.totalSubmittedCount());
        assertEquals(75L, stats.totalSkippedKnownCount());
    }

    @Test
    void unloadScanStatsReflectLoaderResultsWhenIntervalExpires() {
        FakeChunkLoader chunkLoader = new FakeChunkLoader();
        chunkLoader.nextUnloadStats = new ChunkLoader.UnloadStats(3, 2);
        NearChunkStreamer streamer = new NearChunkStreamer(chunkLoader, new CylinderStreamer(1, 1, 0, 0));

        Vector3 observerPosition = createOriginPosition();
        streamer.updateForPosition(observerPosition, 0.016f);
        streamer.updateForPosition(observerPosition, 2.1f);

        ChunkStreamingStats stats = streamer.getLastStats();
        assertTrue(stats.unloadScanTriggered());
        assertEquals(3, stats.unloadedChunkCountThisUpdate());
        assertEquals(2, stats.cancelledPendingCountThisUpdate());
        assertEquals(1L, stats.totalUnloadScanCount());
        assertEquals(3L, stats.totalUnloadedChunkCount());
        assertEquals(2L, stats.totalCancelledPendingCount());
    }

    @Test
    void noSweepWhenStillAndIntervalsNotReady() {
        FakeChunkLoader chunkLoader = new FakeChunkLoader();
        NearChunkStreamer streamer = new NearChunkStreamer(chunkLoader, new CylinderStreamer(1, 0, 0, 0));

        Vector3 observerPosition = createOriginPosition();
        streamer.updateForPosition(observerPosition, 0.016f);
        streamer.updateForPosition(observerPosition, 0.01f);

        ChunkStreamingStats stats = streamer.getLastStats();
        assertFalse(stats.sweepTriggered());
        assertEquals(ChunkStreamingSweepReason.NONE, stats.lastSweepReason());
        assertEquals(0, stats.submittedThisUpdate());
        assertEquals(0, stats.skippedKnownThisUpdate());
    }

    private static Vector3 createOriginPosition() {
        return new Vector3(0f, 0f, 0f);
    }

    private static final class FakeChunkLoader extends ChunkLoader {
        private ChunkLoader.UnloadStats nextUnloadStats = new ChunkLoader.UnloadStats(0, 0);
        private final java.util.Set<ChunkKey> requestedKeys = new java.util.HashSet<>();

        private FakeChunkLoader() {
            super(null, null);
        }

        @Override
        public boolean requestChunkAsync(int chunkX, int chunkY, int chunkZ) {
            return requestedKeys.add(new ChunkKey(chunkX, chunkY, chunkZ));
        }

        @Override
        public boolean requestChunkAsync(int chunkX, int chunkY, int chunkZ, int priority) {
            return requestedKeys.add(new ChunkKey(chunkX, chunkY, chunkZ));
        }

        @Override
        public void reprioritiseChunkAsync(int chunkX, int chunkY, int chunkZ, int newPriority) {
            // no-op in fake
        }

        @Override
        public ChunkLoader.UnloadStats unloadChunks(Predicate<ChunkKey> shouldUnload) {
            return nextUnloadStats;
        }
    }
}