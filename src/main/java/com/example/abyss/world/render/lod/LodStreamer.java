package com.example.abyss.world.render.lod;

import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Vector3;
import com.example.abyss.world.render.CylinderStreamer;
import com.example.abyss.world.render.LodRenderEntry;
import com.example.abyss.world.render.StreamingSubmissionBudget;

import java.util.ArrayList;
import java.util.List;

/**
 * Distance-band streaming policy for far-field proxy regions.
 * <p>
 * Requests proxy regions in an outer band around the observer, leaving the
 * inner near-field cylinder managed elsewhere. The exclusion zone is derived
 * directly from the shared {@link CylinderStreamer}, so the near-field and
 * LOD streamers stay consistent without coupling this class to a concrete
 * near-field implementation.
 * </p>
 * <p>
 * Proxy regions are expressed in region-space coordinates where one region spans
 * {@link LodProxyGenerator#REGION_CHUNKS_H} chunks on all three axes (cubic region).
 * </p>
 */
public class LodStreamer {
    // ---------------------------------------------------------------
    // Timing constants — mirror NearChunkStreamer's discipline
    // ---------------------------------------------------------------

    private static final float UNLOAD_INTERVAL_SECONDS          = 4.0f;
    private static final float MOVEMENT_SWEEP_THRESHOLD         = 1.0f;
    private static final float MOVEMENT_SWEEP_INTERVAL_SECONDS  = 0.5f;
    private static final float IDLE_RESWEEP_INTERVAL_SECONDS    = 0.1f;

    /**
     * Maximum proxy region submissions per sweep.
     * Sized to saturate a multi-threaded generation pool on initial load without
     * flooding the task queue excessively.
     */
    private static final int MAX_SUBMISSIONS_PER_SWEEP = 128;

    // ---------------------------------------------------------------
    // Region dimensions (derived from generator constants)
    // ---------------------------------------------------------------

    private static final int REGION_SIZE = LodProxyGenerator.REGION_VOXELS_H;

    // ---------------------------------------------------------------
    // State
    // ---------------------------------------------------------------

    private final LodProxyManager proxyManager;
    private final CylinderStreamer cylinderStreamer;

    private int lastSweptRx = Integer.MIN_VALUE;
    private int lastSweptRy = Integer.MIN_VALUE;
    private int lastSweptRz = Integer.MIN_VALUE;

    private float unloadTimer;
    private float movementSweepTimer;
    private float idleResweepTimer = IDLE_RESWEEP_INTERVAL_SECONDS;

    public LodStreamer(LodProxyManager proxyManager, CylinderStreamer cylinderStreamer) {
        this.proxyManager     = proxyManager;
        this.cylinderStreamer = cylinderStreamer;
    }

    // ---------------------------------------------------------------
    // Per-frame update
    // ---------------------------------------------------------------

    /**
     * Call once per frame on the main thread.
     * Submits proxy region requests and periodically evicts distant regions.
     */
    public void update(PerspectiveCamera camera, float deltaTime) {
        updateForPosition(camera.position, deltaTime);
    }

    void updateForPosition(Vector3 observerPosition, float deltaTime) {
        proxyManager.pollCompletedMeshes();
        sweepProxyBand(observerPosition, deltaTime);
        maybeUnloadDistantRegions(observerPosition, deltaTime);
    }

    // ---------------------------------------------------------------
    // Sweep
    // ---------------------------------------------------------------

