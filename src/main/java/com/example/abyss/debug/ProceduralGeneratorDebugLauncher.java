package com.example.abyss.debug;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

/**
 * Desktop launcher for the direct ProceduralGenerator debug viewer.
 */
public final class ProceduralGeneratorDebugLauncher {

    private ProceduralGeneratorDebugLauncher() {
    }

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setWindowedMode(1280, 800);
        config.setTitle("Abyss - Procedural Generator Debug");
        new Lwjgl3Application(new ProceduralGeneratorDebugApp(), config);
    }
}
