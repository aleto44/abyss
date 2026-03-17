package com.example.abyss.world.render;

import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Vector3;
import com.example.abyss.WorldConstants;
import com.example.abyss.world.ChunkLoader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Streaming policy for frustum-based chunk loading and distance-based unloading.
 * <p>
 * Owns all the "when to sweep" decisions that do not belong in {@link ChunkLoader}:
 * <ul>
 *   <li>Which chunks are candidates (a box around the observer).</li>
 *   <li>Which candidates are visible right now (frustum AABB test).</li>
 *   <li>When to re-sweep (chunk boundary crossing, significant camera rotation,
 *       or continuous movement above a speed threshold).</li>
 *   <li>How often to run the unload scan (throttled by a timer).</li>
 * </ul>
 * Candidates are submitted nearest-first so the generation thread pool always
 * works on the chunks the observer is most likely to see first.
 * <p>
 * {@link ChunkLoader} is used as a pure executor — it is only told which
 * specific coordinates to load or unload, with no knowledge of cameras or frustums.
 * </p>
 */
public class FrustumChunkStreamer {

    /** Dot-product threshold below which a camera rotation triggers a new frustum sweep (~10°). */
    private static final float DIRECTION_CHANGE_THRESHOLD = 0.985f;
    /** How often (in seconds) the unload scan runs. Avoids O(chunks) work every frame. */
    private static final float UNLOAD_INTERVAL_SECONDS = 2.0f;
    /**
     * Minimum world-units-per-second of camera movement that forces a re-sweep every frame.
     * At typical move speeds (48 u/s) the observer crosses chunk boundaries slowly enough that
     * the chunk-boundary trigger alone creates 8+ second gaps in loading. Sweeping every frame
     * while moving keeps the pipeline full.
     */
    private static final float MOVEMENT_SWEEP_THRESHOLD = 1.0f;

    private final ChunkLoader chunkLoader;
    private final int hRadius;
    private final int vRadius;

    /** Chunk-space position from the last sweep — re-sweep triggered when this changes. */
    private int lastSweptPcx = Integer.MIN_VALUE;
    private int lastSweptPcy = Integer.MIN_VALUE;
    private int lastSweptPcz = Integer.MIN_VALUE;

    /** Camera direction from the last sweep — re-sweep triggered when rotation is significant. */
    private final Vector3 lastSweptDirection = new Vector3(Float.NaN, Float.NaN, Float.NaN);

    /** Camera position from the previous frame, used to detect movement this frame. */
    private final Vector3 lastPosition = new Vector3(Float.NaN, Float.NaN, Float.NaN);

    /** Scratch vectors reused each sweep to avoid per-chunk allocation during frustum tests. */
    private final Vector3 candidateMin = new Vector3();
    private final Vector3 candidateMax = new Vector3();

    /** Accumulates delta-time so the unload scan only fires every {@link #UNLOAD_INTERVAL_SECONDS}. */
    private float unloadTimer = 0f;

    public FrustumChunkStreamer(ChunkLoader chunkLoader, int hRadius, int vRadius) {
        this.chunkLoader = chunkLoader;
        this.hRadius = hRadius;
        this.vRadius = vRadius;
    }

    /**
     * Call once per frame. Submits visible chunks for async generation and
     * periodically unloads chunks that are too far away.
     */
    public void update(PerspectiveCamera camera, float deltaTime) {
        sweepVisibleChunks(camera, deltaTime);
        maybeUnloadDistantChunks(camera, deltaTime);
    }

    private void sweepVisibleChunks(PerspectiveCamera camera, float deltaTime) {
        int worldX = Math.round(camera.position.x);
        int worldY = Math.round(camera.position.y);
        int worldZ = Math.round(camera.position.z);

        int pcx = Math.floorDiv(worldX, WorldConstants.CHUNK_SIZE);
        int pcy = Math.floorDiv(worldY, WorldConstants.CHUNK_SIZE);
        int pcz = Math.floorDiv(worldZ, WorldConstants.CHUNK_SIZE);

        boolean playerChunkChanged = pcx != lastSweptPcx || pcy != lastSweptPcy || pcz != lastSweptPcz;
        float dot = lastSweptDirection.dot(camera.direction);
        boolean cameraRotatedSignificantly = dot < DIRECTION_CHANGE_THRESHOLD;
        boolean playerIsMoving = isObserverMoving(camera.position, deltaTime);

        boolean shouldResweep = playerChunkChanged || cameraRotatedSignificantly || playerIsMoving;
        if (!shouldResweep) {
            return;
        }

        lastSweptPcx = pcx;
        lastSweptPcy = pcy;
        lastSweptPcz = pcz;
        lastSweptDirection.set(camera.direction);

        submitVisibleCandidatesNearestFirst(pcx, pcy, pcz, camera);
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

    private void submitVisibleCandidatesNearestFirst(int pcx, int pcy, int pcz, PerspectiveCamera camera) {
        float chunkSize = WorldConstants.CHUNK_SIZE;
        float camX = camera.position.x;
        float camY = camera.position.y;
        float camZ = camera.position.z;

        List<int[]> visibleCandidates = new ArrayList<>();
        for (int dy = -vRadius; dy <= vRadius; dy++) {
            for (int dz = -hRadius; dz <= hRadius; dz++) {
                for (int dx = -hRadius; dx <= hRadius; dx++) {
                    int candidateCx = pcx + dx;
                    int candidateCy = pcy + dy;
                    int candidateCz = pcz + dz;

                    float wx = candidateCx * chunkSize;
                    float wy = candidateCy * chunkSize;
                    float wz = candidateCz * chunkSize;
                    candidateMin.set(wx, wy, wz);
                    candidateMax.set(wx + chunkSize, wy + chunkSize, wz + chunkSize);

                    boolean isVisible = camera.frustum.boundsInFrustum(candidateMin, candidateMax);
                    if (isVisible) {
                        visibleCandidates.add(new int[]{candidateCx, candidateCy, candidateCz});
                    }
                }
            }
        }

        // Sort nearest-first so the thread pool works on the closest chunks first.
        visibleCandidates.sort(Comparator.comparingDouble(c -> {
            float cx = c[0] * chunkSize + chunkSize * 0.5f;
            float cy = c[1] * chunkSize + chunkSize * 0.5f;
            float cz = c[2] * chunkSize + chunkSize * 0.5f;
            float ddx = cx - camX;
            float ddy = cy - camY;
            float ddz = cz - camZ;
            return ddx * ddx + ddy * ddy + ddz * ddz;
        }));

        for (int[] c : visibleCandidates) {
            chunkLoader.requestChunkAsync(c[0], c[1], c[2]);
        }
    }

    private void maybeUnloadDistantChunks(PerspectiveCamera camera, float deltaTime) {
        unloadTimer += deltaTime;
        boolean isTimeToUnload = unloadTimer >= UNLOAD_INTERVAL_SECONDS;
        if (isTimeToUnload) {
            unloadTimer = 0f;
            int worldX = Math.round(camera.position.x);
            int worldY = Math.round(camera.position.y);
            int worldZ = Math.round(camera.position.z);
            // Unload radius is larger than load radius to create a hysteresis band,
            // preventing chunks near the edge from flickering in and out.
            chunkLoader.unloadDistantChunks(worldX, worldY, worldZ, hRadius + 2, vRadius + 2);
        }
    }
}
