# Implementation Plan: Veloren-Style Distant Terrain + Y-Layered Horizon Maps

**Status:** Planning  
**Goal:** Move the distant terrain system to the "one continuous background surface + depth-buffer override" pattern
described in `distantLOD_nearchunk_override.md`, and add Y-layered horizon maps for distant shadowing.  
**Voxelisation shader deferred to a later milestone.**

---

## 1. Current State Assessment

### What is already correct
- `DistantTerrainStreamer` — permanent background, never-unload contract, horizon-expanding sweeps. ✅
- `DistantTerrainManager` — monotonically growing region map, async pipeline, budget-capped GPU uploads. ✅
- `WorldRenderSnapshot` — `distantTerrainEntries` field separate from `nearFieldEntries`. ✅
- `FirstPersonWorldRenderer` — distant terrain drawn first (opaque, then transparent), near-field drawn second; depth-buffer override is structurally in place. ✅
- `AbyssWorld` wiring — `DistantTerrainManager` + `DistantTerrainStreamer` live alongside `NearChunkStreamer`. ✅

### What is incomplete / the gap relative to the target pattern

| Gap | Impact |
|---|---|
| `DistantTerrainGenerator` only finds the **topmost solid Y** per column — emits a single top-surface mesh per region. Abyss is a deep vertical world with distinct solid surfaces at multiple Y bands (cliff lips, cave ceilings, layer floors). The distant view is missing all sub-surface geometry. | Visual: open abyss shaft looks empty from a distance |
| Mesh vertex layout stores **colour only** (`position + normal + color`). There is no lighting/shadow signal on distant geometry. | Visual: flat, unlit distant terrain |
| No horizon map data structure, generation pipeline, or shader input exists anywhere. | Prerequisite for distant shadowing |
| The Y-layered horizon map problem is unique to Abyss: the world spans ~7 500 voxels vertically across 9 distinct `Layer` bands. A single horizon map (as used by Veloren) captures only the top crust. We need **one horizon map slice per visible Y band** so that overhangs, cave mouths, and abyss rim geometry can cast correct directional occlusion. | Core architectural gap |
| `FirstPersonWorldRenderer` uses the same flat-colour shader for both near and distant passes. No distinct distant terrain shader program exists. | Prerequisite for future horizon-map sampling |

---

## 2. Target Architecture

```
┌─────────────────────────────────────────────────────┐
│  DISTANT TERRAIN (permanent background)             │
│  Multi-surface regions — one mesh section per       │
│  Y layer band that has visible geometry             │
│  Rendered first in each pass (opaque then transp.)  │
│  Lit using Y-layered horizon maps (ambient occlusion│
│  approximation per band)                            │
└─────────────────────────────────────────────────────┘
         ↑ drawn first (depth-buffer fills background)
┌─────────────────────────────────────────────────────┐
│  NEAR-FIELD CHUNKS (unchanged)                      │
│  Full 32³ resolution, rendered after distant terrain│
│  Overwrites distant pixels via standard depth test  │
└─────────────────────────────────────────────────────┘
```

### Y-Layered Horizon Maps — concept

Veloren uses a single 2-D horizon map because its world has one main surface. Abyss has
`Layer.values().length` (currently 9) distinct terrain bands each with independent geometry.

Each horizon map slice answers: *"for a given (X, Z) column, at what relative Y within this
band does solid terrain first block a ray cast horizontally in direction D?"*

The horizon maps are pre-baked per distant region, stored as CPU-side arrays (small: one short
per grid cell per direction per layer band), and sampled in the distant terrain shader to modulate
ambient brightness. No real-time ray casting is involved at runtime.

```
HorizonMapSet
  └─ per Layer band: HorizonMapSlice
        └─ per compass direction (N, S, E, W or 8-way): short[gridW * gridW]
              value = normalised depth from band top where first solid surface occurs
```

---

## 3. Class Inventory — New and Changed

### New classes

