package com.example.abyss.world.render.distant;

import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Vector3;
import com.example.abyss.world.render.CylinderStreamer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Horizon-expanding streaming policy for the distant terrain background layer.
 * <p>
 * Unlike the old band-swap model, this streamer <strong>never removes regions during
 * normal play</strong>. Regions are loaded once and remain resident as the player
 * moves. The horizon grows outward; it never shrinks.
 * </p>
 * <p>
 * Sweeps are gated on the player crossing a region-grid boundary. Because distant
 * regions are {@link DistantTerrainConfig#REGION_SIZE_VOXELS} voxels wide, a
 * boundary crossing is infrequent during normal movement. Between crossings the
 * queue is already full of the correct work; re-iterating the full candidate space
 * achieves nothing and was the primary cause of main-thread freezes.
 * </p>
 * <p>
 * The only removal path is the infrequent safety-eviction scan, which fires every
 * {@link DistantTerrainConfig#EVICTION_INTERVAL_SECONDS} seconds and removes regions
 * that have drifted beyond {@link DistantTerrainConfig#EVICTION_H_RADIUS_REGIONS}.
 * This is a last-resort VRAM guard, not a streaming rhythm.
 * </p>
 */
public class DistantTerrainStreamer {

    private static final float MOVEMENT_SWEEP_THRESHOLD        = 1.0f;
    private static final float MOVEMENT_SWEEP_INTERVAL_SECONDS = 0.1f;
    private static final float IDLE_RESWEEP_INTERVAL_SECONDS   = 0.1f;

    /** Refill the queue whenever in-flight tasks drop to this fraction of the cap. */
    private static final float QUEUE_LOW_WATER_FRACTION = 0.5f;

    /** Sentinel value meaning "no region swept yet". */
    private static final int NO_REGION = Integer.MIN_VALUE;

    private final DistantTerrainPort terrainPort;
    private final CylinderStreamer cylinderStreamer;

    private final Vector3 lastSweptPosition = new Vector3(Float.NaN, Float.NaN, Float.NaN);

    /** Region-grid coords of the last completed sweep. Guards against re-sweeping the same cell. */
    private int lastSweptRx = NO_REGION;
    private int lastSweptRy = NO_REGION;
    private int lastSweptRz = NO_REGION;

    private float movementSweepTimer;
    private float idleResweepTimer = IDLE_RESWEEP_INTERVAL_SECONDS;
    private float evictionTimer;

    /**
     * Set to {@code true} after a full scan completes without submitting any new regions —
     * meaning the horizon is fully seeded for the current region cell. Cleared whenever the
     * player crosses a region boundary so the next cell's candidates are queued.
     */
    private boolean horizonFullySeeded = false;

    private long evictionCount;

    public DistantTerrainStreamer(DistantTerrainPort terrainPort, CylinderStreamer cylinderStreamer) {
        this.terrainPort      = terrainPort;
        this.cylinderStreamer = cylinderStreamer;
    }

    /** Call once per frame on the main thread. */
    public void update(PerspectiveCamera camera, float deltaTime) {
        updateForPosition(camera.position, deltaTime);
    }

    public long getEvictionCount() {
        return evictionCount;
    }

    void updateForPosition(Vector3 observerPosition, float deltaTime) {
        terrainPort.pollCompletedMeshes();
        maybeSweepHorizon(observerPosition, deltaTime);
        maybeSafetyEvict(observerPosition, deltaTime);
    }

    // ---------------------------------------------------------------
    // Horizon sweep — only adds regions, never removes
    // ---------------------------------------------------------------

    private void maybeSweepHorizon(Vector3 observerPosition, float deltaTime) {
        boolean isMoving = cylinderStreamer.updateAndCheckMovement(observerPosition, deltaTime, MOVEMENT_SWEEP_THRESHOLD);

        movementSweepTimer += deltaTime;
        idleResweepTimer   += deltaTime;

        int rx = worldToRegion(Math.round(observerPosition.x));
        int ry = worldToRegion(Math.round(observerPosition.y));
        int rz = worldToRegion(Math.round(observerPosition.z));

        boolean regionCellChanged = rx != lastSweptRx || ry != lastSweptRy || rz != lastSweptRz;
        if (regionCellChanged) {
            horizonFullySeeded = false;
        }

        // Once the horizon is fully seeded for this cell, only re-scan if we cross a cell boundary.
        if (horizonFullySeeded) {
            // Still update position tracking so positionChanged stays accurate after an eventual cell crossing.
            lastSweptPosition.set(observerPosition);
            return;
        }

        int     currentInFlight = terrainPort.getPendingRegionCount();
        boolean shouldSweep     = isSweepWarranted(isMoving, currentInFlight);

        if (shouldSweep) {
            boolean needsFullScan = isFullCandidateScanNeeded(rx, ry, rz, currentInFlight);

            // Always update position so positionChanged doesn't re-fire every frame for the same spot.
            lastSweptPosition.set(observerPosition);

            if (needsFullScan) {
                lastSweptRx = rx;
                lastSweptRy = ry;
                lastSweptRz = rz;

                // Timers only reset when we actually ran a scan, so the idle resweep
                // can fire normally even while the player moves within the same region cell.
                movementSweepTimer = 0f;
                idleResweepTimer   = 0f;

                int submitted = submitUnknownCandidatesNearestFirst(rx, ry, rz, currentInFlight);
                boolean horizonHasNoMoreWork = submitted == 0;
                if (horizonHasNoMoreWork) {
                    horizonFullySeeded = true;
                }
            }
        }
    }

    /** Returns true if any sweep pass should be attempted this tick. */
    private boolean isSweepWarranted(boolean isMoving, int currentInFlight) {
        boolean isInitialSweep     = lastSweptRx == NO_REGION;
        boolean movementSweepReady = movementSweepTimer >= MOVEMENT_SWEEP_INTERVAL_SECONDS;
        boolean idleResweepReady   = idleResweepTimer   >= IDLE_RESWEEP_INTERVAL_SECONDS;
        boolean isMovementSweep    = isMoving && movementSweepReady;
        int     lowWaterMark       = (int) (DistantTerrainConfig.MAX_IN_FLIGHT_TASKS * QUEUE_LOW_WATER_FRACTION);
        boolean queueHungry        = currentInFlight < lowWaterMark;

        return isInitialSweep || isMovementSweep || idleResweepReady || queueHungry;
    }

    /**
     * Returns true if we must re-scan the full candidate space.
     * False means the existing queue already contains all relevant work from the last sweep.
     */
    private boolean isFullCandidateScanNeeded(int rx, int ry, int rz, int currentInFlight) {
        boolean isInitialSweep    = lastSweptRx == NO_REGION;
        boolean idleResweepReady  = idleResweepTimer >= IDLE_RESWEEP_INTERVAL_SECONDS;
        boolean regionCellChanged = rx != lastSweptRx || ry != lastSweptRy || rz != lastSweptRz;
        boolean queueHungry       = currentInFlight < (int) (DistantTerrainConfig.MAX_IN_FLIGHT_TASKS * QUEUE_LOW_WATER_FRACTION);
        boolean queueHasCapacity  = currentInFlight < DistantTerrainConfig.MAX_IN_FLIGHT_TASKS;

        return isInitialSweep || regionCellChanged || queueHungry || (idleResweepReady && queueHasCapacity);
    }

    /**
     * Submits unknown horizon candidates in nearest-first order by iterating
     * outward in Chebyshev shells (d=0, 1, 2, …). No list is built and no sort
     * is performed — candidates are encountered in the correct order by construction.
     * Stops as soon as the in-flight queue is full.
     *
     * @return the number of new regions submitted, or -1 if the loop was cut short by a full queue
     */
    private int submitUnknownCandidatesNearestFirst(int rx, int ry, int rz, int inFlight) {
        // hRadius >= vRadius by config contract, so hRadius drives the outer shell loop.
        // The y-axis extent is independently clamped to vRadius inside each shell.
        int hRadius = DistantTerrainConfig.HORIZON_H_RADIUS_REGIONS;
        int vRadius = DistantTerrainConfig.HORIZON_V_RADIUS_REGIONS;

        int submitted = 0;

        for (int shell = 0; shell <= hRadius; shell++) {
            int dyMax = Math.min(shell, vRadius);
            for (int dy = -dyMax; dy <= dyMax; dy++) {
                boolean isYFace = Math.abs(dy) == shell;
                for (int dz = -shell; dz <= shell; dz++) {
                    boolean isZFace = Math.abs(dz) == shell;
                    for (int dx = -shell; dx <= shell; dx++) {
                        boolean isXFace          = Math.abs(dx) == shell;
                        boolean isOnShellSurface = isXFace || isYFace || isZFace;
                        if (!isOnShellSurface) {
                            continue;
                        }

                        boolean queueIsFull = inFlight >= DistantTerrainConfig.MAX_IN_FLIGHT_TASKS;
                        if (queueIsFull) {
                            return -1;
                        }

                        DistantRegionKey key = new DistantRegionKey(rx + dx, ry + dy, rz + dz);
                        boolean wasSubmitted = maybeSubmitRegion(key);
                        if (wasSubmitted) {
                            inFlight++;
                            submitted++;
                        }
                    }
                }
            }
        }
        return submitted;
    }

    /**
     * Submits a single unknown region to the async pipeline at the given priority.
     * Returns true if the request was dispatched.
     */
    private boolean maybeSubmitRegion(DistantRegionKey key) {
        boolean isUnknown = !terrainPort.isRegionKnown(key);
        if (isUnknown) {
            terrainPort.requestRegionAsync(key);
        }
        return isUnknown;
    }

    // ---------------------------------------------------------------
    // Safety eviction — infrequent, last resort only
    // ---------------------------------------------------------------

    private void maybeSafetyEvict(Vector3 observerPosition, float deltaTime) {
        evictionTimer += deltaTime;
        boolean isTimeToEvict = evictionTimer >= DistantTerrainConfig.EVICTION_INTERVAL_SECONDS;

        if (isTimeToEvict) {
            evictionTimer = 0f;

            int rx = worldToRegion(Math.round(observerPosition.x));
            int ry = worldToRegion(Math.round(observerPosition.y));
            int rz = worldToRegion(Math.round(observerPosition.z));

            List<DistantRegionKey> toEvict = new ArrayList<>();
            for (DistantRegionKey key : terrainPort.getLoadedRegionKeys()) {
                boolean isTooFar = isBeyondEvictionRadius(key, rx, ry, rz);
                if (isTooFar) {
                    toEvict.add(key);
                }
            }

            for (DistantRegionKey key : toEvict) {
                terrainPort.removeRegion(key);
                evictionCount++;
            }
        }
    }

    /** Observer region coords are pre-computed by the caller to avoid repeating the conversion per key. */
    private boolean isBeyondEvictionRadius(DistantRegionKey key, int rx, int ry, int rz) {
        int adx = Math.abs(key.regionX() - rx);
        int ady = Math.abs(key.regionY() - ry);
        int adz = Math.abs(key.regionZ() - rz);

        boolean horizontallyTooFar = adx > DistantTerrainConfig.EVICTION_H_RADIUS_REGIONS
                                  || adz > DistantTerrainConfig.EVICTION_H_RADIUS_REGIONS;
        boolean verticallyTooFar   = ady > DistantTerrainConfig.EVICTION_V_RADIUS_REGIONS;

        return horizontallyTooFar || verticallyTooFar;
    }

    private static int worldToRegion(int worldCoord) {
        return Math.floorDiv(worldCoord, DistantTerrainConfig.REGION_SIZE_VOXELS);
    }
}
