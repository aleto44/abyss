package com.example.abyss.world.render;

final class ChunkStreamingStatsTracker {

  private ChunkStreamingStats lastStats = ChunkStreamingStats.initial();
  private long totalSweepCount;
  private long totalSubmittedCount;
  private long totalSkippedKnownCount;
  private long totalUnloadScanCount;
  private long totalUnloadedChunkCount;
  private long totalCancelledPendingCount;

  ChunkStreamingStats getLastStats() {
    return lastStats;
  }

  void recordUpdate(
      NearChunkStreamer.SweepOutcome sweepOutcome,
      NearChunkStreamer.UnloadOutcome unloadOutcome
  ) {
    boolean sweepTriggered = sweepOutcome.sweepTriggered();
    if (sweepTriggered) {
      totalSweepCount++;
      totalSubmittedCount += sweepOutcome.submitted();
      totalSkippedKnownCount += sweepOutcome.skippedKnown();
    }

    boolean unloadScanTriggered = unloadOutcome.scanTriggered();
    if (unloadScanTriggered) {
      totalUnloadScanCount++;
      totalUnloadedChunkCount += unloadOutcome.unloadedChunkCount();
      totalCancelledPendingCount += unloadOutcome.cancelledPendingCount();
    }

    lastStats = new ChunkStreamingStats(
        sweepOutcome.reason(),
        sweepOutcome.sweepTriggered(),
        sweepOutcome.playerChunkChanged(),
        sweepOutcome.playerMoving(),
        sweepOutcome.movementSweepReady(),
        sweepOutcome.candidatesConsidered(),
        sweepOutcome.submitted(),
        sweepOutcome.skippedKnown(),
        unloadOutcome.scanTriggered(),
        unloadOutcome.unloadedChunkCount(),
        unloadOutcome.cancelledPendingCount(),
        totalSweepCount,
        totalSubmittedCount,
        totalSkippedKnownCount,
        totalUnloadScanCount,
        totalUnloadedChunkCount,
        totalCancelledPendingCount
    );
  }
}