| Class | Package | Responsibility |
|---|---|---|
| `HorizonMapSlice` | `world.render.distant.horizon` | CPU-side horizon data for one Layer band + one direction. Immutable value object. |
| `HorizonMapSet` | `world.render.distant.horizon` | Ordered collection of `HorizonMapSlice` for all Layer bands. Produced by the generator, consumed by the shader upload path. |
| `HorizonMapBuilder` | `world.render.distant.horizon` | Pure function: `(VoxelData[][], Layer, int gridW) → HorizonMapSlice`. No side effects. |
| `HorizonMapSetGenerator` | `world.render.distant.horizon` | Iterates all Layer bands, calls `HorizonMapBuilder` for each, assembles `HorizonMapSet`. Called from `DistantTerrainGenerator`. |
| `DistantTerrainShaderProgram` | `world.render.distant` | Builds and owns the dedicated GLSL shader for the distant terrain pass. Distinct from the near-field chunk shader. Accepts horizon map uniforms. |
| `HorizonMapTextureArray` | `world.render.distant.horizon` | Uploads a `HorizonMapSet` to a GL texture array (one layer per Y band) ready for shader sampling. GL-thread only. |

### Modified classes

| Class | Change |
|---|---|
| `DistantTerrainGenerator` | `generate()` now collects **all solid surfaces** per column (not just topmost) grouped by `Layer` band, and calls `HorizonMapSetGenerator`. Returns extended `DistantRegionData` (see below). |
| `DistantRegion` | Add `HorizonMapSet horizonMaps` field alongside `DistantMeshState meshState`. |
| `DistantMeshState` | Add `HorizonMapTextureArray horizonTexture` field; lifecycle mirrors `opaqueMeshes`. |
| `DistantTerrainManager` | `uploadPendingMeshes()` also uploads `HorizonMapTextureArray` from the pending data. `getRenderEntries()` includes horizon texture handle in `DistantRenderEntry`. |
| `DistantRenderEntry` | Add `HorizonMapTextureArray horizonMaps` field so the renderer can bind the texture array per region. |
| `FirstPersonWorldRenderer` | Split distant terrain render into its own `DistantTerrainShaderProgram`; bind horizon map texture array per region before the draw call. |
| `DistantMeshSectionBuilder` | No vertex layout change yet — horizon maps drive a uniform, not per-vertex data. Layout stays `position(3) + normal(3) + color(4)`. |
| `DistantTerrainConfig` | Add `HORIZON_MAP_DIRECTIONS` (4 or 8) and `HORIZON_MAP_LAYER_COUNT` (= `Layer.values().length`) constants. |

### Return type bridging

`DistantTerrainGenerator.generate()` currently returns `MeshData`. After this change it needs
to return both mesh data and horizon map data. Introduce a small carrier:

```java
// world.render.distant
record DistantRegionData(MeshData meshData, HorizonMapSet horizonMaps) {}
```

Update `DistantTerrainManager`'s `KeyedTaskQueue` type parameter from `MeshData` to
`DistantRegionData`. No change to `KeyedTaskQueue` itself.

---

## 4. Horizon Map Design

### Rationale for Y-layered maps

| Concern | Answer |
|---|---|
| Why not one map? | The abyss spans ~7 500 voxels vertically. A single map captures only the surface crust; cave mouths, hanging garden floors, and the mycelium ceiling all need independent horizon data. |
| Why not per-voxel? | Horizon maps are sampled at distant-terrain resolution (1 sample per `SAMPLE_STEP` voxels). One short per cell per direction per band is negligible memory. |
| How many directions? | Start with 4 cardinal (N/S/E/W). Expand to 8 (adding diagonals) if the shadow quality justifies the memory cost. Controlled by `DistantTerrainConfig.HORIZON_MAP_DIRECTIONS`. |

### Data layout

