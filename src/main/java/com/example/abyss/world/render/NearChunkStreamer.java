package com.example.abyss.world.render;

import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Vector3;
import com.example.abyss.WorldConstants;
import com.example.abyss.world.ChunkLoader;

import java.util.ArrayList;
import java.util.List;

/**
 * Cylinder-based near-field chunk streaming policy.
 * <p>
 * Loads every chunk within a cylinder around the player.
 * The shaft is narrow enough that any chunk within the horizontal radius is
 * potentially visible regardless of look direction, so direction-based loading
 * only caused thrash as the player turned.
 * </p>
 * <p>
 * Unloading uses a pure positional cylinder centered on the player with symmetric
 * vertical bounds, supporting vertical exploration in both directions through the abyss.
 * </p>
 * <p>
 * {@link ChunkLoader} is used as a pure executor. It is only told which
 * specific coordinates to load or unload, with no knowledge of cameras.
 * </p>
 */
public class NearChunkStreamer {

  /**
   * How often (in seconds) the unload scan runs.
   */
  private static final float UNLOAD_INTERVAL_SECONDS = 2.0f;
  /**
   * Minimum world-units-per-second of camera movement that marks the observer as moving.
   * Does not mean a sweep fires every frame. See {@link #MOVEMENT_SWEEP_INTERVAL_SECONDS}.
   */
  private static final float MOVEMENT_SWEEP_THRESHOLD = 1.0f;
  /**
   * Minimum seconds between movement-triggered sweeps.
   * Chunk-boundary crossings still trigger an immediate sweep.
   */
  private static final float MOVEMENT_SWEEP_INTERVAL_SECONDS = 0.1f;

  private final ChunkLoader chunkLoader;
  private final CylinderStreamer cylinderStreamer;

  private int lastSweptPcx = Integer.MIN_VALUE;
  private int lastSweptPcy = Integer.MIN_VALUE;
  private int lastSweptPcz = Integer.MIN_VALUE;

  private float unloadTimer = 0f;
  private float movementSweepTimer = 0f;

  private final ChunkStreamingStatsTracker statsTracker = new ChunkStreamingStatsTracker();

  public NearChunkStreamer(ChunkLoader chunkLoader, CylinderStreamer cylinderStreamer) {
    this.chunkLoader = chunkLoader;
    this.cylinderStreamer = cylinderStreamer;
  }

  /**
   * Call once per frame. Submits chunks in the cylinder for async generation
   * and periodically evicts chunks outside the keep zone.
   */
  public void update(PerspectiveCamera camera, float deltaTime) {
    updateForPosition(camera.position, deltaTime);
  }

  public ChunkStreamingStats getLastStats() {
    return statsTracker.getLastStats();
  }

  void updateForPosition(Vector3 observerPosition, float deltaTime) {
    SweepOutcome sweepOutcome = sweepCylinderChunks(observerPosition, deltaTime);
    UnloadOutcome unloadOutcome = maybeUnloadDistantChunks(observerPosition, deltaTime);
    statsTracker.recordUpdate(sweepOutcome, unloadOutcome);
  }

  private SweepOutcome sweepCylinderChunks(Vector3 observerPosition, float deltaTime) {
    int worldX = Math.round(observerPosition.x);
    int worldY = Math.round(observerPosition.y);
    int worldZ = Math.round(observerPosition.z);

    int pcx = Math.floorDiv(worldX, WorldConstants.CHUNK_SIZE);
    int pcy = Math.floorDiv(worldY, WorldConstants.CHUNK_SIZE);
    int pcz = Math.floorDiv(worldZ, WorldConstants.CHUNK_SIZE);

    boolean playerChunkChanged = pcx != lastSweptPcx || pcy != lastSweptPcy || pcz != lastSweptPcz;
    boolean playerMoving = cylinderStreamer.updateAndCheckMovement(observerPosition, deltaTime, MOVEMENT_SWEEP_THRESHOLD);

    movementSweepTimer += deltaTime;
    boolean movementSweepReady = movementSweepTimer >= MOVEMENT_SWEEP_INTERVAL_SECONDS;

    ChunkStreamingSweepReason sweepReason = determineSweepReason(
        playerChunkChanged,
        playerMoving,
        movementSweepReady
    );
    boolean shouldResweep = sweepReason != ChunkStreamingSweepReason.NONE;
    if (!shouldResweep) {
      return SweepOutcome.notTriggered(playerChunkChanged, playerMoving, movementSweepReady);
    }

    movementSweepTimer = 0f;
    lastSweptPcx = pcx;
    lastSweptPcy = pcy;
    lastSweptPcz = pcz;

    SubmissionStats submissionStats = submitCylinderCandidatesNearestFirst(pcx, pcy, pcz, observerPosition);
    return SweepOutcome.triggered(
        sweepReason,
        playerChunkChanged,
        playerMoving,
        movementSweepReady,
        submissionStats.candidatesConsidered(),
        submissionStats.submitted(),
        submissionStats.skippedKnown()
    );
  }

