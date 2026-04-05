package com.example.abyss.world.render.lod;

import com.example.abyss.world.render.LodRenderEntry;

import java.util.List;

/**
 * Streaming-facing contract for far-field proxy region management.
 * <p>
 * {@link LodStreamer} depends only on this interface, keeping it decoupled from
 * the concrete {@link LodProxyManager} implementation and allowing lightweight
 * test doubles without subclassing.
 * </p>
 */
public interface LodProxyPort {

    /** Drains completed proxy mesh results. Call once per frame on the main thread. */
    void pollCompletedMeshes();

    /**
     * Returns {@code true} if the region is either loaded or pending generation.
     * Used by the streamer to avoid re-submitting known regions.
     */
    boolean isRegionKnown(LodRegionKey key);

    /**
     * Submits proxy mesh generation at the given priority. Lower values execute first.
     *
     * @return {@code true} if a new generation task was submitted
     */
    boolean requestProxyMeshAsync(LodRegionKey key, int priority);

    /**
     * Raises the priority of an already-pending task.
     * No-op if the region is not pending or has already started.
     */
    void reprioritiseProxyMeshAsync(LodRegionKey key, int newPriority);

    /** Removes a region and cancels any pending generation task. */
    LodProxyRegion removeRegion(LodRegionKey key);

    /** Number of regions whose generation task is currently in flight (submitted but not yet completed). */
    int getPendingRegionCount();

    /** Returns the keys of all currently loaded regions. */
    List<LodRegionKey> getLoadedRegionKeys();

    /** Render entries for the current frame. */
    List<LodRenderEntry> getRenderEntries();
}