# LOD Refactor Plan — Veloren-Style Permanent Distant Terrain

**Status:** Planning  
**Target:** Replace the current swap-based tiered LOD system with a permanent, coexisting distant-terrain renderer that is never unloaded and never swapped.

---

## 1. The Core Problem With the Current System

The current LOD system (`LodStreamer` + `LodProxyManager` + `LodConfig.TIERS`) works as a set of **distance bands**. When the player moves:

- Inner LOD tiers get unloaded as near-field chunks fill their space.
- Outer LOD tiers get unloaded when the player exits their annular band.
- Near-field chunks load on top of (or instead of) LOD regions.

This is a **swap model** — the world is covered by whichever system "owns" each distance band right now. The fundamental flaw is that at any given moment, one representation is being torn down while another is being built up. This creates the visible gaps, popping, and boundary mismatches described in `betterlod.md`.

**Root structural causes in the current code:**

| Issue | Where it lives |
|---|---|
| Tiered annular bands — each tier has an inner exclusion zone that is actively unloaded | `LodConfig.TIERS`, `CylinderStreamer.isInTierBand()`, `LodStreamer.maybeUnloadDistantRegions()` |
| LOD regions unloaded when player moves out of band | `LodStreamer.maybeUnloadDistantRegions()` — `removeRegion()` called on any key outside the band |
| Multi-tier geometry at different resolutions that must tile seamlessly | `LodTierConfig`, `LodTierSpec`, `LodProxyGenerator` using tier-specific `sampleStep` and `regionSizeChunks` |
| Near-field exclusion zone hardwired into tier inner radii | `LodConfig.buildTiers()` derives `innerHRadiusChunks` from `WorldConstants.NEAR_FIELD_H_RADIUS` |
| No overlap between systems — near chunks replace LOD, LOD fills the void behind near | `LodProxyManager.requestProxyMeshAsync()` skips keys already in `loadedRegions` but never keeps both |

---

## 2. Target Architecture: Permanent Coexisting Background Layer

### The Mental Model

```
┌───────────────────────────────────────────────────────────┐
│ DISTANT TERRAIN (always present, never swapped)           │
│  - Single coarse world-coverage mesh built from           │
│    heightmap + color columns                              │
│  - No inner exclusion zone — extends under near chunks    │
│  - Never unloaded; updates lazily as player moves far     │
└───────────────────────────────────────────────────────────┘
          ↑ rendered first (background)
┌───────────────────────────────────────────────────────────┐
│ NEAR-FIELD CHUNKS (unchanged)                             │
│  - Full 32³ voxel resolution                              │
│  - Loaded/unloaded exactly as today                       │
│  - Rendered on top of the distant terrain layer           │
└───────────────────────────────────────────────────────────┘
          ↑ rendered second (foreground), occludes background
```

The near-field system **never changes**. The distant terrain system is a **background layer** that simply continues to exist. When near chunks load over a distant terrain cell, the distant terrain for that cell is still rendered — the near chunks just occlude it. There is no swap, no gap, no transition boundary to manage.

### What Changes

| Current | Target |
|---|---|
| 3 tiered LOD bands, inner-exclusion annuli | 1 single uniform distant terrain coverage, no exclusion |
| Regions unloaded when player moves out of band | Regions never unloaded; only expanded outward as player travels further |
| Multi-resolution tiers with different `sampleStep` | Single coarse `sampleStep` (e.g. 4 voxels per cell) across all distant terrain |
| `LodConfig.TIERS` drives inner radius from `NEAR_FIELD_H_RADIUS` | Distant terrain starts at distance 0 from origin — no exclusion zone |
| `LodStreamer` sweeps annular bands | `DistantTerrainStreamer` sweeps expanding square horizon, never removes |
| `LodProxyManager` owns removal lifecycle | `DistantTerrainManager` owns permanently-resident region map; removal only on explicit eviction (e.g. far beyond any reasonable horizon) |

---

## 3. New Class Inventory

The refactor replaces the `lod/` package's streaming and manager with purpose-built counterparts. The pure data and meshing classes can be reused or adapted.

### Classes to **Delete**

