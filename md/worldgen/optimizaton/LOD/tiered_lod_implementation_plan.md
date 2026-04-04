# Tiered LOD Implementation Plan

**Status:** Planning — not yet implemented  
**Depends on:** Existing single-tier LOD system (Phases 1–5 of `lod_implementation_plan.md` are complete)  
**Goal:** Replace the single LOD band with an ordered list of tiers, each with its own region size and
sample step, separated by a mandatory buffer zone that prevents coordinate-space gaps at tier boundaries.

---

## The Problem Being Solved

The current system has two layers:

1. **Near-field** — full-detail chunks, 1 chunk per unit, loaded close to the player.
2. **LOD band** — one coarse proxy layer beyond that, using 2-chunk-wide regions and a fixed sample step.

This is fine for one LOD tier. The moment you add a second coarser tier with larger regions
(e.g. 4-chunk-wide), a seam problem appears. The two tiers use different coordinate grids.
A 4-chunk-wide region grid and a 1-chunk-wide region grid don't tile neatly at their shared boundary,
which produces gaps, double-coverage, and messy unload logic.

The **buffer zone** fixes this. It is always a 1-chunk-wide region tier sitting at the seam between
near-field and the first real LOD tier. Because 1-chunk regions align with everything by definition,
the buffer acts as a grout line — each subsequent tier can start at whatever size it wants, and the
boundary is always clean.

### ELI18 version

Picture three rings around you:
- **Ring 0 (buffer):** Close-ish, still pretty detailed, using 1-chunk boxes.
- **Ring 1 (LOD1):** Further out, slightly blurry, still using 1-chunk boxes but coarser sampling.
- **Ring 2 (LOD2):** Far away, very blurry, uses 4-chunk boxes (much cheaper, covers more ground per box).

The reason rings 0 and 1 both use 1-chunk boxes is so their edges match up perfectly.
Ring 2 uses 4-chunk boxes, which is fine because ring 1's outer edge is set to a multiple of 4,
so the grids tile cleanly. If you skipped the buffer and went straight from near-field to 4-chunk
boxes, the edge of near-field (8 chunks out) isn't necessarily a multiple of 4 — you'd get gaps.

---

## Architecture Decision

**Tier configuration is boot-time only.** The `LodConfig.TIERS` list is a static constant.
Changing tier geometry requires a restart. It is not hot-reloadable, and no code should attempt
to treat it as such.

**`LodRegionKey.lodLevel` is the tier index** into `LodConfig.TIERS`. A key is fully
self-describing — given just the key, you can recover its world position and generation parameters
without any other context, by looking up `LodConfig.TIERS.get(key.lodLevel())`.

**Buffer zone is `lodLevel = 0`.** It is not a special case in code — it is just the first tier.
`LodStreamer` treats all tiers identically.

---

## New Files

### `LodTierConfig.java`
`com.example.abyss.world.render.lod.LodTierConfig`

Pure data record. Immutable. No behaviour.

```java
/**
 * Immutable configuration for one tier of the LOD streaming system.
 * All radii are in chunks. Tiers are ordered innermost-first in {@link LodConfig#TIERS}.
 *
 * @param lodLevel            index into {@link LodConfig#TIERS}; 0 = buffer (finest), higher = coarser
 * @param innerHRadiusChunks  inner horizontal boundary in chunks (exclusive); must equal previous tier's outer
 * @param outerHRadiusChunks  outer horizontal boundary in chunks (exclusive); must be a multiple of regionSizeChunks
 * @param innerVRadiusChunks  inner vertical boundary in chunks (exclusive)
 * @param outerVRadiusChunks  outer vertical boundary in chunks (exclusive)
 * @param regionSizeChunks    chunks per region side; 1 for buffer/LOD1, 4 for LOD2, etc.
 * @param sampleStep          voxels skipped per LOD grid cell; higher = coarser mesh, cheaper generation
 */
public record LodTierConfig(
    int lodLevel,
    int innerHRadiusChunks,
    int outerHRadiusChunks,
    int innerVRadiusChunks,
    int outerVRadiusChunks,
    int regionSizeChunks,
    int sampleStep
) {
    /** World-voxel size of one region side for this tier. */
    public int regionSizeVoxels() {
        return regionSizeChunks * WorldConstants.CHUNK_SIZE;
    }

    /** Horizontal band half-width in region units (how many regions out to sweep). */
    public int outerHRadiusRegions() {
        return outerHRadiusChunks / regionSizeChunks;
    }

    /** Inner horizontal exclusion in region units. */
    public int innerHRadiusRegions() {
        return (int) Math.ceil((double) innerHRadiusChunks / regionSizeChunks);
    }

    /** Outer vertical half-height in region units. */
    public int outerVRadiusRegions() {
        return outerVRadiusChunks / regionSizeChunks;
    }

    /** Inner vertical exclusion in region units. */
    public int innerVRadiusRegions() {
        return (int) Math.ceil((double) innerVRadiusChunks / regionSizeChunks);
    }
}
```

