package com.example.abyss.debug;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

/**
 * Desktop launcher for the live-tweak ProceduralGenerator debug viewer.
 *
 * <p>LWJGL3 and Swing can coexist on the same process as long as the LWJGL3
 * window is created <em>after</em> we signal the JVM to use the AWT/Swing
 * toolkit off the main thread.  The {@code headless} system property must
 * NOT be set, and {@code java.awt.headless} should be {@code false}.</p>
 */
public final class ProceduralGeneratorTweakerLauncher {

    private ProceduralGeneratorTweakerLauncher() {
    }

    public static void main(String[] args) {
        // Allow AWT/Swing to run alongside LWJGL3 on the same JVM process.
        System.setProperty("java.awt.headless", "false");

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setWindowedMode(1280, 800);
        config.setTitle("Abyss - Gen Param Tweaker");
        new Lwjgl3Application(new ProceduralGeneratorTweakerDebugApp(), config);
    }
}