  private UnloadOutcome maybeUnloadDistantChunks(Vector3 observerPosition, float deltaTime) {
    unloadTimer += deltaTime;
    boolean isTimeToUnload = unloadTimer >= UNLOAD_INTERVAL_SECONDS;
    if (!isTimeToUnload) {
      return UnloadOutcome.notTriggered();
    }
    unloadTimer = 0f;

    int worldX = Math.round(observerPosition.x);
    int worldY = Math.round(observerPosition.y);
    int worldZ = Math.round(observerPosition.z);
    int pcx = Math.floorDiv(worldX, WorldConstants.CHUNK_SIZE);
    int pcy = Math.floorDiv(worldY, WorldConstants.CHUNK_SIZE);
    int pcz = Math.floorDiv(worldZ, WorldConstants.CHUNK_SIZE);

    ChunkLoader.UnloadStats unloadStats = chunkLoader.unloadChunks(key -> {
      int deltaX = key.cx() - pcx;
      int deltaY = key.cy() - pcy;
      int deltaZ = key.cz() - pcz;
      return cylinderStreamer.isOutsideKeepZone(deltaX, deltaY, deltaZ, 1, 1);
    });
    return UnloadOutcome.triggered(unloadStats.unloadedChunkCount(), unloadStats.cancelledPendingCount());
  }

  private ChunkStreamingSweepReason determineSweepReason(
      boolean playerChunkChanged,
      boolean playerMoving,
      boolean movementSweepReady
  ) {
    boolean isInitialSweep = lastSweptPcx == Integer.MIN_VALUE;
    if (isInitialSweep) {
      return ChunkStreamingSweepReason.INITIAL;
    }
    if (playerChunkChanged) {
      return ChunkStreamingSweepReason.CHUNK_CHANGED;
    }
    boolean isMovementSweep = playerMoving && movementSweepReady;
    if (isMovementSweep) {
      return ChunkStreamingSweepReason.MOVING;
    }
    return ChunkStreamingSweepReason.NONE;
  }

  private SubmissionStats submitCylinderCandidatesNearestFirst(int pcx, int pcy, int pcz, Vector3 camPos) {
    List<int[]> candidates = new ArrayList<>();
    int horizontalRadius = cylinderStreamer.getHRadius();
    int verticalRadius = cylinderStreamer.getVRadius();
    // Load one extra shell beyond the visible radius so that every chunk on the
    // outermost renderable ring has all six face-neighbours present. Without these
    // border chunks the SameResolutionNeighbourPolicy blocks meshing on the boundary
    // layer, making the outermost radius worth of chunks permanently invisible.
    int loadHRadius = horizontalRadius + 1;
    int loadVRadius = verticalRadius + 1;
    for (int dy = -loadVRadius; dy <= loadVRadius; dy++) {
      for (int dz = -loadHRadius; dz <= loadHRadius; dz++) {
        for (int dx = -loadHRadius; dx <= loadHRadius; dx++) {
          candidates.add(new int[]{pcx + dx, pcy + dy, pcz + dz});
        }
      }
    }

    candidates.sort(cylinderStreamer.createNearestFirstComparator(WorldConstants.CHUNK_SIZE, camPos));

    int skippedKnown = 0;
    int submitted = 0;
    for (int[] candidate : candidates) {
      boolean wasNew = chunkLoader.requestChunkAsync(candidate[0], candidate[1], candidate[2]);
      if (wasNew) {
        submitted++;
      } else {
        skippedKnown++;
      }
    }
    return new SubmissionStats(candidates.size(), submitted, skippedKnown);
  }

  private record SubmissionStats(
      int candidatesConsidered,
      int submitted,
      int skippedKnown
  ) {
  }

  record SweepOutcome(
      ChunkStreamingSweepReason reason,
      boolean sweepTriggered,
      boolean playerChunkChanged,
      boolean playerMoving,
      boolean movementSweepReady,
      int candidatesConsidered,
      int submitted,
      int skippedKnown
  ) {
    private static SweepOutcome notTriggered(
        boolean playerChunkChanged,
        boolean playerMoving,
        boolean movementSweepReady
    ) {
      return new SweepOutcome(
          ChunkStreamingSweepReason.NONE,
          false,
          playerChunkChanged,
          playerMoving,
          movementSweepReady,
          0,
          0,
          0
      );
    }

    private static SweepOutcome triggered(
        ChunkStreamingSweepReason reason,
        boolean playerChunkChanged,
        boolean playerMoving,
        boolean movementSweepReady,
        int candidatesConsidered,
        int submitted,
        int skippedKnown
    ) {
      return new SweepOutcome(
          reason,
          true,
          playerChunkChanged,
          playerMoving,
          movementSweepReady,
          candidatesConsidered,
          submitted,
          skippedKnown
      );
    }
  }

  record UnloadOutcome(
      boolean scanTriggered,
      int unloadedChunkCount,
      int cancelledPendingCount
  ) {
    private static UnloadOutcome notTriggered() {
      return new UnloadOutcome(false, 0, 0);
    }

    private static UnloadOutcome triggered(int unloadedChunkCount, int cancelledPendingCount) {
      return new UnloadOutcome(true, unloadedChunkCount, cancelledPendingCount);
    }
  }
}