---

### `LodConfig.java`
`com.example.abyss.world.render.lod.LodConfig`

**The single tuning panel.** All tier geometry lives here. Everything else derives from it.

```java
public final class LodConfig {

    // ---------------------------------------------------------------
    // Tier definitions — edit here to add, remove, or tune tiers.
    // All radii are in chunks (1 chunk = 32 voxels).
    // ---------------------------------------------------------------

    //  lodLevel | innerH | outerH | innerV | outerV | regionSize | sampleStep
    //  ---------|--------|--------|--------|--------|------------|------------
    //  0 buffer |      8 |     16 |      5 |     10 |     1      |     2
    //  1 LOD1   |     16 |     32 |     10 |     20 |     1      |     4
    //  2 LOD2   |     32 |     64 |     20 |     40 |     4      |     8

    public static final List<LodTierConfig> TIERS = List.of(
        new LodTierConfig(0,  8, 16,  5, 10, 1, 2),
        new LodTierConfig(1, 16, 32, 10, 20, 1, 4),
        new LodTierConfig(2, 32, 64, 20, 40, 4, 8)
    );

    static {
        validateTiers(TIERS);
    }
}
```

> ⚠️ The numbers above are **starting values for tuning**, not final values. Adjust freely.
> The validation block will throw at startup if the rules below are violated.

**Validation rules enforced at startup:**
1. `tier[0].innerHRadiusChunks == WorldConstants.NEAR_FIELD_H_RADIUS` — buffer must start at near-field edge.
2. `tier[n].innerHRadiusChunks == tier[n-1].outerHRadiusChunks` — tiers must be contiguous, no gaps or overlaps.
3. `tier[n].outerHRadiusChunks % tier[n].regionSizeChunks == 0` — outer boundary aligns to region grid.
4. Same continuity rule for vertical radii.

---

## Modified Files

### `WorldConstants.java`

**Remove:**
- `REGION_CHUNKS` — region size is now per-tier, not global.
- `LOD_BAND_H_RADIUS` — replaced by `LodTierConfig.outerHRadiusChunks` on the outermost tier.
- `LOD_BAND_V_RADIUS` — same.
- `LOD_PROXY_SAMPLE_STEP` — replaced by `LodTierConfig.sampleStep`.

**Keep:**
- `CHUNK_SIZE = 32` — still a global constant, all tiers derive from it.
- `NEAR_FIELD_H_RADIUS = 8` — still a global constant, `LodConfig` validates against it.
- `NEAR_FIELD_V_RADIUS = 5` — same.

> ⚠️ Before removing `REGION_CHUNKS`, grep the whole codebase. It is referenced in:
> `LodProxyGenerator`, `CylinderStreamer` (via derived constants), and potentially
> `NearChunkStreamer` / `ChunkLoader`. Any non-LOD references that survive must stay in
> `WorldConstants` under a more specific name.

---

### `CylinderStreamer.java`

**Remove:**
- `LOD_EXCLUSION_H_REGIONS` static constant — this was derived from the single global `REGION_CHUNKS`. It no longer exists.
- `LOD_EXCLUSION_V_REGIONS` static constant — same.
- The static initializer validation block — moves to `LodConfig.validateTiers`.
- `isInLodBand(int dx, int dy, int dz)` — replaced by the tier-aware version below.

