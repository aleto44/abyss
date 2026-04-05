package com.example.abyss.world.render.distant;

import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Vector3;
import com.example.abyss.world.render.CylinderStreamer;

import java.util.ArrayList;
import java.util.List;

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
    private static final float IDLE_RESWEEP_INTERVAL_SECONDS   = 0.5f;

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
        boolean movementSweepReady = movementSweepTimer >= MOVEMENT_SWEEP_INTERVAL_SECONDS;
        boolean idleResweepReady   = idleResweepTimer   >= IDLE_RESWEEP_INTERVAL_SECONDS;

        boolean isInitialSweep  = lastSweptRx == NO_REGION;
        boolean isMovementSweep = isMoving && movementSweepReady;
        boolean positionChanged = !isInitialSweep && !observerPosition.epsilonEquals(lastSweptPosition, 0.5f);
        boolean shouldSweep     = isInitialSweep || positionChanged || isMovementSweep || idleResweepReady;

        if (!shouldSweep) {
            return;
        }

        int rx = worldToRegion(Math.round(observerPosition.x));
        int ry = worldToRegion(Math.round(observerPosition.y));
        int rz = worldToRegion(Math.round(observerPosition.z));

        // Region-grid guard: skip the expensive candidate scan entirely if the player
        // has not crossed into a new region cell since the last sweep. The task queue
        // already holds all relevant work from the previous sweep.
        boolean regionCellChanged = rx != lastSweptRx || ry != lastSweptRy || rz != lastSweptRz;
        boolean queueHasCapacity  = terrainPort.getPendingRegionCount() < DistantTerrainConfig.MAX_IN_FLIGHT_TASKS;
        boolean needsFullScan     = isInitialSweep || regionCellChanged || (idleResweepReady && queueHasCapacity);

        movementSweepTimer = 0f;
        idleResweepTimer   = 0f;
        lastSweptPosition.set(observerPosition);

        if (!needsFullScan) {
            return;
        }

        lastSweptRx = rx;
        lastSweptRy = ry;
        lastSweptRz = rz;

        submitUnknownCandidatesNearestFirst(rx, ry, rz);
    }

    /**
     * Submits unknown horizon candidates in nearest-first order by iterating
     * outward in Chebyshev shells (d=0, 1, 2, …). No list is built and no sort
     * is performed — candidates are encountered in the correct order by construction.
     * Stops as soon as the in-flight queue is full.
     */
    private void submitUnknownCandidatesNearestFirst(int rx, int ry, int rz) {
        // hRadius >= vRadius by config contract, so hRadius drives the outer shell loop.
        // The y-axis extent is independently clamped to vRadius inside each shell.
        int hRadius = DistantTerrainConfig.HORIZON_H_RADIUS_REGIONS;
        int vRadius = DistantTerrainConfig.HORIZON_V_RADIUS_REGIONS;

        int inFlight = terrainPort.getPendingRegionCount();
        int globalPriority = 0;

        shellLoop:
        for (int shell = 0; shell <= hRadius; shell++) {
            int dyMax = Math.min(shell, vRadius);
            for (int dy = -dyMax; dy <= dyMax; dy++) {
                boolean isYFace = Math.abs(dy) == shell;
                for (int dz = -shell; dz <= shell; dz++) {
                    boolean isZFace = Math.abs(dz) == shell;
                    for (int dx = -shell; dx <= shell; dx++) {
                        boolean isXFace = Math.abs(dx) == shell;
                        // Only visit cells on the surface of this shell to avoid re-visiting
                        // inner shells that were already processed at a lower shell index.
                        boolean isOnShellSurface = isXFace || isYFace || isZFace;
                        if (!isOnShellSurface) {
                            continue;
                        }

                        boolean queueIsFull = inFlight >= DistantTerrainConfig.MAX_IN_FLIGHT_TASKS;
                        if (queueIsFull) {
                            break shellLoop;
                        }

                        DistantRegionKey key = new DistantRegionKey(rx + dx, ry + dy, rz + dz);
                        boolean isKnown = terrainPort.isRegionKnown(key);
                        if (isKnown) {
                            continue;
                        }

                        boolean submitted = terrainPort.requestRegionAsync(key, globalPriority);
                        if (submitted) {
                            inFlight++;
                        }
                        globalPriority++;
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Safety eviction — infrequent, last resort only
    // ---------------------------------------------------------------

    private void maybeSafetyEvict(Vector3 observerPosition, float deltaTime) {
        evictionTimer += deltaTime;
        boolean isTimeToEvict = evictionTimer >= DistantTerrainConfig.EVICTION_INTERVAL_SECONDS;
        if (!isTimeToEvict) {
            return;
        }
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

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static int worldToRegion(int worldCoord) {
        return Math.floorDiv(worldCoord, DistantTerrainConfig.REGION_SIZE_VOXELS);
    }
}
