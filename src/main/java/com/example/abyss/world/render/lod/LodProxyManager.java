package com.example.abyss.world.render.lod;

import com.example.abyss.world.render.LodRenderEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns render-only far-field proxy regions.
 * <p>
 * This manager is intentionally inert in the first pass. It provides the
 * ownership boundary and snapshot/debug surfaces needed for future LOD work
 * without changing the near-field chunk pipeline.
 * </p>
 */
public class LodProxyManager {

    private final Map<LodRegionKey, LodProxyRegion> loadedRegions = new LinkedHashMap<>();
    private long promotionCount;
    private long demotionCount;

    public LodProxyRegion getRegion(LodRegionKey key) {
        return loadedRegions.get(key);
    }

    public void putRegion(LodProxyRegion region) {
        loadedRegions.put(region.key(), region);
    }

    public LodProxyRegion removeRegion(LodRegionKey key) {
        return loadedRegions.remove(key);
    }

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
}