```
Per region, per Layer band B, per direction D:
  short horizonDepth[gridW * gridW]

  value encoding: normalised Y within band, stored as short in [0, SHORT_MAX]
  0        = sky / no occlusion in this band
  SHORT_MAX = bottom of band fully blocked
```

### Generation algorithm (per band, per direction)

For each (gx, gz) grid cell:
1. Cast a ray horizontally in direction D across the grid cells of this region.
2. At each step, query whether any voxel in the column at (stepX, gz) within the band Y-range
   is solid above the current cell's surface height.
3. Record the angle of the first blocking column as a normalised depth value.
4. Stop at region boundary — inter-region horizon blending is a future enhancement.

This is O(gridW² × directions × bands) per region — all CPU-side, background thread.

### Shader usage (simplified)

```glsl
uniform sampler2DArray u_horizonMaps; // one layer per Y band

// In fragment shader:
// Lookup occlusion contribution from each relevant band
// Modulate ambient term based on how blocked the horizon is in each direction
// Sum contributions weighted by which Layer band the fragment belongs to
```

---

## 5. Multi-Surface Mesh Generation

`DistantTerrainGenerator` currently emits geometry only for the topmost solid Y per column.
The abyss world has meaningful solid surfaces below the top crust (cave floors, hanging ledges,
layer boundaries).

### Change to `generate()`

Instead of `findTopmostSolidY`, scan downward through **all Layer band boundaries** within the
region's Y range and collect the topmost solid Y **within each band** that has a solid column
in it.

```
For each (gx, gz):
  For each Layer band that overlaps this region's Y range:
    Find topmost solid Y within [band.yEnd, band.yStart]
    If found → emit a top-face quad for that band surface
              → emit side walls vs neighbours in the same band
```

This produces one `MeshData` section per active band rather than a single section for the whole
region. The `DistantMeshSectionBuilder` already handles multi-section output — no change needed.

### Impact on render count

A deep region covering multiple Layer bands will have more draw calls than today, but the
section count is bounded by `Layer.values().length` (currently 9) × sections per band.
Given the sparse nature of distant regions this is acceptable.

---

## 6. Migration Sequence

### Phase 1 — `DistantTerrainShaderProgram` (no behaviour change)

1. Extract the distant-terrain GLSL from `FirstPersonWorldDebugConstants` (or wherever the shader
   source currently lives) into `DistantTerrainShaderProgram`.
2. `FirstPersonWorldRenderer` constructs `DistantTerrainShaderProgram` alongside the existing
   near-field `ShaderProgram`.
3. Wire the distant pass to use `DistantTerrainShaderProgram`.
4. **Tests:** compile-only; no functional change, all existing tests pass.

### Phase 2 — Horizon map data structures (CPU only, not yet rendered)

1. Implement `HorizonMapSlice`, `HorizonMapSet` as plain value objects.
2. Implement `HorizonMapBuilder` — pure function, unit-testable without GL.
3. Implement `HorizonMapSetGenerator` — iterates all Layer bands, calls `HorizonMapBuilder`.
4. Add `DistantRegionData` carrier record.
5. **Tests:** Unit-test `HorizonMapBuilder` for a synthetic 2×2 grid with known solid columns
   — assert correct blocking depths for each direction.

### Phase 3 — Wire horizon map generation into `DistantTerrainGenerator`

1. Change `generate()` return type from `MeshData` to `DistantRegionData`.
2. After the surface mesh is built, call `HorizonMapSetGenerator.generate()` and pack the result
   into `DistantRegionData`.
3. Update `DistantTerrainManager` task queue type param `MeshData` → `DistantRegionData`.
4. `pollCompletedMeshes()` unpacks `DistantRegionData`; stores `HorizonMapSet` on the
   `DistantRegion`.
5. **Tests:** Integration smoke test — run `DistantTerrainGenerator` with real `ProceduralGenerator`
   for one region, assert `horizonMaps` is non-null and has `Layer.values().length` slices.

