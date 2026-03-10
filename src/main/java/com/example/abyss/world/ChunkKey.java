package com.example.abyss.world;

/**
 * Lightweight key for identifying a chunk by its chunk-space coordinates.
 * Used as a {@link java.util.HashMap} key in {@link ChunkManager}.
 */
public record ChunkKey(int cx, int cy, int cz) {
}
