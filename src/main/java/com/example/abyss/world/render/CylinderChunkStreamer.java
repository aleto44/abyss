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

    /** How often (in seconds) the unload scan runs. */
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

    private StreamerStats lastStats = StreamerStats.initial();
    private long totalSweepCount;
    private long totalSubmittedCount;
    private long totalSkippedKnownCount;
    private long totalUnloadScanCount;
    private long totalUnloadedChunkCount;
    private long totalCancelledPendingCount;

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
        SweepOutcome sweepOutcome = sweepCylinderChunks(camera, deltaTime);
        UnloadOutcome unloadOutcome = maybeUnloadDistantChunks(camera, deltaTime);
        lastStats = new StreamerStats(
                sweepOutcome.reason(),
                sweepOutcome.sweepTriggered(),
                sweepOutcome.playerChunkChanged(),
                sweepOutcome.playerMoving(),
                sweepOutcome.movementSweepReady(),
                sweepOutcome.idleResweepReady(),
                sweepOutcome.candidatesConsidered(),
                sweepOutcome.submitted(),
                sweepOutcome.skippedKnown(),
                sweepOutcome.budgetExhausted(),
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

    public StreamerStats getLastStats() {
        return lastStats;
    }

    void updateForPosition(Vector3 observerPosition, float deltaTime) {
        SweepOutcome sweepOutcome = sweepCylinderChunks(observerPosition, deltaTime);
        UnloadOutcome unloadOutcome = maybeUnloadDistantChunks(observerPosition, deltaTime);
        lastStats = new StreamerStats(
                sweepOutcome.reason(),
                sweepOutcome.sweepTriggered(),
                sweepOutcome.playerChunkChanged(),
                sweepOutcome.playerMoving(),
                sweepOutcome.movementSweepReady(),
                sweepOutcome.idleResweepReady(),
                sweepOutcome.candidatesConsidered(),
                sweepOutcome.submitted(),
                sweepOutcome.skippedKnown(),
                sweepOutcome.budgetExhausted(),
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

    private SweepOutcome sweepCylinderChunks(PerspectiveCamera camera, float deltaTime) {
        int worldX = Math.round(camera.position.x);
        int worldY = Math.round(camera.position.y);
        int worldZ = Math.round(camera.position.z);

        int pcx = Math.floorDiv(worldX, WorldConstants.CHUNK_SIZE);
        int pcy = Math.floorDiv(worldY, WorldConstants.CHUNK_SIZE);
        int pcz = Math.floorDiv(worldZ, WorldConstants.CHUNK_SIZE);

        boolean playerChunkChanged = pcx != lastSweptPcx || pcy != lastSweptPcy || pcz != lastSweptPcz;
        boolean playerMoving = isObserverMoving(camera.position, deltaTime);

        movementSweepTimer += deltaTime;
        idleResweepTimer += deltaTime;
        boolean movementSweepReady = movementSweepTimer >= MOVEMENT_SWEEP_INTERVAL_SECONDS;
        boolean idleResweepReady = idleResweepTimer >= IDLE_RESWEEP_INTERVAL_SECONDS;

        SweepReason sweepReason = determineSweepReason(
                playerChunkChanged,
                playerMoving,
                movementSweepReady,
                idleResweepReady
        );
        boolean shouldResweep = sweepReason != SweepReason.NONE;
        if (!shouldResweep) {
            return SweepOutcome.notTriggered(playerChunkChanged, playerMoving, movementSweepReady, idleResweepReady);
        }

        movementSweepTimer = 0f;
        idleResweepTimer = 0f;
        lastSweptPcx = pcx;
        lastSweptPcy = pcy;
        lastSweptPcz = pcz;

        SubmissionStats submissionStats = submitCylinderCandidatesNearestFirst(pcx, pcy, pcz, camera.position);
        totalSweepCount++;
        totalSubmittedCount += submissionStats.submitted();
        totalSkippedKnownCount += submissionStats.skippedKnown();
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

        SweepReason sweepReason = determineSweepReason(
                playerChunkChanged,
                playerMoving,
                movementSweepReady,
                idleResweepReady
        );
        boolean shouldResweep = sweepReason != SweepReason.NONE;
        if (!shouldResweep) {
            return SweepOutcome.notTriggered(playerChunkChanged, playerMoving, movementSweepReady, idleResweepReady);
        }

        movementSweepTimer = 0f;
        idleResweepTimer = 0f;
        lastSweptPcx = pcx;
        lastSweptPcy = pcy;
        lastSweptPcz = pcz;

        SubmissionStats submissionStats = submitCylinderCandidatesNearestFirst(pcx, pcy, pcz, observerPosition);
        totalSweepCount++;
        totalSubmittedCount += submissionStats.submitted();
        totalSkippedKnownCount += submissionStats.skippedKnown();
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

    private SweepReason determineSweepReason(
            boolean playerChunkChanged,
            boolean playerMoving,
            boolean movementSweepReady,
            boolean idleResweepReady
    ) {
        boolean isInitialSweep = lastSweptPcx == Integer.MIN_VALUE;
        if (isInitialSweep) {
            return SweepReason.INITIAL;
        }
        if (playerChunkChanged) {
            return SweepReason.CHUNK_CHANGED;
        }
        boolean isMovementSweep = playerMoving && movementSweepReady;
        if (isMovementSweep) {
            return SweepReason.MOVING;
        }
        if (idleResweepReady) {
            return SweepReason.IDLE_RESWEEP;
        }
        return SweepReason.NONE;
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
            } else {
                skippedKnown++;
            }
        }
        return new SubmissionStats(candidatesConsidered, submitted, skippedKnown, budgetExhausted);
    }

    private UnloadOutcome maybeUnloadDistantChunks(PerspectiveCamera camera, float deltaTime) {
        unloadTimer += deltaTime;
        boolean isTimeToUnload = unloadTimer >= UNLOAD_INTERVAL_SECONDS;
        if (!isTimeToUnload) {
            return UnloadOutcome.notTriggered();
        }
        unloadTimer = 0f;

        int worldX = Math.round(camera.position.x);
        int worldY = Math.round(camera.position.y);
        int worldZ = Math.round(camera.position.z);
        int pcx = Math.floorDiv(worldX, WorldConstants.CHUNK_SIZE);
        int pcy = Math.floorDiv(worldY, WorldConstants.CHUNK_SIZE);
        int pcz = Math.floorDiv(worldZ, WorldConstants.CHUNK_SIZE);

        com.example.abyss.world.ChunkLoader.UnloadStats unloadStats = chunkLoader.unloadChunks(key -> {
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
        totalUnloadScanCount++;
        totalUnloadedChunkCount += unloadStats.unloadedChunkCount();
        totalCancelledPendingCount += unloadStats.cancelledPendingCount();
        return UnloadOutcome.triggered(unloadStats.unloadedChunkCount(), unloadStats.cancelledPendingCount());
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

        com.example.abyss.world.ChunkLoader.UnloadStats unloadStats = chunkLoader.unloadChunks(key -> {
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
        totalUnloadScanCount++;
        totalUnloadedChunkCount += unloadStats.unloadedChunkCount();
        totalCancelledPendingCount += unloadStats.cancelledPendingCount();
        return UnloadOutcome.triggered(unloadStats.unloadedChunkCount(), unloadStats.cancelledPendingCount());
    }

    public enum SweepReason {
        NONE,
        INITIAL,
        CHUNK_CHANGED,
        MOVING,
        IDLE_RESWEEP
    }

    public record StreamerStats(
            SweepReason lastSweepReason,
            boolean sweepTriggered,
            boolean playerChunkChanged,
            boolean playerMoving,
            boolean movementSweepReady,
            boolean idleResweepReady,
            int candidatesConsidered,
            int submittedThisUpdate,
            int skippedKnownThisUpdate,
            boolean budgetExhausted,
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
        private static StreamerStats initial() {
            return new StreamerStats(
                    SweepReason.NONE,
                    false,
                    false,
                    false,
                    false,
                    false,
                    0,
                    0,
                    0,
                    false,
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

    private record SubmissionStats(
            int candidatesConsidered,
            int submitted,
            int skippedKnown,
            boolean budgetExhausted
    ) {
    }

    private record SweepOutcome(
            SweepReason reason,
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
                    SweepReason.NONE,
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
                SweepReason reason,
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

    private record UnloadOutcome(
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
