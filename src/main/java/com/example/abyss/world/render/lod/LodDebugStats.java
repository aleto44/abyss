package com.example.abyss.world.render.lod;

/**
 * Debug-facing snapshot of far-field proxy state.
 *
 * @param loadedProxyRegionCount loaded far-field proxy region count
 * @param pendingProxyMeshCount pending far-field proxy mesh build count
 * @param approximateLoadedProxyMeshMemoryBytes approximate CPU/GPU-side proxy mesh memory
 * @param promotionCount proxy-to-near promotion count
 * @param demotionCount near-to-proxy demotion count
 */
public record LodDebugStats(
        int loadedProxyRegionCount,
        int pendingProxyMeshCount,
        long approximateLoadedProxyMeshMemoryBytes,
        long promotionCount,
        long demotionCount
) {

    public static LodDebugStats empty() {
        return new LodDebugStats(0, 0, 0L, 0L, 0L);
    }
}
