package com.example.abyss.world.render;

import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Vector3;
import com.example.abyss.WorldConstants;
import com.example.abyss.world.ChunkLoader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Cylinder-based chunk streaming policy.
 * <p>
 * Loads every chunk within a cylinder around the player.
 * The shaft is narrow enough that any chunk within the horizontal radius is
 * potentially visible regardless of look direction, so direction-based loading
 * only caused thrash as the player turned.
 * </p>
 * <p>
 * Unloading uses a pure positional cylinder with an asymmetric vertical window:
 * chunks above the player are evicted sooner than chunks below, matching the
 * one-directional downward travel pattern of the abyss.
 * </p>
 * <p>
 * {@link ChunkLoader} is used as a pure executor. It is only told which
 * specific coordinates to load or unload, with no knowledge of cameras.
 * </p>
 */
public class CylinderChunkStreamer {

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
  private static final float MOVEMENT_SWEEP_INTERVAL_SECONDS = 0.25f;
  /**
   * Maximum number of chunk requests submitted in a single sweep.
   * Caps queue depth so the generation workers are not flooded with thousands of tasks at once.
   */
  private static final int MAX_SUBMISSIONS_PER_SWEEP = 512;
  /**
   * How often (in seconds) a re-sweep fires unconditionally.
   * Needed because {@link #MAX_SUBMISSIONS_PER_SWEEP} caps each sweep.
   */
  private static final float IDLE_RESWEEP_INTERVAL_SECONDS = 0.25f;
  /**
   * How many chunks above the player to keep loaded.
   * Smaller than {@code vRadius} because the player is descending.
   */
  private static final int V_KEEP_ABOVE = 10;

  private final ChunkLoader chunkLoader;
  private final int hRadius;
  private final int vRadius;

  private int lastSweptPcx = Integer.MIN_VALUE;
  private int lastSweptPcy = Integer.MIN_VALUE;
  private int lastSweptPcz = Integer.MIN_VALUE;

  private final Vector3 lastPosition = new Vector3(Float.NaN, Float.NaN, Float.NaN);

  private float unloadTimer = 0f;
  private float movementSweepTimer = 0f;
  private float idleResweepTimer = IDLE_RESWEEP_INTERVAL_SECONDS;

  private final ChunkStreamingStatsTracker statsTracker = new ChunkStreamingStatsTracker();

