package com.example.abyss.world.render;

public record ChunkStreamingStats(
    ChunkStreamingSweepReason lastSweepReason,
    boolean sweepTriggered,
    boolean playerChunkChanged,
    boolean playerMoving,
    boolean movementSweepReady,
    int candidatesConsidered,
    int submittedThisUpdate,
    int skippedKnownThisUpdate,
    boolean unloadScanTriggered,
    int unloadedChunkCountThisUpdate,
    int cancelledPendingCountThisUpdate,
    long totalSweepCount,
    long totalSubmittedCount,
    long totalSkippedKnownCount,
    long totalUnloadScanCount,
    long totalUnloadedChunkCount,
    long totalCancelledPendingCount
) {
  static ChunkStreamingStats initial() {
    return new ChunkStreamingStats(
        ChunkStreamingSweepReason.NONE,
        false,
        false,
        false,
        false,
        0,
        0,
        0,
        false,
        0,
        0,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L
    );
  }
}
