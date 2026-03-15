package com.example.abyss.debug.tweak;

import com.example.abyss.world.gen.Layer;
import com.example.abyss.world.gen.LayerGenConfig;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable per-layer overrides for {@link LayerGenConfig} knobs.
 * <p>
 * The Swing panel writes into this store on the EDT; the LibGDX render thread
 * reads from it each frame via {@link #getConfig(Layer)}.  Thread-safety is
 * achieved with a simple {@code volatile} reference swap per entry — the
 * records are immutable so no partial-write hazard exists.
 * </p>
 * <p>
 * A monotonically-increasing {@link #getRevision() revision counter} lets
 * the sampler detect when any knob has changed without comparing every field.
 * </p>
 */
public class GenParamOverrides {

    private final Map<Layer, LayerGenConfig> overrides = new EnumMap<>(Layer.class);
    private final AtomicLong revision = new AtomicLong(0);

    public GenParamOverrides() {
        // Seed from baked Layer values so the panel starts at the current tuned values.
        for (Layer layer : Layer.values()) {
            overrides.put(layer, layer.getGenConfig());
        }
    }

    /**
     * Returns the current (possibly overridden) config for {@code layer}.
     * Safe to call from any thread.
     */
    public synchronized LayerGenConfig getConfig(Layer layer) {
        return overrides.get(layer);
    }

    /**
     * Replaces the config for a single layer and bumps the revision counter.
     * Intended to be called from the Swing EDT.
     */
    public synchronized void setConfig(Layer layer, LayerGenConfig config) {
        overrides.put(layer, config);
        revision.incrementAndGet();
    }

    /**
     * Returns a monotonically-increasing counter that is bumped every time any
     * config entry changes.  The sampler can compare against a saved value to
     * detect staleness without holding a lock.
     */
    public long getRevision() {
        return revision.get();
    }

    /** Resets all layers back to their baked {@link Layer} defaults. */
    public synchronized void resetAll() {
        for (Layer layer : Layer.values()) {
            overrides.put(layer, layer.getGenConfig());
        }
        revision.incrementAndGet();
    }
}
