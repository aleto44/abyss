package com.example.abyss.debug.viewer;

/**
 * Identifies a chunk by its chunk-space coordinates.
 * Shared between debug samplers for use as a cache key.
 */
public record ChunkKey(int x, int y, int z) {
}