| Class | Reason |
|---|---|
| `LodTierSpec` | Tier concept is removed |
| `LodTierConfig` | Tier concept is removed |
| `LodConfig` | Tier configuration replaced by `DistantTerrainConfig` |
| `LodStreamer` | Swap-based band streaming replaced by `DistantTerrainStreamer` |
| `LodRegionKey.lodLevel` field | All distant terrain is a single level; level field becomes dead weight |

### Classes to **Rename / Repurpose**

| Current | New Name | Change |
|---|---|---|
| `LodRegionKey` | `DistantRegionKey` | Drop `lodLevel` field (or keep as constant 0 for minimal diff) |
| `LodProxyManager` | `DistantTerrainManager` | Remove tier logic; change removal policy to eviction-only |
| `LodProxyPort` | `DistantTerrainPort` | Update method names to match new semantics |
| `LodProxyGenerator` | `DistantTerrainGenerator` | Single uniform `sampleStep`, no tier lookup |
| `LodProxyRegion` | `DistantRegion` | Rename only |
| `LodProxyMeshState` | `DistantMeshState` | Rename only |
| `LodRenderEntry` | `DistantRenderEntry` | Rename only |
| `LodDebugStats` | `DistantTerrainDebugStats` | Rename only |
| `ProxyMeshSectionBuilder` | `DistantMeshSectionBuilder` | Rename only |
| `ProxyQuadEmitter` | `DistantQuadEmitter` | Rename only |

### Classes to **Create**

| Class | Responsibility |
|---|---|
| `DistantTerrainConfig` | Single source of truth for distant terrain: region size in chunks, sample step, horizon radius (in regions). No tiers. |
| `DistantTerrainStreamer` | Expands terrain coverage outward from player position. Never removes. Submits regions in nearest-first order up to in-flight cap. Eviction-only safety valve for regions beyond hard horizon limit. |

### Classes **Unchanged**

| Class | Notes |
|---|---|
| `NearChunkStreamer` | Completely untouched |
| `CylinderStreamer` | Movement detection methods still used; tier-band methods deprecated/removed |
| `WorldRenderSnapshot` | Rename field `farFieldEntries` → `distantTerrainEntries` for clarity (cosmetic) |
| `ChunkCuller` | `isFrustumVisible` for distant entries is still correct |
| `AbyssWorld` | Wiring updated: `LodStreamer` + `LodProxyManager` → `DistantTerrainStreamer` + `DistantTerrainManager` |

---

## 4. New Configuration: `DistantTerrainConfig`

Replaces `LodConfig` entirely. Flat, no tiers.

```java
public final class DistantTerrainConfig {

    /** Side length of one distant terrain region, in chunks. */
    public static final int REGION_SIZE_CHUNKS = 4;

    /**
     * Voxels skipped per height-sample grid cell.
     * 4 voxels/cell means one height sample per 4×4 voxel column.
     * Coarser than the finest current LOD tier but consistent everywhere.
     */
    public static final int SAMPLE_STEP = 4;

    /**
     * World-voxel footprint of one region side.
     * = REGION_SIZE_CHUNKS × WorldConstants.CHUNK_SIZE
     */
    public static final int REGION_SIZE_VOXELS = REGION_SIZE_CHUNKS * WorldConstants.CHUNK_SIZE;

    /**
     * Horizontal coverage radius in regions beyond which the terrain streamer
     * will never load new regions. Acts as a hard memory cap.
     * Must be large enough to cover the player's maximum view distance.
     */
    public static final int HORIZON_H_RADIUS_REGIONS = 64;

    /** Vertical coverage radius in regions (abyss is deep, keep this generous). */
    public static final int HORIZON_V_RADIUS_REGIONS = 32;

    /**
     * Hard eviction distance in regions. Regions further than this from the player
     * are eligible for removal even in the permanent-background model.
     * Set significantly beyond HORIZON_H_RADIUS_REGIONS to keep visual continuity
     * when the player rapidly reverses direction.
     */
    public static final int EVICTION_H_RADIUS_REGIONS = 80;
    public static final int EVICTION_V_RADIUS_REGIONS = 40;

    private DistantTerrainConfig() {}
}
```