  public CylinderChunkStreamer(ChunkLoader chunkLoader, int hRadius, int vRadius) {
    this.chunkLoader = chunkLoader;
    this.hRadius = hRadius;
    this.vRadius = vRadius;
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
    boolean playerMoving = isObserverMoving(observerPosition, deltaTime);

    movementSweepTimer += deltaTime;
    idleResweepTimer += deltaTime;
    boolean movementSweepReady = movementSweepTimer >= MOVEMENT_SWEEP_INTERVAL_SECONDS;
    boolean idleResweepReady = idleResweepTimer >= IDLE_RESWEEP_INTERVAL_SECONDS;

    ChunkStreamingSweepReason sweepReason = determineSweepReason(
        playerChunkChanged,
        playerMoving,
        movementSweepReady,
        idleResweepReady
    );
    boolean shouldResweep = sweepReason != ChunkStreamingSweepReason.NONE;
    if (!shouldResweep) {
      return SweepOutcome.notTriggered(playerChunkChanged, playerMoving, movementSweepReady, idleResweepReady);
    }

    movementSweepTimer = 0f;
    idleResweepTimer = 0f;
    lastSweptPcx = pcx;
    lastSweptPcy = pcy;
    lastSweptPcz = pcz;

    SubmissionStats submissionStats = submitCylinderCandidatesNearestFirst(pcx, pcy, pcz, observerPosition);
    return SweepOutcome.triggered(
        sweepReason,
        playerChunkChanged,
        playerMoving,
        movementSweepReady,
        idleResweepReady,
        submissionStats.candidatesConsidered(),
        submissionStats.submitted(),
        submissionStats.skippedKnown(),
        submissionStats.budgetExhausted()
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
      int dx = Math.abs(key.cx() - pcx);
      int dz = Math.abs(key.cz() - pcz);
      boolean isOutsideHorizontal = dx > hRadius + 1 || dz > hRadius + 1;
      if (isOutsideHorizontal) {
        return true;
      }

      int rawDy = key.cy() - pcy;
      boolean isTooFarAbove = rawDy > V_KEEP_ABOVE;
      boolean isTooFarBelow = rawDy < -vRadius;
      return isTooFarAbove || isTooFarBelow;
    });
    return UnloadOutcome.triggered(unloadStats.unloadedChunkCount(), unloadStats.cancelledPendingCount());
  }

  private ChunkStreamingSweepReason determineSweepReason(
      boolean playerChunkChanged,
      boolean playerMoving,
      boolean movementSweepReady,
      boolean idleResweepReady
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
    if (idleResweepReady) {
      return ChunkStreamingSweepReason.IDLE_RESWEEP;
    }
    return ChunkStreamingSweepReason.NONE;
  }

  private boolean isObserverMoving(Vector3 currentPosition, float deltaTime) {
    boolean lastPositionKnown = !Float.isNaN(lastPosition.x);
    float prevX = lastPosition.x;
    float prevY = lastPosition.y;
    float prevZ = lastPosition.z;
    lastPosition.set(currentPosition);
    if (!lastPositionKnown) {
      return false;
    }
    float dx = currentPosition.x - prevX;
    float dy = currentPosition.y - prevY;
    float dz = currentPosition.z - prevZ;
    float distanceMoved = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    float speedThisFrame = deltaTime > 0f ? distanceMoved / deltaTime : 0f;
    return speedThisFrame > MOVEMENT_SWEEP_THRESHOLD;
  }

  private SubmissionStats submitCylinderCandidatesNearestFirst(int pcx, int pcy, int pcz, Vector3 camPos) {
    float chunkSize = WorldConstants.CHUNK_SIZE;
    float camX = camPos.x;
    float camY = camPos.y;
    float camZ = camPos.z;

    List<int[]> candidates = new ArrayList<>();
    for (int dy = -vRadius; dy <= vRadius; dy++) {
      for (int dz = -hRadius; dz <= hRadius; dz++) {
        for (int dx = -hRadius; dx <= hRadius; dx++) {
          candidates.add(new int[]{pcx + dx, pcy + dy, pcz + dz});
        }
      }
    }

    candidates.sort(Comparator.comparingDouble(candidate -> {
      float cx = candidate[0] * chunkSize + chunkSize * 0.5f;
      float cy = candidate[1] * chunkSize + chunkSize * 0.5f;
      float cz = candidate[2] * chunkSize + chunkSize * 0.5f;
      float dx = cx - camX;
      float dy = cy - camY;
      float dz = cz - camZ;
      return dx * dx + dy * dy + dz * dz;
    }));

    int submitted = 0;
    int skippedKnown = 0;
    int candidatesConsidered = 0;
    boolean budgetExhausted = false;
    for (int[] candidate : candidates) {
      boolean isBudgetExhausted = submitted >= MAX_SUBMISSIONS_PER_SWEEP;
      if (isBudgetExhausted) {
        budgetExhausted = true;
        break;
      }
      candidatesConsidered++;
      boolean wasNew = chunkLoader.requestChunkAsync(candidate[0], candidate[1], candidate[2]);
      if (wasNew) {
        submitted++;
      }
      else {
        skippedKnown++;
      }
    }
    return new SubmissionStats(candidatesConsidered, submitted, skippedKnown, budgetExhausted);
  }

  private record SubmissionStats(
      int candidatesConsidered,
      int submitted,
      int skippedKnown,
      boolean budgetExhausted
  ) {
  }

  record SweepOutcome(
      ChunkStreamingSweepReason reason,
      boolean sweepTriggered,
      boolean playerChunkChanged,
      boolean playerMoving,
      boolean movementSweepReady,
      boolean idleResweepReady,
      int candidatesConsidered,
      int submitted,
      int skippedKnown,
      boolean budgetExhausted
  ) {
    private static SweepOutcome notTriggered(
        boolean playerChunkChanged,
        boolean playerMoving,
        boolean movementSweepReady,
        boolean idleResweepReady
    ) {
      return new SweepOutcome(
          ChunkStreamingSweepReason.NONE,
          false,
          playerChunkChanged,
          playerMoving,
          movementSweepReady,
          idleResweepReady,
          0,
          0,
          0,
          false
      );
    }

    private static SweepOutcome triggered(
        ChunkStreamingSweepReason reason,
        boolean playerChunkChanged,
        boolean playerMoving,
        boolean movementSweepReady,
        boolean idleResweepReady,
        int candidatesConsidered,
        int submitted,
        int skippedKnown,
        boolean budgetExhausted
    ) {
      return new SweepOutcome(
          reason,
          true,
          playerChunkChanged,
          playerMoving,
          movementSweepReady,
          idleResweepReady,
          candidatesConsidered,
          submitted,
          skippedKnown,
          budgetExhausted
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