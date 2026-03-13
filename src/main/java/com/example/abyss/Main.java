package com.example.abyss;

import com.example.abyss.debug.ChunkStreamingDebugLauncher;

/**
 * Temporary application entry point that delegates to the isolated debug launcher.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        ChunkStreamingDebugLauncher.main(args);
    }
}