**Key design decisions:**
- `REGION_SIZE_CHUNKS = 4` gives 128-voxel regions — consistent with the current tier-0 proxy regions, so `LodProxyGenerator`'s internal layout maps directly.
- `SAMPLE_STEP = 4` is coarser than the current finest tier (step=2) but is uniform everywhere. This trades a small amount of close-LOD fidelity for complete elimination of tier seams.
- No inner exclusion zone. The distant terrain is generated starting at the player's own region and extends outward.

---

## 5. New Streaming Policy: `DistantTerrainStreamer`

Replaces `LodStreamer`. Key behavioral differences:

### What it does NOT do (compared to `LodStreamer`)
- Does **not** check `isInTierBand` — there is no band/annulus concept.
- Does **not** call `removeRegion` during normal sweep — regions are permanent.
- Does **not** use `UNLOAD_HYSTERESIS_REGIONS` — hysteresis is irrelevant when there is no unloading.
- Does **not** loop over multiple tiers.

### What it does
1. **Sweep:** For every region within `HORIZON_H_RADIUS_REGIONS × HORIZON_V_RADIUS_REGIONS` of the player, submit if not already known. Sort nearest-first. Cap in-flight tasks at `MAX_IN_FLIGHT_TASKS`.
2. **Reprioritise:** For every already-known region, call `reprioritiseAsync` with its current distance rank, so nearby regions always finish before far ones.
3. **Eviction (safety valve only):** Periodically scan `loadedRegionKeys`. If a region is further than `EVICTION_H_RADIUS_REGIONS` from the player, mark it for removal. This prevents unbounded VRAM growth if the player teleports thousands of regions away. This fires on a long timer (e.g. every 30 seconds) rather than every 2 seconds — eviction is a last resort, not a routine operation.

### Pseudocode

```
update(camera, dt):
    pollCompletedMeshes()
    maybeSweepHorizon(camera.position, dt)       // frequent, submits new regions
    maybeSafetyEvict(camera.position, dt)        // rare, removes truly distant regions

maybeSweepHorizon(pos, dt):
    if not (positionChanged || movementSweep || idleResweep): return
    candidates = all region coords within HORIZON_H/V_RADIUS of pos
    sort candidates nearest-first
    inFlight = pendingRegionCount()
    for each candidate:
        if known: reprioritise(candidate, rank)
        else if inFlight < MAX_IN_FLIGHT: submit(candidate, rank); inFlight++

maybeSafetyEvict(pos, dt):
    evictionTimer += dt
    if evictionTimer < EVICTION_INTERVAL_SECONDS: return
    evictionTimer = 0
    for each loadedKey:
        if distanceRegions(key, pos) > EVICTION_H/V_RADIUS: removeRegion(key)
```

---

## 6. Updated `DistantTerrainManager` (was `LodProxyManager`)

Only the removal policy changes meaningfully:

- `requestRegionAsync` — identical to current `requestProxyMeshAsync`, no tier offset needed.
- `removeRegion` — still exists, but called only from the safety eviction path in `DistantTerrainStreamer`.
- `uploadPendingMeshes` / `pollCompletedMeshes` / `getRenderEntries` — unchanged in structure.
- `LOD_PRIORITY_OFFSET` — renamed `DISTANT_TERRAIN_PRIORITY_OFFSET`, same value and semantics.

The `loadedRegions` map grows monotonically during normal play rather than oscillating. This is intentional and a core property of the new design.

---

## 7. Updated `DistantTerrainGenerator` (was `LodProxyGenerator`)

- Remove `LodConfig.TIERS.get(key.lodLevel())` tier lookup.
- Replace with `DistantTerrainConfig.REGION_SIZE_CHUNKS` and `DistantTerrainConfig.SAMPLE_STEP` directly.
- All mesh generation logic (`buildSurfaceMesh`, `emitSurfaceGeometry`, `emitSideWalls`, etc.) is **unchanged** — the geometry algorithm is already correct; only the configuration source changes.

---

## 8. `CylinderStreamer` Cleanup

`CylinderStreamer` currently exposes tier-band methods that will no longer be used:

