package com.example.abyss.world.render.distant;

import java.util.List;

/**
 * Streaming-facing contract for distant terrain background region management.
 * <p>
 * {@link DistantTerrainStreamer} depends only on this interface, keeping it decoupled
 * from the concrete {@link DistantTerrainManager} implementation and enabling lightweight
 * test doubles.
 * </p>
 */
public interface DistantTerrainPort {

    /** Drains completed distant terrain mesh results. Call once per frame on the main thread. */
    void pollCompletedMeshes();

    /**
     * Returns {@code true} if the region is either loaded or pending generation.
     * Used by the streamer to avoid re-submitting known regions.
     */
    boolean isRegionKnown(DistantRegionKey key);

    /**
     * Submits distant terrain mesh generation for the given region.
     *
     * @return {@code true} if a new generation task was submitted
     */
    boolean requestRegionAsync(DistantRegionKey key);

    /**
     * Removes a region and cancels any pending generation task.
     * Intended only for the safety-eviction path — not called during normal streaming.
     */
    DistantRegion removeRegion(DistantRegionKey key);

    /** Number of regions whose generation task is currently in-flight. */
    int getPendingRegionCount();

    /** Returns the keys of all currently loaded regions. */
    List<DistantRegionKey> getLoadedRegionKeys();

    /** Render entries for the current frame. */
    List<DistantRenderEntry> getRenderEntries();
}
