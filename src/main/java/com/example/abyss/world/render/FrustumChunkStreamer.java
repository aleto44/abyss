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
 * Loads every chunk within a cylinder around the player — no frustum test.
 * The shaft is narrow enough that any chunk within the horizontal radius is
 * potentially visible regardless of look direction, so frustum-based loading
 * only caused thrash as the player turned.
 * </p>
 * <p>
 * Unloading uses a pure positional cylinder with an asymmetric vertical window:
 * chunks above the player are evicted sooner than chunks below, matching the
 * one-directional downward travel pattern of the abyss.
 * </p>
 * <p>
 * {@link ChunkLoader} is used as a pure executor — it is only told which
 * specific coordinates to load or unload, with no knowledge of cameras.
 * </p>
 */
public class FrustumChunkStreamer {

    /** How often (in seconds) the unload scan runs. */
    private static final float UNLOAD_INTERVAL_SECONDS = 2.0f;
    /**
     * Minimum world-units-per-second of camera movement that marks the observer as moving.
     * Does not mean a sweep fires every frame — see {@link #MOVEMENT_SWEEP_INTERVAL_SECONDS}.
     */
    private static final float MOVEMENT_SWEEP_THRESHOLD = 1.0f;
    /**
     * Minimum seconds between movement-triggered sweeps.
     * Chunk-boundary crossings still trigger an immediate sweep.
     */
    private static final float MOVEMENT_SWEEP_INTERVAL_SECONDS = 0.25f;
    /**
     * Maximum number of chunk requests submitted in a single sweep.
     * Caps queue depth so the gen workers aren't flooded with thousands of tasks at
     * once — nearest-first ordering ensures the most important chunks go in first.
     */
    private static final int MAX_SUBMISSIONS_PER_SWEEP = 512;
    /**
     * How often (in seconds) a re-sweep fires unconditionally.
     * Needed because {@link #MAX_SUBMISSIONS_PER_SWEEP} caps each sweep — without a
     * periodic retry the remaining chunks would never be queued while standing still.
     */
    private static final float IDLE_RESWEEP_INTERVAL_SECONDS = 0.25f;
    /**
     * How many chunks above the player to keep loaded.
     * Smaller than {@code vRadius} because the player is descending — chunks far above
     * are unlikely to be revisited and can be evicted sooner than chunks below.
     */
    private static final int V_KEEP_ABOVE = 10;

    private final ChunkLoader chunkLoader;
    private final int hRadius;
    private final int vRadius;

    /** Chunk-space position from the last sweep — re-sweep triggered when this changes. */
    private int lastSweptPcx = Integer.MIN_VALUE;
    private int lastSweptPcy = Integer.MIN_VALUE;
    private int lastSweptPcz = Integer.MIN_VALUE;

    /** Camera position from the previous frame, used to detect movement this frame. */
    private final Vector3 lastPosition = new Vector3(Float.NaN, Float.NaN, Float.NaN);

    /** Accumulates delta-time so the unload scan only fires every {@link #UNLOAD_INTERVAL_SECONDS}. */
    private float unloadTimer = 0f;
    /** Accumulates delta-time to throttle movement-triggered re-sweeps. */
    private float movementSweepTimer = 0f;
    /** Accumulates delta-time to drive the periodic idle re-sweep. */
    private float idleResweepTimer = IDLE_RESWEEP_INTERVAL_SECONDS; // start ready so first frame always sweeps

    public FrustumChunkStreamer(ChunkLoader chunkLoader, int hRadius, int vRadius) {
        this.chunkLoader = chunkLoader;
        this.hRadius = hRadius;
        this.vRadius = vRadius;
    }

    /**
     * Call once per frame. Submits chunks in the cylinder for async generation
     * and periodically evicts chunks outside the keep zone.
     */
    public void update(PerspectiveCamera camera, float deltaTime) {
        sweepCylinderChunks(camera, deltaTime);
        maybeUnloadDistantChunks(camera, deltaTime);
    }

    private void sweepCylinderChunks(PerspectiveCamera camera, float deltaTime) {
        int worldX = Math.round(camera.position.x);
        int worldY = Math.round(camera.position.y);
        int worldZ = Math.round(camera.position.z);

        int pcx = Math.floorDiv(worldX, WorldConstants.CHUNK_SIZE);
        int pcy = Math.floorDiv(worldY, WorldConstants.CHUNK_SIZE);
        int pcz = Math.floorDiv(worldZ, WorldConstants.CHUNK_SIZE);

        boolean playerChunkChanged = pcx != lastSweptPcx || pcy != lastSweptPcy || pcz != lastSweptPcz;
        boolean playerIsMoving = isObserverMoving(camera.position, deltaTime);

        movementSweepTimer += deltaTime;
        idleResweepTimer += deltaTime;
        boolean movementSweepReady = movementSweepTimer >= MOVEMENT_SWEEP_INTERVAL_SECONDS;
        boolean idleResweepReady = idleResweepTimer >= IDLE_RESWEEP_INTERVAL_SECONDS;

        boolean shouldResweep = playerChunkChanged
                || (playerIsMoving && movementSweepReady)
                || idleResweepReady;
        if (!shouldResweep) {
            return;
        }

        movementSweepTimer = 0f;
        idleResweepTimer = 0f;
        lastSweptPcx = pcx;
        lastSweptPcy = pcy;
        lastSweptPcz = pcz;

        submitCylinderCandidatesNearestFirst(pcx, pcy, pcz, camera.position);
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

    private void submitCylinderCandidatesNearestFirst(int pcx, int pcy, int pcz, Vector3 camPos) {
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

        // Sort nearest-first so workers always generate the closest chunks first.
        candidates.sort(Comparator.comparingDouble(c -> {
            float cx = c[0] * chunkSize + chunkSize * 0.5f;
            float cy = c[1] * chunkSize + chunkSize * 0.5f;
            float cz = c[2] * chunkSize + chunkSize * 0.5f;
            float ddx = cx - camX;
            float ddy = cy - camY;
            float ddz = cz - camZ;
            return ddx * ddx + ddy * ddy + ddz * ddz;
        }));

        int submitted = 0;
        for (int[] c : candidates) {
            boolean budgetExhausted = submitted >= MAX_SUBMISSIONS_PER_SWEEP;
            if (budgetExhausted) {
                break;
            }
            boolean wasNew = chunkLoader.requestChunkAsync(c[0], c[1], c[2]);
            if (wasNew) {
                submitted++;
            }
        }
    }

    private void maybeUnloadDistantChunks(PerspectiveCamera camera, float deltaTime) {
        unloadTimer += deltaTime;
        boolean isTimeToUnload = unloadTimer >= UNLOAD_INTERVAL_SECONDS;
        if (!isTimeToUnload) {
            return;
        }
        unloadTimer = 0f;

        int worldX = Math.round(camera.position.x);
        int worldY = Math.round(camera.position.y);
        int worldZ = Math.round(camera.position.z);
        int pcx = Math.floorDiv(worldX, WorldConstants.CHUNK_SIZE);
        int pcy = Math.floorDiv(worldY, WorldConstants.CHUNK_SIZE);
        int pcz = Math.floorDiv(worldZ, WorldConstants.CHUNK_SIZE);

        // Evict any chunk outside the cylinder keep zone.
        // Horizontal: symmetric at hRadius + 1 (one-chunk hysteresis band).
        // Vertical: asymmetric — keep vRadius below (where the player is heading)
        //           but only V_KEEP_ABOVE above (already descended past those).
        chunkLoader.unloadChunks(key -> {
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
    }
}