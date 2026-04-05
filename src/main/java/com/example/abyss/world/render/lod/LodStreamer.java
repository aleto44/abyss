package com.example.abyss.world.render.lod;

import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Vector3;
import com.example.abyss.world.render.CylinderStreamer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Distance-band streaming policy for far-field proxy regions.
 * Sweeps all configured {@link LodConfig#TIERS} each frame, submitting or
 * reprioritising proxy regions for every tier whose band contains the observer.
 */
public class LodStreamer {

    private static final float UNLOAD_INTERVAL_SECONDS         = 2.0f;
    private static final float MOVEMENT_SWEEP_THRESHOLD        = 1.0f;
    private static final float MOVEMENT_SWEEP_INTERVAL_SECONDS = 0.1f;
    private static final float IDLE_RESWEEP_INTERVAL_SECONDS   = 0.5f;

    /**
     * Extra regions beyond the load band's outer edge before a loaded region is eligible for removal.
     * Prevents thrashing when the player drifts near a band boundary: the old region stays visible
     * until the replacement has had time to generate.
     */
    private static final int UNLOAD_HYSTERESIS_REGIONS = 1;

    /**
     * Hard cap on the number of LOD generation tasks that may sit in-flight at once.
     * Keeps the shared worker pool from being flooded with distant tasks that block
     * close ones from completing: once the queue is this full, sweeps only
     * reprioritise existing tasks and submit nothing new.
     */
    private static final int MAX_IN_FLIGHT_TASKS = 24;

    private final LodProxyPort proxyPort;
    private final CylinderStreamer cylinderStreamer;

    private final Vector3 lastSweptPosition  = new Vector3(Float.NaN, Float.NaN, Float.NaN);
    private final Vector3 lastUnloadPosition = new Vector3(Float.NaN, Float.NaN, Float.NaN);

    private float unloadTimer;
    private float movementSweepTimer;
    private float idleResweepTimer = IDLE_RESWEEP_INTERVAL_SECONDS;

    private long demotionCount;

    public LodStreamer(LodProxyPort proxyPort, CylinderStreamer cylinderStreamer) {
        this.proxyPort        = proxyPort;
        this.cylinderStreamer = cylinderStreamer;
    }

    /** Call once per frame on the main thread. */
    public void update(PerspectiveCamera camera, float deltaTime) {
        updateForPosition(camera.position, deltaTime);
    }

    public long getDemotionCount() {
        return demotionCount;
    }

    void updateForPosition(Vector3 observerPosition, float deltaTime) {
        proxyPort.pollCompletedMeshes();
        maybeUnloadDistantRegions(observerPosition, deltaTime);
        sweepProxyBand(observerPosition, deltaTime);
    }

    // ---------------------------------------------------------------
    // Sweep
    // ---------------------------------------------------------------

    private void sweepProxyBand(Vector3 observerPosition, float deltaTime) {
        boolean isMoving = cylinderStreamer.updateAndCheckMovement(observerPosition, deltaTime, MOVEMENT_SWEEP_THRESHOLD);

        movementSweepTimer += deltaTime;
        idleResweepTimer   += deltaTime;
        boolean movementSweepReady = movementSweepTimer >= MOVEMENT_SWEEP_INTERVAL_SECONDS;
        boolean idleResweepReady   = idleResweepTimer   >= IDLE_RESWEEP_INTERVAL_SECONDS;

        boolean isInitialSweep  = Float.isNaN(lastSweptPosition.x);
        boolean isMovementSweep = isMoving && movementSweepReady;
        boolean positionChanged = !isInitialSweep && !observerPosition.epsilonEquals(lastSweptPosition, 0.5f);
        boolean shouldSweep     = isInitialSweep || positionChanged || isMovementSweep || idleResweepReady;

        if (!shouldSweep) {
            return;
        }

        movementSweepTimer = 0f;
        idleResweepTimer   = 0f;
        lastSweptPosition.set(observerPosition);

        submitAllTierCandidatesNearestFirst(observerPosition);
    }

    private void submitAllTierCandidatesNearestFirst(Vector3 observerPosition) {
        List<TierCandidate> allCandidates = collectAllTierCandidates(observerPosition);
        allCandidates.sort(Comparator.comparingDouble(c -> c.distanceSq));

        int inFlight = proxyPort.getPendingRegionCount();
        int globalPriority = 0;
        for (TierCandidate candidate : allCandidates) {
            boolean isKnown = proxyPort.isRegionKnown(candidate.key);
            if (isKnown) {
                proxyPort.reprioritiseProxyMeshAsync(candidate.key, globalPriority);
            } else {
                boolean queueIsFull = inFlight >= MAX_IN_FLIGHT_TASKS;
                if (queueIsFull) {
                    continue;
                }
                boolean submitted = proxyPort.requestProxyMeshAsync(candidate.key, globalPriority);
                if (submitted) {
                    inFlight++;
                }
            }
            globalPriority++;
        }
    }

    private List<TierCandidate> collectAllTierCandidates(Vector3 observerPosition) {
        List<TierCandidate> allCandidates = new ArrayList<>();
        for (LodTierConfig tier : LodConfig.TIERS) {
            int rx = worldToRegionForTier(Math.round(observerPosition.x), tier);
            int ry = worldToRegionForTier(Math.round(observerPosition.y), tier);
            int rz = worldToRegionForTier(Math.round(observerPosition.z), tier);
            int outerH = tier.outerHRadiusRegions();
            int outerV = tier.outerVRadiusRegions();
            float regionSize = tier.regionSizeVoxels();
            for (int dy = -outerV; dy <= outerV; dy++) {
                for (int dz = -outerH; dz <= outerH; dz++) {
                    for (int dx = -outerH; dx <= outerH; dx++) {
                        boolean isInBand = cylinderStreamer.isInTierBand(dx, dy, dz, tier);
                        if (!isInBand) {
                            continue;
                        }
                        int absRx = rx + dx;
                        int absRy = ry + dy;
                        int absRz = rz + dz;
                        float centerX = absRx * regionSize + regionSize * 0.5f;
                        float centerY = absRy * regionSize + regionSize * 0.5f;
                        float centerZ = absRz * regionSize + regionSize * 0.5f;
                        float ddx = centerX - observerPosition.x;
                        float ddy = centerY - observerPosition.y;
                        float ddz = centerZ - observerPosition.z;
                        float distanceSq = ddx * ddx + ddy * ddy + ddz * ddz;
                        allCandidates.add(new TierCandidate(
                                new LodRegionKey(absRx, absRy, absRz, tier.lodLevel()),
                                distanceSq));
                    }
                }
            }
        }
        return allCandidates;
    }

    private record TierCandidate(LodRegionKey key, float distanceSq) {}

    // ---------------------------------------------------------------
    // Unload
    // ---------------------------------------------------------------

    private void maybeUnloadDistantRegions(Vector3 observerPosition, float deltaTime) {
        boolean positionChanged = !Float.isNaN(lastUnloadPosition.x)
                               && !observerPosition.epsilonEquals(lastUnloadPosition, 0.5f);

        unloadTimer += deltaTime;
        boolean isTimeToUnload = unloadTimer >= UNLOAD_INTERVAL_SECONDS;

        boolean shouldUnload = positionChanged || isTimeToUnload || Float.isNaN(lastUnloadPosition.x);
        if (!shouldUnload) {
            return;
        }

        if (isTimeToUnload) {
            unloadTimer = 0f;
        }
        lastUnloadPosition.set(observerPosition);

        List<LodRegionKey> toRemove = new ArrayList<>();
        for (LodRegionKey key : proxyPort.getLoadedRegionKeys()) {
            LodTierConfig tier = LodConfig.TIERS.get(key.lodLevel());
            int rx = worldToRegionForTier(Math.round(observerPosition.x), tier);
            int ry = worldToRegionForTier(Math.round(observerPosition.y), tier);
            int rz = worldToRegionForTier(Math.round(observerPosition.z), tier);
            int dx = key.regionX() - rx;
            int dy = key.regionY() - ry;
            int dz = key.regionZ() - rz;
            boolean isNoLongerInUnloadZone = !cylinderStreamer.isInTierUnloadZone(dx, dy, dz, tier, UNLOAD_HYSTERESIS_REGIONS);
            if (isNoLongerInUnloadZone) {
                toRemove.add(key);
            }
        }

        for (LodRegionKey key : toRemove) {
            proxyPort.removeRegion(key);
            demotionCount++;
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private static int worldToRegionForTier(int worldCoord, LodTierConfig tier) {
        return Math.floorDiv(worldCoord, tier.regionSizeVoxels());
    }
}