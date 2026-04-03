package com.example.abyss.world.render.lod;

import com.example.abyss.util.KeyedTaskQueue;
import com.example.abyss.world.meshing.MeshData;
import com.example.abyss.world.render.LodRenderEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Owns render-only far-field proxy regions.
 * <p>
 * Proxy mesh generation runs on a pool of background threads via an internal
 * {@link KeyedTaskQueue}. Each worker thread gets its own {@link LodProxyGenerator}
 * instance via a {@code ThreadLocal} factory, keeping far-field work separate from
 * the near-field chunk and meshing thread pools without sharing mutable noise state.
 * </p>
 * <p>
 * Call {@link #requestProxyMeshAsync(LodRegionKey, int)} once per region from the
 * streamer, then call {@link #pollCompletedMeshes()} each frame on the main
 * thread to promote finished results into the resident mesh state.
 * </p>
 */
public class LodProxyManager implements LodProxyPort {

    private final Map<LodRegionKey, LodProxyRegion> loadedRegions = new LinkedHashMap<>();
    private final KeyedTaskQueue<LodRegionKey, MeshData> taskQueue;
    private final ThreadLocal<LodProxyGenerator> generatorLocal;

    public LodProxyManager(Supplier<LodProxyGenerator> generatorFactory) {
        this.generatorLocal = ThreadLocal.withInitial(generatorFactory);
        this.taskQueue = KeyedTaskQueue.<LodRegionKey, MeshData>builder()
                .threadNamePrefix("LodProxyGen")
                .threadCount(Math.max(2, Runtime.getRuntime().availableProcessors()))
                .build();
    }

    // ----------------------------------------------------------------
    // LodProxyPort — async generation
    // ----------------------------------------------------------------

    @Override
    public boolean requestProxyMeshAsync(LodRegionKey key, int priority) {
        boolean alreadyLoaded = loadedRegions.containsKey(key);
        if (alreadyLoaded) {
            return false;
        }
        boolean submitted = taskQueue.submit(key, () -> generatorLocal.get().generate(key), priority);
        if (submitted) {
            float worldX = key.regionX() * LodProxyGenerator.REGION_VOXELS_H;
            float worldY = key.regionY() * LodProxyGenerator.REGION_VOXELS_H;
            float worldZ = key.regionZ() * LodProxyGenerator.REGION_VOXELS_H;
            loadedRegions.put(key, new LodProxyRegion(key, worldX, worldY, worldZ, new LodProxyMeshState()));
        }
        return submitted;
    }

    @Override
    public void reprioritiseProxyMeshAsync(LodRegionKey key, int newPriority) {
        taskQueue.reprioritise(key, () -> generatorLocal.get().generate(key), newPriority);
    }

    @Override
    public boolean isRegionKnown(LodRegionKey key) {
        boolean isLoaded = loadedRegions.containsKey(key);
        boolean isPending = taskQueue.isKnown(key);
        return isLoaded || isPending;
    }

    @Override
    public void pollCompletedMeshes() {
        List<KeyedTaskQueue.Result<LodRegionKey, MeshData>> results = taskQueue.pollCompleted();
        for (KeyedTaskQueue.Result<LodRegionKey, MeshData> result : results) {
            LodRegionKey key = result.key();
            LodProxyRegion existing = loadedRegions.get(key);
            boolean stillWanted = existing != null;
            if (!stillWanted) {
                continue;
            }
            LodProxyMeshState meshState = existing.meshState();
            meshState.setMeshQueued(false);
            meshState.setMeshDirty(false);
            meshState.setPendingMeshData(result.value());
        }
    }

    // ----------------------------------------------------------------
    // LodProxyPort — region lifecycle
    // ----------------------------------------------------------------

    @Override
    public LodProxyRegion removeRegion(LodRegionKey key) {
        taskQueue.forget(key);
        return loadedRegions.remove(key);
    }

    @Override
    public List<LodRegionKey> getLoadedRegionKeys() {
        return List.copyOf(loadedRegions.keySet());
    }

    @Override
    public List<LodRenderEntry> getRenderEntries() {
        List<LodRenderEntry> renderEntries = new ArrayList<>();
        for (LodProxyRegion region : loadedRegions.values()) {
            renderEntries.add(new LodRenderEntry(
                    region.worldX(),
                    region.worldY(),
                    region.worldZ(),
                    region.key().lodLevel(),
                    List.copyOf(region.meshState().getOpaqueMeshes()),
                    List.copyOf(region.meshState().getTransparentMeshes())
            ));
        }
        return List.copyOf(renderEntries);
    }

    // ----------------------------------------------------------------
    // Direct region access (renderer + tests)
    // ----------------------------------------------------------------

    public LodProxyRegion getRegion(LodRegionKey key) {
        return loadedRegions.get(key);
    }

    /** Returns a live collection of all loaded regions for GL-thread mesh upload. */
    public Iterable<LodProxyRegion> getLoadedRegionsForUpload() {
        return loadedRegions.values();
    }

    /** Inserts a region directly. Intended for tests and renderer bootstrapping. */
    public void putRegion(LodProxyRegion region) {
        loadedRegions.put(region.key(), region);
    }

    // ----------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------

    public int getLoadedRegionCount() {
        return loadedRegions.size();
    }

    public int getPendingMeshCount() {
        int count = 0;
        for (LodProxyRegion region : loadedRegions.values()) {
            if (region.meshState().isMeshQueued()) {
                count++;
            }
        }
        return count;
    }

    public long getApproximateLoadedMeshMemoryBytes() {
        long totalBytes = 0L;
        for (LodProxyRegion region : loadedRegions.values()) {
            totalBytes += region.meshState().getApproximateMeshMemoryBytes();
        }
        return totalBytes;
    }

    public LodDebugStats getDebugStats(long demotionCount) {
        return new LodDebugStats(
                getLoadedRegionCount(),
                getPendingMeshCount(),
                getApproximateLoadedMeshMemoryBytes(),
                demotionCount
        );
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    public void shutdown() {
        taskQueue.shutdown();
    }
}