### Phase 4 — GPU upload of horizon maps

1. Implement `HorizonMapTextureArray` — wraps a libGDX `Texture` (or raw GL texture array)
   of dimensions `gridW × gridW × layerCount × directionCount`.
2. In `DistantTerrainManager.uploadPendingMeshes()`, after uploading mesh geometry, upload the
   `HorizonMapSet` into a `HorizonMapTextureArray` and store it on `DistantMeshState`.
3. `DistantRenderEntry` gains a `HorizonMapTextureArray horizonMaps()` accessor.
4. **Tests:** mock GL context or headless test — verify texture is constructed with correct
   dimensions.

### Phase 5 — Multi-surface mesh generation

1. Refactor `findTopmostSolidY` in `DistantTerrainGenerator` into a per-band surface scan.
2. Produce one `DistantMeshSectionBuilder` per active Layer band per region.
3. Pack all sections into the `MeshData` inside `DistantRegionData`.
4. **Tests:** Run generator for a deep region that spans multiple Layer bands; assert that the
   resulting `MeshData` has more than one opaque section.

### Phase 6 — Shader sampling of horizon maps

1. Extend `DistantTerrainShaderProgram` GLSL to accept `sampler2DArray u_horizonMaps`.
2. In the fragment shader, sample the horizon map for the fragment's Layer band and modulate the
   ambient light term.
3. In `FirstPersonWorldRenderer.renderDistantTerrainPass()`, bind the `HorizonMapTextureArray`
   for each region before the draw call.
4. **Tests:** Visual / manual verification in the debug app. No automated GL tests needed here.

---

## 7. Files to Create

```
src/main/java/com/example/abyss/world/render/distant/
  DistantRegionData.java               (Phase 3)
  DistantTerrainShaderProgram.java     (Phase 1)
  horizon/
    HorizonMapSlice.java               (Phase 2)
    HorizonMapSet.java                 (Phase 2)
    HorizonMapBuilder.java             (Phase 2)
    HorizonMapSetGenerator.java        (Phase 2)
    HorizonMapTextureArray.java        (Phase 4)
```

---

## 8. Files to Modify

```
DistantTerrainGenerator.java     — multi-surface scan; return DistantRegionData      (Phase 3, 5)
DistantTerrainManager.java       — queue type, unpack DistantRegionData, upload maps  (Phase 3, 4)
DistantTerrainConfig.java        — HORIZON_MAP_DIRECTIONS, HORIZON_MAP_LAYER_COUNT   (Phase 2)
DistantRegion.java               — add HorizonMapSet field                            (Phase 3)
DistantMeshState.java            — add HorizonMapTextureArray field                   (Phase 4)
DistantRenderEntry.java          — add horizonMaps() accessor                         (Phase 4)
FirstPersonWorldRenderer.java    — use DistantTerrainShaderProgram; bind horizon maps (Phase 1, 6)
```

**Unchanged:**  
`DistantTerrainStreamer`, `DistantTerrainPort`, `DistantTerrainStreamerTest`,  
`DistantTerrainManagerTest`, `NearChunkStreamer`, `CylinderStreamer`, `AbyssWorld`,  
`WorldRenderSnapshot`, `ChunkCuller`, `DistantMeshSectionBuilder`.

---

## 9. Out of Scope (Deferred)

- **Voxelisation shader** — excluded per brief; add after horizon maps are working.
- **Inter-region horizon blending** — horizon maps currently stop at region boundaries. Cross-region
  blending requires sampling neighbouring regions' maps. Defer to a polish pass.
- **Dynamic horizon map invalidation** — maps are baked at generation time and never updated.
  Correct for a deterministic world; revisit only if player-editable distant terrain is added.
- **Diagonal direction sampling** (8-way) — start with 4 cardinal. Upgrade path is purely additive:
  increase `HORIZON_MAP_DIRECTIONS` in config and extend the GLSL.

---

*Update this document as implementation progresses.*