| Method | Fate |
|---|---|
| `isInTierBand(dx, dy, dz, LodTierConfig)` | **Delete** — no tiers |
| `isInTierUnloadZone(dx, dy, dz, LodTierConfig, int)` | **Delete** — no tiers, no unload zones |
| `isOutsideKeepZone(dx, dy, dz, hPad, vPad)` | **Keep** — still used by `NearChunkStreamer` |
| `updateAndCheckMovement(pos, dt, threshold)` | **Keep** — used by both streamers |
| `createNearestFirstComparator(...)` | **Keep** if present, otherwise streamer does its own sort |

The `bandHRadius` and `bandVRadius` constructor parameters become unused. The constructor should be simplified to just `(hRadius, vRadius)` for near-field, and `DistantTerrainStreamer` uses `DistantTerrainConfig` directly rather than going through `CylinderStreamer` for horizon geometry.

---

## 9. `AbyssWorld` Wiring Changes

```java
// Before
lodProxyManager = new LodProxyManager(
    () -> new LodProxyGenerator(generatorFactory),
    SharedWorkerPool.get().executor()
);
lodStreamer = new LodStreamer(lodProxyManager, cylinderStreamer);

// After
distantTerrainManager = new DistantTerrainManager(
    () -> new DistantTerrainGenerator(generatorFactory),
    SharedWorkerPool.get().executor()
);
distantTerrainStreamer = new DistantTerrainStreamer(distantTerrainManager);

// CylinderStreamer no longer needs bandH/bandV params
cylinderStreamer = new CylinderStreamer(
    WorldConstants.NEAR_FIELD_H_RADIUS,
    WorldConstants.NEAR_FIELD_V_RADIUS
);
```

`WorldRenderSnapshot` gains a rename:
```java
record WorldRenderSnapshot(
    List<BaseRenderEntry> nearFieldEntries,
    List<DistantRenderEntry> distantTerrainEntries   // was farFieldEntries
)
```

---

## 10. `WorldConstants` Cleanup

The following constants become dead weight and should be removed:

| Constant | Reason |
|---|---|
| `LOD_BAND_H_RADIUS` (if present) | Replaced by `DistantTerrainConfig.HORIZON_H_RADIUS_REGIONS` |
| `LOD_BAND_V_RADIUS` (if present) | Replaced by `DistantTerrainConfig.HORIZON_V_RADIUS_REGIONS` |

The `NEAR_FIELD_H_RADIUS` and `NEAR_FIELD_V_RADIUS` constants are **unchanged** — `NearChunkStreamer` and `CylinderStreamer` continue to use them.

---

## 11. Impact on Existing Tests

| Test Class | Impact |
|---|---|
| `LodStreamerTest` | **Rewrite** as `DistantTerrainStreamerTest` — verify no-removal contract, horizon sweep, safety eviction |
| `LodProxyManagerTest` | **Rename** to `DistantTerrainManagerTest` — most assertions still valid; remove tier-specific assertions |
| `WorldRenderSnapshotTest` | Minor: update field name in assertions |
| `NearChunkStreamerTest` | **None** — near-field system untouched |
| `ChunkMesherTest` | **None** |
| `ChunkCullerTest` | **None** |

New test coverage required:
- `DistantTerrainStreamerTest.regionsAreNeverRemovedDuringNormalPlay()` — assert that after sweeping a new player position, no regions loaded from the previous position are removed.
- `DistantTerrainStreamerTest.safetyEvictionRemovesOnlyTrulyDistantRegions()` — assert that regions within `EVICTION_H_RADIUS` are never evicted even after the long timer fires.
- `DistantTerrainStreamerTest.horizonSweepCoversAllRegionsWithinRadius()` — assert completeness of coverage.

---

## 12. Rendering Correctness Notes

Because the distant terrain now renders under near chunks (no exclusion zone), the renderer needs to ensure:

1. **Depth test is enabled** for both passes — near chunk geometry will correctly occlude distant terrain since near chunks sit at the correct world-space position and write depth.
2. **No z-fighting** — distant terrain cells that exactly underlay near-field chunk geometry could z-fight. This is acceptable at the default `sampleStep=4` resolution since distant terrain geometry is coarser and will not produce co-planar faces with full-voxel chunks. If z-fighting is observed, a small negative polygon offset on the distant terrain shader resolves it.
3. **Draw order** — render distant terrain first (writes depth), then near-field chunks on top. This is the natural order given the two-pass structure already in `WorldRenderSnapshot`.

