package com.example.abyss.world.render.distant;

import java.util.List;

/**
 * Streaming-facing contract for distant terrain macro-chunk management.
 * {@link DistantTerrainStreamer} depends only on this interface, keeping it decoupled
 * from the concrete {@link DistantTerrainManager} and enabling lightweight test doubles.
 */
public interface DistantTerrainPort {

    /** Drains completed heightmap results and triggers mesh builds for visible bands. Call once per frame on the main thread. */
    void pollCompletedMeshes();

    /** Returns {@code true} if the macro-chunk is either loaded or pending generation. */
    boolean isMacroChunkKnown(MacroChunkKey key);

    /**
     * Submits heightmap generation for the given macro-chunk.
     *
     * @return {@code true} if a new generation task was submitted
     */
    boolean requestMacroChunkAsync(MacroChunkKey key);

    /** Removes a macro-chunk and cancels any pending generation task. */
    void removeMacroChunk(MacroChunkKey key);

    /** Number of macro-chunks whose generation task is currently in-flight. */
    int getPendingCount();

    /** Returns the keys of all currently loaded macro-chunks. */
    List<MacroChunkKey> getLoadedKeys();

    /** Render entries for the current frame. */
    List<DistantRenderEntry> getRenderEntries();
}