    private void sweepProxyBand(Vector3 observerPosition, float deltaTime) {
        int rx = worldToRegion(Math.round(observerPosition.x));
        int ry = worldToRegion(Math.round(observerPosition.y));
        int rz = worldToRegion(Math.round(observerPosition.z));

        boolean regionChanged = rx != lastSweptRx || ry != lastSweptRy || rz != lastSweptRz;
        boolean isMoving      = cylinderStreamer.updateAndCheckMovement(observerPosition, deltaTime, MOVEMENT_SWEEP_THRESHOLD);

        movementSweepTimer += deltaTime;
        idleResweepTimer   += deltaTime;
        boolean movementSweepReady = movementSweepTimer >= MOVEMENT_SWEEP_INTERVAL_SECONDS;
        boolean idleResweepReady   = idleResweepTimer   >= IDLE_RESWEEP_INTERVAL_SECONDS;

        boolean isInitialSweep  = lastSweptRx == Integer.MIN_VALUE;
        boolean isMovementSweep = isMoving && movementSweepReady;
        boolean shouldSweep     = isInitialSweep || regionChanged || isMovementSweep || idleResweepReady;

        if (!shouldSweep) {
            return;
        }

        movementSweepTimer = 0f;
        idleResweepTimer   = 0f;
        lastSweptRx = rx;
        lastSweptRy = ry;
        lastSweptRz = rz;

        submitBandCandidatesNearestFirst(rx, ry, rz, observerPosition);
    }

    private void submitBandCandidatesNearestFirst(int rx, int ry, int rz, Vector3 camPos) {
        List<int[]> candidates = new ArrayList<>();
        int bandHRadius = cylinderStreamer.getBandHRadius();
        int bandVRadius = cylinderStreamer.getBandVRadius();
        for (int dy = -bandVRadius; dy <= bandVRadius; dy++) {
            for (int dz = -bandHRadius; dz <= bandHRadius; dz++) {
                for (int dx = -bandHRadius; dx <= bandHRadius; dx++) {
                    boolean isInBand = cylinderStreamer.isInLodBand(dx, dy, dz, REGION_SIZE);
                    if (!isInBand) {
                        continue;
                    }
                    candidates.add(new int[]{rx + dx, ry + dy, rz + dz});
                }
            }
        }

        candidates.sort(cylinderStreamer.createNearestFirstComparator(REGION_SIZE, camPos));

        StreamingSubmissionBudget budget = new StreamingSubmissionBudget(MAX_SUBMISSIONS_PER_SWEEP);
        for (int[] candidate : candidates) {
            if (budget.isExhausted()) {
                break;
            }
            LodRegionKey key = new LodRegionKey(candidate[0], candidate[1], candidate[2], 1);
            boolean isKnown = proxyManager.isRegionKnown(key);
            if (!isKnown) {
                boolean submitted = proxyManager.requestProxyMeshAsync(key);
                if (submitted) {
                    budget.recordSubmission();
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Unload
    // ---------------------------------------------------------------

    private void maybeUnloadDistantRegions(Vector3 observerPosition, float deltaTime) {
        unloadTimer += deltaTime;
        boolean isTimeToUnload = unloadTimer >= UNLOAD_INTERVAL_SECONDS;
        if (!isTimeToUnload) {
            return;
        }
        unloadTimer = 0f;

        int rx = worldToRegion(Math.round(observerPosition.x));
        int ry = worldToRegion(Math.round(observerPosition.y));
        int rz = worldToRegion(Math.round(observerPosition.z));

        List<LodRegionKey> toRemove = new ArrayList<>();
        for (LodRegionKey key : getLoadedRegionKeys()) {
            int dx = key.regionX() - rx;
            int dy = key.regionY() - ry;
            int dz = key.regionZ() - rz;
            boolean isNoLongerInBand = !cylinderStreamer.isInLodBand(dx, dy, dz, REGION_SIZE);
            if (isNoLongerInBand) {
                toRemove.add(key);
            }
        }

        for (LodRegionKey key : toRemove) {
            proxyManager.removeRegion(key);
            proxyManager.recordDemotion();
        }
    }

    private List<LodRegionKey> getLoadedRegionKeys() {
        List<LodRegionKey> keys = new ArrayList<>();
        for (LodRenderEntry entry : proxyManager.getRenderEntries()) {
            keys.add(new LodRegionKey(
                    worldToRegion(Math.round(entry.worldX())),
                    worldToRegion(Math.round(entry.worldY())),
                    worldToRegion(Math.round(entry.worldZ())),
                    entry.lodLevel()
            ));
        }
        return keys;
    }

    // ---------------------------------------------------------------
    // Coordinate helpers
    // ---------------------------------------------------------------

    private static int worldToRegion(int worldCoord) {
        return Math.floorDiv(worldCoord, REGION_SIZE);
    }
}