No shader changes are required beyond the existing distant terrain draw path.

---

## 13. Migration Sequence (Recommended Order)

Perform these steps in order to keep the codebase compiling and tests green at each stage:

### Phase 1 — Introduce `DistantTerrainConfig`, keep old system running
1. Create `DistantTerrainConfig` (new file, no deletions yet).
2. Add `DistantRegionKey` as a rename of `LodRegionKey` without the `lodLevel` field, or deprecate `lodLevel`.
3. Add `DistantTerrainGenerator` that reads from `DistantTerrainConfig` instead of `LodConfig.TIERS`. Wire it in as an alternative path (both old and new coexist briefly).
4. Run all existing tests — they must still pass.

### Phase 2 — New streamer, parallel to old
1. Implement `DistantTerrainStreamer` with the no-removal contract.
2. Implement `DistantTerrainManager` / `DistantTerrainPort`.
3. Add `DistantTerrainStreamerTest` targeting the no-removal contract.
4. Do **not** wire into `AbyssWorld` yet.

### Phase 3 — Swap in `AbyssWorld`
1. Replace `LodProxyManager` + `LodStreamer` wiring in `AbyssWorld.init()` with `DistantTerrainManager` + `DistantTerrainStreamer`.
2. Update `WorldRenderSnapshot` field name.
3. Update `WorldDebugStatsProvider` to use new debug stats type.
4. Run all tests.

### Phase 4 — Delete old classes
1. Delete: `LodConfig`, `LodTierSpec`, `LodTierConfig`, `LodStreamer`, `LodProxyManager`, `LodProxyPort`.
2. Rename remaining `Lod*` classes to `Distant*` (IDE rename refactor).
3. Clean `CylinderStreamer` of tier-band methods.
4. Clean `WorldConstants` of obsolete LOD radius constants.
5. Run all tests.

### Phase 5 — Validate behaviour
1. Visual inspection: load world, move far from start, confirm no popping or gaps at LOD boundaries.
2. Confirm memory: `DistantTerrainManager.getLoadedRegionCount()` grows as player moves, stays stable when player is stationary.
3. Confirm eviction: move player to extreme distance, wait for eviction timer, confirm old regions evicted.

---

## 14. Risk Assessment

| Risk | Likelihood | Mitigation |
|---|---|---|
| VRAM growth without eviction | Medium | `EVICTION_H/V_RADIUS_REGIONS` hard cap; debug stat monitors region count |
| Z-fighting between distant terrain and near chunks | Low | Polygon offset on distant terrain shader if observed; coarser geometry at `sampleStep=4` makes it unlikely |
| Distant terrain generation cost at large horizon radius | Medium | `MAX_IN_FLIGHT_TASKS` cap prevents flooding; existing priority queue already handles this |
| Near-field chunk generation competing with distant terrain | Low | `DISTANT_TERRAIN_PRIORITY_OFFSET` (100_000_000) ensures near tasks always run first |
| Test suite relying on removal assertions | Low | `LodStreamerTest` is the only affected test; clearly documented above |

---

## 15. Definition of Done

- [ ] `DistantTerrainConfig` created, all radii/sizes defined.
- [ ] `DistantTerrainGenerator` using config directly (no tier lookup).
- [ ] `DistantTerrainStreamer` implemented with no-removal contract and safety eviction.
- [ ] `DistantTerrainManager` + `DistantTerrainPort` implemented.
- [ ] `AbyssWorld` wired to new classes.
- [ ] `WorldRenderSnapshot.distantTerrainEntries` renamed.
- [ ] All `Lod*` streaming classes deleted.
- [ ] `CylinderStreamer` tier-band methods removed.
- [ ] `DistantTerrainStreamerTest` passing with no-removal assertion.
- [ ] All pre-existing tests green.
- [ ] Architecture doc (`codebase_architecture.md`) updated to reflect new package map and data flows.
- [ ] Visual inspection confirms no popping or gaps.

---

*Created: April 2026. Implements the Veloren-style permanent background terrain approach described in `betterlod.md`.*