**Add:**
```java
/**
 * Returns true if the region-space offset (dx, dy, dz) falls inside the given tier's
 * annulus — i.e. within the outer cylinder but outside the inner exclusion cylinder.
 * Offsets are in region units for the given tier's region size.
 */
public boolean isInTierBand(int dx, int dy, int dz, LodTierConfig tier) {
    int adx = Math.abs(dx);
    int adz = Math.abs(dz);
    boolean isInsideOuter = adx <= tier.outerHRadiusRegions()
                         && adz <= tier.outerHRadiusRegions()
                         && dy >= -tier.outerVRadiusRegions()
                         && dy <=  tier.outerVRadiusRegions();
    if (!isInsideOuter) {
        return false;
    }
    boolean isInsideInner = adx < tier.innerHRadiusRegions()
                         && adz < tier.innerHRadiusRegions()
                         && dy > -tier.innerVRadiusRegions()
                         && dy <  tier.innerVRadiusRegions();
    return !isInsideInner;
}
```

The existing `bandHRadius` / `bandVRadius` constructor fields become unused and should be removed,
or repurposed to represent the outermost tier's outer radius for any code that still needs a
single "how far does LOD go?" answer (e.g. the near-field streamer's keep-zone calculation).

---

### `LodProxyGenerator.java`

**Remove** the class-level `static final` constants:
- `REGION_VOXELS_H` — was `REGION_CHUNKS * CHUNK_SIZE`, now `tier.regionSizeVoxels()`
- `SAMPLE_STEP` — now `tier.sampleStep()`
- `GRID_W` — now computed per call

**Modify `generate(LodRegionKey key)`:**

```java
public MeshData generate(LodRegionKey key) {
    LodTierConfig tier     = LodConfig.TIERS.get(key.lodLevel());
    int regionSizeChunks   = tier.regionSizeChunks();
    int regionVoxelsH      = tier.regionSizeVoxels();
    int sampleStep         = tier.sampleStep();
    int gridW              = (regionVoxelsH + sampleStep - 1) / sampleStep;

    int baseChunkX = key.regionX() * regionSizeChunks;
    int baseChunkY = key.regionY() * regionSizeChunks;
    int baseChunkZ = key.regionZ() * regionSizeChunks;

    int worldBaseX = baseChunkX * WorldConstants.CHUNK_SIZE;
    int worldBaseY = baseChunkY * WorldConstants.CHUNK_SIZE;
    int worldBaseZ = baseChunkZ * WorldConstants.CHUNK_SIZE;

    VoxelData[][] chunks = generateRegionChunks(baseChunkX, baseChunkY, baseChunkZ, regionSizeChunks);
    return buildSurfaceMesh(chunks, worldBaseX, worldBaseY, worldBaseZ,
                            regionSizeChunks, regionVoxelsH, sampleStep, gridW);
}
```

All internal methods (`generateRegionChunks`, `resolveSurfaceHeights`, `buildSurfaceMesh`,
`findTopmostSolidY`, `emitSurfaceGeometry`, `emitTopFace`, `emitSideWalls`) gain
`regionSizeChunks`, `regionVoxelsH`, `sampleStep`, and `gridW` as explicit parameters
instead of reading class-level statics. No generation logic changes — only how the
dimensions are sourced.

---

### `LodProxyManager.java`

**Modify `requestProxyMeshAsync`** — the world origin calculation currently uses the hardcoded
`LodProxyGenerator.REGION_VOXELS_H`. That constant is going away.

```java
// Before (wrong for any tier with regionSizeChunks != 1):
float worldX = key.regionX() * LodProxyGenerator.REGION_VOXELS_H;

// After:
LodTierConfig tier = LodConfig.TIERS.get(key.lodLevel());
float worldX = key.regionX() * tier.regionSizeVoxels();
float worldY = key.regionY() * tier.regionSizeVoxels();
float worldZ = key.regionZ() * tier.regionSizeVoxels();
```

This is a **correctness bug** in the current codebase the moment a second tier is added.
LOD2 regions would be placed at 1/4 of their correct world position without this fix.

---

### `LodStreamer.java`

This is the largest logic change. The streamer currently has one sweep and one unload loop,
both operating on a single global region coordinate space. With tiers, each tier has its own
region coordinate space (`worldCoord / tier.regionSizeVoxels()`).

**Remove:**
- `REGION_SIZE` constant — replaced by per-tier lookup.
- `lastSweptRx/Ry/Rz` single set of sweep markers — needs one set per tier, or a rethink
  (see note below).

**Add:**
```java
private static int worldToRegionForTier(int worldCoord, LodTierConfig tier) {
    return Math.floorDiv(worldCoord, tier.regionSizeVoxels());
}
```

**Modify `submitBandCandidatesNearestFirst`** — loop over all tiers:

```java
private void submitAllTierCandidatesNearestFirst(Vector3 camPos) {
    for (LodTierConfig tier : LodConfig.TIERS) {
        int rx = worldToRegionForTier(Math.round(camPos.x), tier);
        int ry = worldToRegionForTier(Math.round(camPos.y), tier);
        int rz = worldToRegionForTier(Math.round(camPos.z), tier);
        submitTierCandidatesNearestFirst(rx, ry, rz, camPos, tier, tierBudget(tier));
    }
}
```

**Submission budget — per-tier sub-budgets (recommended):**

| Tier | Max submissions per sweep | Reason |
|------|--------------------------|--------|
| 0 (buffer) | 64 | small regions, cheap, high spatial priority |
| 1 (LOD1)   | 48 | moderate cost |
| 2 (LOD2)   | 16 | each generates 64 chunks, expensive |

A single global budget of 128 risks a heavy LOD2 sweep burning the budget before tier-0
buffer regions are submitted. Per-tier budgets prevent starvation of the nearest tiers.

**Modify `maybeUnloadDistantRegions`** — unload each key against its own tier's band:

```java
for (LodRegionKey key : proxyPort.getLoadedRegionKeys()) {
    LodTierConfig tier = LodConfig.TIERS.get(key.lodLevel());
    int rx = worldToRegionForTier(Math.round(observerPosition.x), tier);
    int ry = worldToRegionForTier(Math.round(observerPosition.y), tier);
    int rz = worldToRegionForTier(Math.round(observerPosition.z), tier);
    int dx = key.regionX() - rx;
    int dy = key.regionY() - ry;
    int dz = key.regionZ() - rz;
    boolean isNoLongerInBand = !cylinderStreamer.isInTierBand(dx, dy, dz, tier);
    if (isNoLongerInBand) {
        toRemove.add(key);
    }
}
```

**Sweep markers (`lastSweptRx` etc.):** The current design stores one set of region
coordinates to detect when a sweep is needed. With multi-tier, region coordinates differ
per tier. The simplest fix: store the raw world position used for the last sweep (as a
`Vector3`) and compare against `MOVEMENT_SWEEP_THRESHOLD` in world units, which is
tier-independent. Region change detection per tier becomes a byproduct of the sweep itself
rather than a pre-check.

---

### `StreamingZoneWireframeRenderer.java`

**Remove:** The single cyan `COLOR_LOD_BAND` box (drawn from `bandHRadius * REGION_SIZE`).

**Add:** One box per tier in `LodConfig.TIERS`, using `LOD_LEVEL_COLORS[tier.lodLevel()]`
(already defined for 4 levels). Each box uses:
- `tier.outerHRadiusChunks() * WorldConstants.CHUNK_SIZE` for horizontal extent
- `tier.outerVRadiusChunks() * WorldConstants.CHUNK_SIZE` for vertical extent

**Modify:** Per-region box sizing currently uses `LodProxyGenerator.REGION_VOXELS_H`.
Replace with `LodConfig.TIERS.get(entry.lodLevel()).regionSizeVoxels()`.

---

## Implementation Order

Execute in this order. Each step leaves the system in a working state.

### Step 1 — Add `LodTierConfig` and `LodConfig`
Create both new files. No existing code changes yet. Add the validation block.
At this point the new types exist but nothing uses them.

**Done when:** `LodConfig` initializes without throwing. Validation assertions pass for
the starting tier values.

---

### Step 2 — Migrate `LodProxyGenerator`
Remove the three static constants. Pass `regionSizeChunks`, `regionVoxelsH`, `sampleStep`,
`gridW` as parameters through the call chain. `generate(LodRegionKey)` looks up the tier
config. The generator is still called with `lodLevel=1` keys from `LodStreamer` — that
maps to `LodConfig.TIERS.get(1)` which is the existing LOD1 tier. Behaviour is identical
to before for tier-1 keys.

**Done when:** All existing tests still pass. `LodProxyGenerator` compiles with no
references to removed constants.

---

### Step 3 — Fix `LodProxyManager` world origin calculation
One-line fix. Low risk but must happen before Step 5 adds tier-2 keys.

**Done when:** World positions of existing tier-1 regions are identical to before the change.

---

### Step 4 — Migrate `CylinderStreamer`
Remove old static constants and `isInLodBand`. Add `isInTierBand`. Update the static
validation to delegate to `LodConfig.validateTiers` instead of doing its own checks.

**Done when:** `LodStreamer` compiles with the new method signature. Existing single-tier
sweep still produces the same results when called with the tier-1 config.

---

### Step 5 — Migrate `LodStreamer` to multi-tier sweep
Replace the single sweep with a tier loop. Add `worldToRegionForTier`. Switch to
per-tier submission budgets. Fix sweep marker tracking to use world position.
Fix unload loop to resolve per-key tier config.

At the end of this step, the buffer tier (tier-0) and LOD2 tier are live for the first time.

**Done when:** In-game, three distinct tiers are visible in the debug wireframe view.
Buffer zone shows green boxes, LOD1 yellow, LOD2 orange. No gaps at tier boundaries.
No double-coverage between near-field and the buffer zone.

---

### Step 6 — Update `StreamingZoneWireframeRenderer`
Draw per-tier band boxes. Fix per-region box sizing.

**Done when:** Debug view shows three distinct coloured band outlines. Per-region boxes
are sized correctly for each tier.

---

### Step 7 — Remove obsolete `WorldConstants` entries
Remove `REGION_CHUNKS`, `LOD_BAND_H_RADIUS`, `LOD_BAND_V_RADIUS`, `LOD_PROXY_SAMPLE_STEP`.
Fix any remaining compile errors.

**Done when:** No references to removed constants remain. All tests pass.

---

### Step 8 — Tune and validate
Adjust the tier radii and sample steps in `LodConfig.TIERS` based on real in-game observation.
Watch the metrics listed below.

---

## Test Coverage Requirements

The following test cases must be added or updated. Do not skip these — multi-tier introduces
correctness traps that are invisible at runtime until something looks obviously wrong.

| Test | What it proves |
|------|---------------|
| `tier0BufferRegionIsSubmittedBeyondNearField` | Buffer tier starts loading immediately past near-field edge |
| `tier2RegionIsPlacedAtCorrectWorldPosition` | 4-chunk region at `(1,0,1)` maps to world `(256,0,256)`, not `(64,0,64)` |
| `tier1KeyUnloadsUsingTier1Band` | A tier-1 key is tested against tier-1 limits, not tier-2 |
| `tier2KeyUnloadsUsingTier2Band` | A tier-2 key is tested against tier-2 limits |
| `tier2GeneratorProducesDifferentGridWThanTier1` | `generate()` uses correct `gridW` per tier |
| `lodConfigValidationRejectsNonContiguousTiers` | Validation throws if tier gap exists |
| `lodConfigValidationRejectsMisalignedOuterBoundary` | Validation throws if `outerH % regionSize != 0` |
| `lodConfigValidationRejectsBufferNotAtNearFieldEdge` | Validation throws if tier-0 inner != `NEAR_FIELD_H_RADIUS` |

---

## Risks and Mitigations

| Risk | Mitigation |
|------|------------|
| LOD2 generates 64 chunks per region (4³), expensive | Per-tier budget cap of 16 submissions; large `sampleStep=8` keeps mesh cost low |
| Sweep marker logic breaks with multiple coordinate spaces | Move to world-position-based change detection, tier-independent |
| Alignment gaps if tier boundary isn't multiple of region size | Static validation in `LodConfig` throws at startup before any rendering occurs |
| LOD2 world origin computed wrong (silent, hard to see) | Step 3 explicitly fixes this before tier-2 keys are ever created |
| Removing `REGION_CHUNKS` breaks non-LOD code | Explicit grep step before removal; Step 7 is last for this reason |
| Tier config changes at runtime cause stale keys | Tier config is boot-time only; document this clearly, add a comment in `LodConfig` |

---

## Metrics to Watch During Tuning

- Loaded region count per tier (add to debug overlay)
- Pending task count per tier
- Approximate mesh memory per tier
- Frame time delta when entering LOD2 range for the first time (cold load spike)
- Demotion count per tier per minute (high churn = band boundaries too tight)
- Visible gaps between near-field and buffer zone (should be zero)
- Visible gaps between buffer and LOD1 (should be zero)
- Visible pop at LOD1→LOD2 boundary (expected, but should not be jarring)

---

## What Is Explicitly Not In Scope

- Geomorphing / smooth LOD transitions between tiers
- Per-tier persistence or caching to disk
- Edit propagation into LOD regions (proxy meshes remain read-only)
- More than 3 tiers in the first implementation (add tier 3 only after tier 2 is tuned)
- Dynamic tier radius changes at runtime
