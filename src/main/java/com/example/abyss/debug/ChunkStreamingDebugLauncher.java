package com.example.abyss.debug;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

/**
 * Desktop launcher for the chunk-streaming debug viewer.
 */
public final class ChunkStreamingDebugLauncher {

    private ChunkStreamingDebugLauncher() {
    }

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setWindowedMode(1280, 800);
        config.setTitle("Abyss - Chunk Streaming Debug");
        config.setForegroundFPS(144);
        config.setIdleFPS(30);
        new Lwjgl3Application(new ChunkStreamingDebugApp(), config);
    }
}
