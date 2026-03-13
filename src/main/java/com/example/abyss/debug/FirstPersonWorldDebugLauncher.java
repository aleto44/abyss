package com.example.abyss.debug;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

/**
 * Desktop launcher for the first-person 3D world debug viewer.
 */
public final class FirstPersonWorldDebugLauncher {

    private FirstPersonWorldDebugLauncher() {
    }

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setWindowedMode(1440, 900);
        config.setTitle("Abyss - First Person World Debug");
        new Lwjgl3Application(new FirstPersonWorldDebugApp(), config);
    }
}
