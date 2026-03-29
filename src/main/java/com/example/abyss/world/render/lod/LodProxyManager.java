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
 * Call {@link #requestProxyMeshAsync(LodRegionKey)} once per region from the
 * streamer, then call {@link #pollCompletedMeshes()} each frame on the main
 * thread to promote finished results into the resident mesh state.
 * </p>
 */
public class LodProxyManager {

    private final Map<LodRegionKey, LodProxyRegion> loadedRegions = new LinkedHashMap<>();
    private final KeyedTaskQueue<LodRegionKey, MeshData> taskQueue;
    private final ThreadLocal<LodProxyGenerator> generatorLocal;
    private long promotionCount;
    private long demotionCount;

    public LodProxyManager(Supplier<LodProxyGenerator> generatorFactory) {
        this.generatorLocal = ThreadLocal.withInitial(generatorFactory);
        this.taskQueue = KeyedTaskQueue.<LodRegionKey, MeshData>builder()
                .threadNamePrefix("LodProxyGen")
                .build();
    }

    /**
     * No-arg constructor for tests. Uses a default generator with seed 0.
     * Production code should use {@link #LodProxyManager(Supplier)}.
     */
    public LodProxyManager() {
        this(() -> new LodProxyGenerator(0L));
    }

    // ----------------------------------------------------------------
    // Async generation
    // ----------------------------------------------------------------

    /**
     * Requests proxy mesh generation for the given region if it is not already
     * loaded or pending. Non-blocking.
     *
     * @return {@code true} if a new generation task was submitted
     */
    public boolean requestProxyMeshAsync(LodRegionKey key) {
        boolean alreadyLoaded = loadedRegions.containsKey(key);
        if (alreadyLoaded) {
            return false;
        }
        return taskQueue.submit(key, () -> generatorLocal.get().generate(key));
    }

    /**
     * Returns {@code true} if the region is either loaded or pending generation.
     * Used by {@code LodStreamer} to avoid re-submitting known regions.
     */
    public boolean isRegionKnown(LodRegionKey key) {
        boolean isLoaded = loadedRegions.containsKey(key);
        boolean isPending = taskQueue.isKnown(key);
        return isLoaded || isPending;
    }

    /**
     * Drains completed proxy mesh results into the loaded region map.
     * Call once per frame on the main thread.
     */
    public void pollCompletedMeshes() {
        List<KeyedTaskQueue.Result<LodRegionKey, MeshData>> results = taskQueue.pollCompleted();
        for (KeyedTaskQueue.Result<LodRegionKey, MeshData> result : results) {
            LodRegionKey key = result.key();
            boolean stillWanted = loadedRegions.containsKey(key) || !result.value().isEmpty();
            if (!stillWanted) {
                continue;
            }

            LodProxyRegion existing = loadedRegions.get(key);
            LodProxyMeshState meshState;
            if (existing != null) {
                meshState = existing.meshState();
            } else {
                float worldX = key.regionX() * LodProxyGenerator.REGION_VOXELS_H;
                float worldY = key.regionY() * LodProxyGenerator.REGION_VOXELS_H;
                float worldZ = key.regionZ() * LodProxyGenerator.REGION_VOXELS_H;
                meshState = new LodProxyMeshState();
                loadedRegions.put(key, new LodProxyRegion(key, worldX, worldY, worldZ, meshState));
            }

            MeshData meshData = result.value();
            meshState.setMeshQueued(false);
            meshState.setMeshDirty(false);
            // Store as promoted meshes — LibGDX Mesh objects are created on the render side;
            // here we store the raw MeshData for the renderer to upload on demand.
            meshState.setPendingMeshData(meshData);
        }
    }

    // ----------------------------------------------------------------
    // Region lifecycle
    // ----------------------------------------------------------------

    public LodProxyRegion getRegion(LodRegionKey key) {
        return loadedRegions.get(key);
    }

    /**
     * Returns a live collection of all loaded regions.
     * Used by the renderer to upload pending mesh data on the GL thread.
     */
    public Iterable<LodProxyRegion> getLoadedRegionsForUpload() {
        return loadedRegions.values();
    }

    public void putRegion(LodProxyRegion region) {
        loadedRegions.put(region.key(), region);
    }

    public LodProxyRegion removeRegion(LodRegionKey key) {
        taskQueue.forget(key);
        return loadedRegions.remove(key);
    }

    // ----------------------------------------------------------------
    // Queries
    // ----------------------------------------------------------------

    public int getLoadedRegionCount() {
        return loadedRegions.size();
    }

    public int getPendingMeshCount() {
        int pendingMeshCount = 0;
        for (LodProxyRegion region : loadedRegions.values()) {
            boolean isMeshQueued = region.meshState().isMeshQueued();
            if (isMeshQueued) {
                pendingMeshCount++;
            }
        }
        return pendingMeshCount;
    }

    public long getApproximateLoadedMeshMemoryBytes() {
        long totalBytes = 0L;
        for (LodProxyRegion region : loadedRegions.values()) {
            totalBytes += region.meshState().getApproximateMeshMemoryBytes();
        }
        return totalBytes;
    }

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

    public LodDebugStats getDebugStats() {
        return new LodDebugStats(
                getLoadedRegionCount(),
                getPendingMeshCount(),
                getApproximateLoadedMeshMemoryBytes(),
                promotionCount,
                demotionCount
        );
    }

    public void recordPromotion() {
        promotionCount++;
    }

    public void recordDemotion() {
        demotionCount++;
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    public void shutdown() {
        taskQueue.shutdown();
    }
}