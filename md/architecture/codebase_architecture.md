# Abyss — Codebase Architecture Reference
> **Purpose:** AI agent reference. Describes every important class, its single responsibility, the data it owns, and the flows that connect them. Read this before generating or modifying any code.

---

## 1. Constants & Configuration Entry Point

### `WorldConstants`
`com.example.abyss.WorldConstants`

The **only** place radius/size tunables live. All other classes derive their geometry from these values.

| Constant | Value | Meaning |
|---|---|---|
| `CHUNK_SIZE` | 32 | Voxels per chunk side |
| `REGION_CHUNKS` | 4 | Chunks per LOD region side |
| `NEAR_FIELD_H_RADIUS` | 11 | Near-field horizontal radius (chunks) |
| `NEAR_FIELD_V_RADIUS` | 7 | Near-field vertical radius (chunks) |
| `LOD_BAND_H_RADIUS` | 24 | LOD outer horizontal radius (chunks) |
| `LOD_BAND_V_RADIUS` | 16 | LOD outer vertical radius (chunks) |

**Rule:** Never hardcode radii anywhere. Always reference `WorldConstants`.

---

## 2. Top-Level Façade

### `AbyssWorld`
`com.example.abyss.world.AbyssWorld`

**Single responsibility:** Wire up all subsystems and expose them via accessors. Contains zero business logic.

**Owns:**
- `seed` + `GenConfigResolver resolver` (immutable after construction)
- References to every subsystem (see below)

**Key lifecycle:**
```
new AbyssWorld(seed)   → init()   → [per-frame calls on subsystems]   → dispose()
```

**`init()` wiring order (important for AI agents):**
1. `generatorFactory` = `() -> new ProceduralGenerator(seed, resolver)` — shared factory, called per-thread
2. `ChunkManager` ← factory
3. `MeshCoordinator` ← ChunkManager
4. `WorldVoxelAccess` ← ChunkManager; edit listener → `MeshCoordinator::invalidateMeshForEdit`
5. `ChunkLoader` ← ChunkManager + MeshCoordinator
6. `CylinderStreamer` ← derived from `WorldConstants` (both near-field and LOD radii)
7. `NearChunkStreamer` ← ChunkLoader + CylinderStreamer
8. `LodProxyManager` ← `() -> new LodProxyGenerator(generatorFactory)`
9. `LodStreamer` ← LodProxyManager + CylinderStreamer (**same instance** as step 6)
10. `WorldDebugStatsProvider` ← ChunkManager + LodProxyManager + LodStreamer

**Critical coupling note:** `NearChunkStreamer` and `LodStreamer` share the same `CylinderStreamer` instance. This guarantees the LOD exclusion zone is always exactly derived from the near-field radius — mismatched radii are structurally impossible.

---

## 3. Data Layer

### `VoxelType` (enum)
`com.example.abyss.world.voxel.VoxelType`

All voxel material types with their physical and rendering properties.

| Property | Type | Meaning |
|---|---|---|
| `solid` | boolean | Blocks movement / occludes faces |
| `transparent` | boolean | Has alpha; goes to transparent mesh pass |
| `baseColor` | `Color` | Tint used by mesher |

**Types:** `AIR`, `WATER`, `STONE`, `DIRT`, `GRASS`, `WOOD`, `VINE`, `MUSHROOM`, `CORAL`, `CRYSTAL`, `OBSIDIAN`

### `VoxelData`
`com.example.abyss.world.voxel.VoxelData`

**Single responsibility:** Raw storage for one 32³ chunk — nothing else.

- `float[] densityField` — continuous terrain density, flat-indexed `y*1024 + z*32 + x`
- `VoxelType[] typeField` — discrete type per voxel, same index formula
- Bounds-checks on every access (returns `AIR`/`0f` for out-of-bounds)

### `VoxelChunk`
`com.example.abyss.world.VoxelChunk`

**Single responsibility:** Chunk-space coordinates + `VoxelData` + dirty flag + face-connectivity mask. No generation, meshing, or streaming logic.

Key fields:
- `cx, cy, cz` — chunk-space coordinates
- `VoxelData data`
- `boolean dirty` — set `true` when player edits a voxel
- `volatile short faceConnectivity` — 15-bit BFS mask; defaults to `FULLY_OPEN`

### `LoadedChunk` (record)
`com.example.abyss.world.LoadedChunk`

Pairs a `VoxelChunk` with its `ChunkMeshState`. This is the unit stored in `ChunkManager`.

```java
record LoadedChunk(VoxelChunk chunk, ChunkMeshState renderState)
```

---

## 4. Procedural Generation

### `Layer` (enum)
`com.example.abyss.world.gen.Layer`

Defines the **8 named layers** of the abyss (top to bottom):

| Layer | Y Range | Theme |
|---|---|---|
| `SURFACE` | `512 → 0` | Overworld surface |
| `THE_EDGE` | `0 → -500` | Rim of the abyss |
| `FOREST_DEPTHS` | `-500 → -1500` | Dense bioluminescent forest |
| `HANGING_GARDENS` | `-1500 → -2500` | Floating platform shelves |
| `SKY_WALLS` | `-2500 → -3500` | Open void with giant walls |
| `MYCELIUM` | `-3500 → -4500` | Dense fungal tunnels |
| `OCEANIA` | `-4500 → -5500` | Subterranean ocean |
| `DEEP_EXTREMES` | `-5500 → -7000` | Dangerous chaotic depths |

Each layer owns a `LayerGenConfig` (generation params) and `LayerRenderConfig` (fog + palette).

**Transition zones:** 192-voxel blend zones at every layer boundary. `Layer.getTransition(worldY)` returns a `TransitionInfo` with `blendFactor` (0.0 = shallow layer, 1.0 = deep layer). `GenConfigResolver.DEFAULT` handles blending automatically.

### `LayerGenConfig` (record)
Parameters used by `DensityFunction`:
- `baseDensityThreshold` — solid/air cutoff
- `noiseFrequency`, `noiseAmplitude`
- `shaftDiameter` — radius of the central open shaft
- `voxelTypes[]` — palette for `VoxelTypeResolver`

### `GenConfigResolver` (functional interface)
`(int worldY, Layer primaryLayer) → LayerGenConfig`

Default impl blends configs in transition zones. Debug tweaker injects overrides via this interface without touching production paths.

### `ProceduralGenerator`
`com.example.abyss.world.gen.ProceduralGenerator`

**Single responsibility:** Given `(chunkX, chunkY, chunkZ)` → return fully-populated `VoxelData`. Pure function (no side effects, fully seedable).

**Per-chunk pipeline:**
1. Populate `ColumnNoiseCache` — 32×32 XZ-column noise sweep (replaces per-voxel shaft/boundary math)
2. Populate `DensityLerpCache` — 9×9×9 coarse grid corners; inner loop reads via trilinear lerp (reduces noise evaluations from 32,768 → 729)
3. For each voxel: evaluate density via `DensityFunction.evaluateCached()`, resolve type via `VoxelTypeResolver.resolveCached()`
4. Apply per-layer `LayerFeatureProcessor` (post-processing pass, keyed by `chunkCenterLayer`)
5. Apply `LayerTransitionFeatureProcessor` list (each self-gates via `shouldProcess`)

**Thread safety:** Instances are stored in `ThreadLocal<ProceduralGenerator>` inside `ChunkManager` — never shared across threads.

### Feature Processors (`features/` package)
Each implements `LayerFeatureProcessor` and post-processes `VoxelData` in-place:

| Processor | Layer | Effect |
|---|---|---|
| `SurfaceFeatureProcessor` | SURFACE | Grass/dirt cap, rocky outcrops |
| `EdgeFeatureProcessor` | THE_EDGE | Abyss rim shaping |
| `ForestFeatureProcessor` | FOREST_DEPTHS | Tree/vine placement |
| `HangingGardensFeatureProcessor` | HANGING_GARDENS | Floating island detail |
| `SkyWallsFeatureProcessor` | SKY_WALLS | Wall texture variation |
| `MyceliumFeatureProcessor` | MYCELIUM | Fungal cluster placement |
| `OceaniaFeatureProcessor` | OCEANIA | Waterline + coral placement |
| `DeepExtremesFeatureProcessor` | DEEP_EXTREMES | Crystal/obsidian clusters |
| `AbyssRimProcessor` | *transition* | Cross-boundary rim geometry |

---

## 5. Chunk Lifecycle Management

### `ChunkManager`
`com.example.abyss.world.ChunkManager`

**Single responsibility:** Own the map of loaded chunks; dispatch async generation; manage mesh queue.

**Internal storage:** `ConcurrentHashMap<ChunkKey, LoadedChunk> loaded`

**Thread pools:**
- `KeyedTaskQueue<ChunkKey, VoxelChunk> taskQueue` — "ChunkGen" pool, `max(2, cpuCount)` threads
- `MeshManager meshManager` — contains its own "ChunkMesh" pool

**Key API (all main-thread unless noted):**

| Method | Thread | Description |
|---|---|---|
| `requestChunkAsync(cx,cy,cz,priority)` | main | Submit background generation (no-op if known) |
| `reprioritiseChunkAsync(...)` | main | Raise priority of pending task |
| `pollCompletedChunks()` | main | Drain finished chunks into `loaded` map |
| `getOrGenerateChunk(cx,cy,cz)` | any | Blocking sync generation (init/tests only) |
| `getChunk(key)` | any | Non-blocking, returns `null` if not loaded |
| `requestChunkMesh(key)` | main | Submit background meshing (checks neighbour policy) |
| `pollCompletedMeshes(shader)` | main (GL) | Upload GPU meshes within budget |
| `markChunkMeshDirty(key)` | main | Flag chunk for remesh |
| `unloadChunk(cx,cy,cz)` | main | Remove from map, cancel pending, dispose GL |
| `iterateLoadedChunks()` | main | Live view (no copy) for per-frame sweeps |

### `ChunkLoader`
`com.example.abyss.world.ChunkLoader`

**Single responsibility:** Low-level executor for load/unload. Bridges `ChunkManager` and `MeshCoordinator`. No camera knowledge, no sweep strategy.

**Key operations:**
- `requestChunkAsync(cx,cy,cz,priority)` → delegates to `ChunkManager`
- `pollCompletedChunks()` → drains `ChunkManager`, calls `meshCoordinator.markNeighborsForBoundaryRemesh()` for each new chunk, then always calls `meshCoordinator.queueDirtyMeshes()`
- `unloadChunks(Predicate<ChunkKey>)` → evicts loaded + cancels pending based on caller-supplied predicate
- `loadChunksAround(...)` → blocking, init/test only

### `ChunkKey` (record)
`record ChunkKey(int cx, int cy, int cz)` — value-typed chunk-space coordinate. Used as map key everywhere. Implements `equals`/`hashCode`.

---

## 6. Meshing Pipeline

### `MeshCoordinator`
`com.example.abyss.world.meshing.MeshCoordinator`

**Single responsibility:** Own the dirty/queue/poll mesh lifecycle. Knows which chunks need remeshing and when.

**Dirty invalidation rules:**
- On voxel edit: marks edited chunk + any face-adjacent chunk that shares that boundary face
- On chunk arrival: marks the new chunk + all 6 face-adjacent neighbours (boundary seam rebuild)

**Per-frame:** `queueDirtyMeshes()` scans all loaded chunks and submits dirty-but-not-queued ones to `ChunkManager.requestChunkMesh()`

**Render output:** `getRenderEntries()` → `List<BaseRenderEntry>` — world-space origin + opaque/transparent mesh lists. Renderer never touches `LoadedChunk` directly.

### `MeshManager`
`com.example.abyss.world.meshing.MeshManager`

**Single responsibility:** Run `ChunkMesher` on background threads; upload results to GPU with frame-budget caps.

- Upload budget: **8 ms/frame** (`glBufferData`)
- Disposal budget: **2 ms/frame** (`glDeleteBuffers`)
- Deferred promotion queue: chunks ready but over-budget are retried next frame
- Deferred dispose queue: old GL meshes deleted at capped rate

Uses `SameResolutionNeighbourPolicy` — a chunk is only meshed when all 6 face-adjacent neighbours are loaded (prevents seam cracks).

### `ChunkMesher` (pure static)
`com.example.abyss.world.meshing.ChunkMesher`

**Single responsibility:** `buildMesh(center, +X, -X, +Y, -Y, +Z, -Z) → MeshData`. Pure function, no state, background-safe.

- Emits one quad per exposed voxel face (face bordering air/transparent neighbour)
- Opaque and transparent faces go to separate section lists
- Recomputes `ChunkFaceConnectivity` only when `center.isDirty()` (player-edited)
- Returns `MeshData.EMPTY` when chunk has no geometry

### `MeshData` / `MeshSectionData`
CPU-side mesh data. `MeshData` holds `List<MeshSectionData>` for opaque and transparent passes. Uploaded to GPU by `MeshManager.pollCompleted()`.

---

## 7. Visibility Culling

### `ChunkFaceConnectivity`
`com.example.abyss.world.ChunkFaceConnectivity`

**Single responsibility:** Compute and query a 15-bit face-pair connectivity bitmask for one chunk.

- 6 faces → 15 unique unordered pairs → 1 bit each → packed into `short`
- Computed via BFS over non-solid voxels at generation time (and on player edit)
- `FULLY_OPEN = 0x7FFF` — default for unloaded chunks (treated as passable)
- `canSeeThrough(mask, entryFace, exitFace)` → used by `ChunkVisibilityGraph`

### `ChunkVisibilityGraph` (pure static)
`com.example.abyss.world.ChunkVisibilityGraph`

**Single responsibility:** BFS over loaded chunk graph using face-connectivity masks → returns reachable chunk set.

- Starts from player's chunk (always considered reachable)
- Propagates to neighbours only when the face-pair bit is set
- Unloaded chunks use `FULLY_OPEN` (never permanently block neighbours)
- Bounded by `hRadius` / `vRadius` in chunk-space

### `ChunkCuller`
`com.example.abyss.world.render.ChunkCuller`

**Single responsibility:** Frustum-AABB test for render entries.

- `isFrustumVisible(entry, camera)` — uses `CHUNK_SIZE` AABB, expands by `FRUSTUM_BIAS = 2.0f` to prevent precision flicker at high world-Y
- Overload accepts explicit `sizeX/Y/Z` for LOD proxy regions

---

## 8. Near-Field Streaming

### `CylinderStreamer`
`com.example.abyss.world.render.CylinderStreamer`

**Single responsibility:** Shared cylinder geometry and movement detection. Used by **both** `NearChunkStreamer` and `LodStreamer` (same instance).

- Near-field cylinder: `hRadius × vRadius` (in chunks)
- LOD band: `bandHRadius × bandVRadius` (in regions), with near-field exclusion zone hollowed out
- `isInLodBand(dx,dy,dz)` — true if inside outer cylinder AND outside exclusion cylinder
- `isOutsideKeepZone(dx,dy,dz, hPad, vPad)` — used for unload decisions
- `updateAndCheckMovement(pos, dt, threshold)` — speed-based movement detection
- `createNearestFirstComparator(unitSize, camPos)` — sorts candidates nearest-first for priority submission
- Static initializer validates that LOD band > exclusion zone at class-load time (fast-fails on bad `WorldConstants`)

### `NearChunkStreamer`
`com.example.abyss.world.render.NearChunkStreamer`

**Single responsibility:** Cylinder-based near-field loading/unloading policy around the observer. Drives `ChunkLoader`.

**Sweep triggers (in priority order):**
1. `INITIAL` — first call ever
2. `CHUNK_CHANGED` — player crossed a chunk boundary
3. `MOVING` — moving above threshold + 250 ms since last sweep
4. `IDLE_RESWEEP` — 100 ms idle timer (catches budget-capped incomplete sweeps)

**Per sweep:**
- Builds candidate list: `(hRadius+1) × (vRadius+1)` cylinder (extra shell so boundary chunks get all 6 neighbours for meshing)
- Sorts nearest-first via `CylinderStreamer.createNearestFirstComparator`
- Submits up to `MAX_SUBMISSIONS_PER_SWEEP = 512` new tasks via `ChunkLoader.requestChunkAsync(priority)`
- Re-prioritises already-known chunks

**Unload scan:** Every 2 seconds, delegates to `ChunkLoader.unloadChunks(predicate)` with `CylinderStreamer.isOutsideKeepZone`.

---

## 9. LOD (Far-Field Proxy) System

### `LodRegionKey` (record)
`record LodRegionKey(int regionX, int regionY, int regionZ, int lodLevel)` — value-typed region coordinate + LOD level (currently always 1).

### `LodProxyGenerator` (pure)
`com.example.abyss.world.render.lod.LodProxyGenerator`

**Single responsibility:** Given `LodRegionKey` → `MeshData`. Pure function, background-safe.

- Region = `REGION_CHUNKS × REGION_CHUNKS × REGION_CHUNKS` chunks = `128 × 128 × 128` voxels
- Generates all chunks in the region using `ProceduralGenerator` (same pipeline as near-field → consistent surface)
- Scans top-down per XZ column at `SAMPLE_STEP = 2` voxel intervals to find highest solid voxel
- Emits top-face quad + side-wall quads where adjacent columns differ in height
- `GRID_W = REGION_VOXELS_H / SAMPLE_STEP` columns per axis

### `LodProxyManager`
`com.example.abyss.world.render.lod.LodProxyManager`

**Single responsibility:** Own the map of loaded LOD regions and their async mesh generation queue.

**Implements `LodProxyPort`** — the interface `LodStreamer` talks to (decouples streaming policy from storage).

- `LinkedHashMap<LodRegionKey, LodProxyRegion> loadedRegions`
- `KeyedTaskQueue<LodRegionKey, MeshData> taskQueue` — "LodProxyGen" pool, `max(2, cpuCount)` threads
- `pollCompletedMeshes()` — drains completed mesh data into `LodProxyMeshState.setPendingMeshData()` (GL upload happens separately in the renderer)
- `getRenderEntries()` → `List<LodRenderEntry>` — world-space origin + lodLevel + mesh lists
- `getApproximateLoadedMeshMemoryBytes()` — for debug stats

### `LodStreamer`
`com.example.abyss.world.render.lod.LodStreamer`

**Single responsibility:** LOD band loading/unloading policy. Mirrors `NearChunkStreamer`'s timing discipline.

**Sweep triggers:** region change, movement + 500 ms, idle 100 ms timer.

**Per sweep:**
- Iterates `bandHRadius × bandVRadius` region grid
- Filters to band-only positions via `CylinderStreamer.isInLodBand`
- Sorts nearest-first, submits up to `MAX_SUBMISSIONS_PER_SWEEP = 128` via `LodProxyPort.requestProxyMeshAsync`

**Unload:** Region change OR 2 second timer — removes regions where `!isInLodBand`. Tracks `demotionCount` for debug stats.

**`LodProxyPort`** (interface): `requestProxyMeshAsync`, `reprioritiseProxyMeshAsync`, `isRegionKnown`, `pollCompletedMeshes`, `removeRegion`, `getLoadedRegionKeys`, `getRenderEntries`. Implemented by `LodProxyManager`.

---

## 10. Voxel World Access

### `WorldVoxelAccess`
`com.example.abyss.world.WorldVoxelAccess`

**Single responsibility:** World-to-chunk coordinate math + voxel read/write delegation. No chunk management logic.

- `toChunkCoord(world)` = `floorDiv(world, CHUNK_SIZE)`
- `toLocalCoord(world)` = `floorMod(world, CHUNK_SIZE)`
- `getVoxelAt(wx,wy,wz)` — blocking (generates chunk if needed)
- `setVoxelAt(wx,wy,wz,type)` — mutates chunk, fires `EditListener` → `MeshCoordinator.invalidateMeshForEdit`
- Non-blocking `getVoxelAtIfLoaded` — returns `null` chunk = not loaded, queues async generation

---

## 11. Render Boundary

### `WorldRenderSnapshot` (record)
```java
record WorldRenderSnapshot(
    List<BaseRenderEntry> nearFieldEntries,
    List<LodRenderEntry> farFieldEntries
)
```
The **only** object the renderer needs. Produced by `AbyssWorld.getRenderSnapshot()`. Renderer is completely decoupled from all world internals.

### `BaseRenderEntry`
Near-field chunk render data: `(worldX, worldY, worldZ, List<Mesh> opaqueMeshes, List<Mesh> transparentMeshes)`

### `LodRenderEntry`
LOD proxy render data: `(worldX, worldY, worldZ, int lodLevel, List<Mesh> opaqueMeshes, List<Mesh> transparentMeshes)`

### `RenderMeshSource` (interface)
Common interface for entries that can be frustum-culled: `worldX()`, `worldY()`, `worldZ()`.

---

## 12. Utility Infrastructure

### `KeyedTaskQueue<K, V>`
`com.example.abyss.util.KeyedTaskQueue`

Generic background work queue. Used by `ChunkManager` (gen + mesh) and `LodProxyManager`.

- Backed by `PriorityBlockingQueue` + `ThreadPoolExecutor`
- Deduplicates by key — duplicate `submit` for same key is a no-op
- `submit(key, supplier, priority)` — lower priority value = runs first
- `reprioritise(key, newPriority)` — cancels + resubmits if not yet started
- `pollCompleted()` → `List<Result<K,V>>` — drain on main thread
- `awaitPending(key)` — blocking (sync gen only)
- `forget(key)` — cancel pending, ignore result

### `BudgetedDrainQueue<T>`
`com.example.abyss.util.BudgetedDrainQueue`

Time-budgeted drain queue used by `MeshManager` to cap `glBufferData` and `glDeleteBuffers` time per frame.

---

## 13. Critical Data Flow Sequences

### A. Near-Field Chunk Load (per-frame happy path)
```
NearChunkStreamer.update(camera, dt)
  → sweepCylinderChunks()
      → ChunkLoader.requestChunkAsync(cx,cy,cz, priority)
          → ChunkManager.requestChunkAsync()
              → KeyedTaskQueue.submit(key, buildChunk(key))
                  [background thread] ProceduralGenerator.generateChunk()
                      → DensityFunction + VoxelTypeResolver + FeatureProcessors
                      → ChunkFaceConnectivity.compute()
                  → VoxelChunk returned

ChunkLoader.pollCompletedChunks()   [called by game loop]
  → ChunkManager.pollCompletedChunks()  → drains into loaded map
  → MeshCoordinator.markNeighborsForBoundaryRemesh(cx,cy,cz) × each new chunk
  → MeshCoordinator.queueDirtyMeshes()
      → ChunkManager.requestChunkMesh(key) for each dirty+not-queued
          → SameResolutionNeighbourPolicy.hasRequiredNeighbours()  [blocks until all 6 present]
          → KeyedTaskQueue.submit(key, ChunkMesher.buildMesh(...))
              [background thread] ChunkMesher.buildMesh(center, ±X, ±Y, ±Z) → MeshData

MeshCoordinator.pollCompletedMeshes(shader)
  → ChunkManager.pollCompletedMeshes(shader)
      → MeshManager.pollCompleted()
          → glBufferData (budget-capped 8ms)
          → glDeleteBuffers (budget-capped 2ms)
```

### B. Voxel Edit
```
WorldVoxelAccess.setVoxelAt(wx,wy,wz, type)
  → VoxelChunk.setVoxel(lx,ly,lz, type)  [sets chunk.dirty = true]
  → EditListener.onVoxelEdited(cx,cy,cz, lx,ly,lz)
      → MeshCoordinator.invalidateMeshForEdit(...)
          → ChunkManager.markChunkMeshDirty(chunkKey)
          → ChunkManager.markChunkMeshDirty(face-adjacent keys)  [if on boundary]
[next frame] MeshCoordinator.queueDirtyMeshes() → re-meshes affected chunks
[next frame] ChunkMesher.buildMesh() → calls ChunkFaceConnectivity.compute() because chunk.isDirty()
```

### C. LOD Region Load
```
LodStreamer.update(camera, dt)
  → sweepProxyBand()
      → cylinderStreamer.isInLodBand(dx,dy,dz) filter
      → LodProxyPort.requestProxyMeshAsync(key, priority)
          → LodProxyManager: put placeholder LodProxyRegion in loadedRegions
          → KeyedTaskQueue.submit(key, generatorLocal.get().generate(key))
              [background thread] LodProxyGenerator.generate(key)
                  → ProceduralGenerator.generateChunk() × (REGION_CHUNKS³)
                  → buildSurfaceMesh() → top-face quads + side-wall quads
                  → MeshData

LodStreamer.updateForPosition()
  → proxyPort.pollCompletedMeshes()
      → LodProxyManager: moves MeshData into LodProxyMeshState.pendingMeshData
[GL thread] renderer: reads pendingMeshData, uploads to GPU, clears pending
```

### D. Unload (periodic, every 2 seconds)
```
NearChunkStreamer: unloadTimer fires
  → ChunkLoader.unloadChunks(predicate)
      → for each loaded chunk: CylinderStreamer.isOutsideKeepZone()
      → ChunkManager.unloadChunk() → remove from map, MeshManager.disposeChunkMeshes()
      → ChunkManager.cancelPendingChunk() for out-of-range pending tasks

LodStreamer: unloadTimer fires
  → for each loadedRegions key: !isInLodBand → LodProxyPort.removeRegion(key)
      → KeyedTaskQueue.forget(key)
      → loadedRegions.remove(key)
```

---

## 14. Invariants AI Agents Must Never Violate

1. **`VoxelChunk` is pure data.** No generation, meshing, streaming, or rendering logic inside it.
2. **`ChunkMesher.buildMesh` is a pure function.** No state mutation, no side effects. Background-thread safe.
3. **`ProceduralGenerator.generateChunk` is deterministic and seedable.** Given same `(seed, cx, cy, cz)` it always produces identical output.
4. **One `CylinderStreamer` instance shared between `NearChunkStreamer` and `LodStreamer`.** Never create two separate instances — the LOD exclusion zone must always derive from the real near-field radius.
5. **Renderer only receives `WorldRenderSnapshot`.** The renderer layer must never hold references to `VoxelChunk`, `LoadedChunk`, `LodProxyRegion`, or any mutable world state.
6. **`ChunkManager.iterateLoadedChunks()` is a live view.** Never structurally modify the loaded map while iterating it.
7. **Mesh upload only on the GL thread.** `pollCompletedMeshes(shader)` must be called only from the main thread.
8. **No direct coupling between `NearChunkStreamer` / `LodStreamer` and each other.** They communicate only through the shared `CylinderStreamer` configuration.
9. **`WorldConstants` is the single source of truth for all radii and sizes.** Never duplicate or override these values inline.
10. **`ProceduralGenerator` instances are `ThreadLocal`.** Never share a single instance across threads.

---

## 15. Package Map

```
com.example.abyss
├── WorldConstants                   -- global tunables
├── Main.java                        -- game entry point
├── world/
│   ├── AbyssWorld                   -- top-level façade / wiring
│   ├── VoxelChunk                   -- pure data: coords + VoxelData + dirty + connectivity
│   ├── LoadedChunk (record)         -- VoxelChunk + ChunkMeshState
│   ├── ChunkKey (record)            -- value-typed chunk coordinate
│   ├── ChunkManager                 -- loaded map, async gen queue, mesh queue
│   ├── ChunkLoader                  -- load/unload executor (no policy logic)
│   ├── WorldVoxelAccess             -- world→chunk coord math + voxel r/w
│   ├── ChunkFaceConnectivity        -- 15-bit face-pair BFS bitmask
│   ├── ChunkVisibilityGraph         -- BFS reachability over chunk graph
│   ├── ChunkStore                   -- (persistence layer, future use)
│   ├── WorldDebugStats (record)     -- stats snapshot
│   ├── WorldDebugStatsProvider      -- assembles debug snapshot
│   ├── voxel/
│   │   ├── VoxelData                -- raw float[] + VoxelType[] storage
│   │   └── VoxelType (enum)         -- all material types
│   ├── gen/
│   │   ├── Layer (enum)             -- 8 named layers with Y ranges + configs
│   │   ├── LayerGenConfig           -- density/noise/shaft params per layer
│   │   ├── LayerRenderConfig        -- fog/colour params per layer
│   │   ├── GenConfigResolver        -- functional interface for config lookup + blending
│   │   ├── ProceduralGenerator      -- chunk gen pipeline (pure, seedable)
│   │   ├── DensityFunction          -- terrain + cave + shaft density evaluation
│   │   ├── DensityLerpCache         -- 9³ trilinear interpolation cache
│   │   ├── ColumnNoiseCache         -- 32×32 XZ column sweep cache
│   │   ├── VoxelTypeResolver        -- density → VoxelType assignment
│   │   ├── LayerFeatureProcessor    -- interface: post-process VoxelData per layer
│   │   ├── LayerTransitionFeatureProcessor -- interface: cross-boundary processors
│   │   └── features/                -- 9 concrete feature processors (one per layer/transition)
│   ├── meshing/
│   │   ├── ChunkMesher              -- pure static buildMesh() function
│   │   ├── MeshCoordinator          -- dirty/queue/poll lifecycle
│   │   ├── MeshManager              -- async meshing + budget-capped GL upload
│   │   ├── MeshData                 -- CPU-side mesh sections (opaque + transparent)
│   │   ├── MeshSectionData          -- single mesh section (vertices + indices)
│   │   ├── ChunkMeshState           -- per-chunk dirty/queued/mesh-list state
│   │   ├── MeshingNeighbourPolicy   -- interface: when are neighbours sufficient?
│   │   └── SameResolutionNeighbourPolicy -- requires all 6 face-adjacent chunks loaded
│   ├── render/
│   │   ├── WorldRenderSnapshot (record) -- near + far render entries for renderer
│   │   ├── WorldRenderSource        -- interface: getRenderSnapshot()
│   │   ├── BaseRenderEntry          -- near-field chunk render data
│   │   ├── LodRenderEntry           -- LOD proxy render data
│   │   ├── RenderMeshSource         -- interface for frustum-cullable entries
│   │   ├── CylinderStreamer         -- shared cylinder geometry + movement detection
│   │   ├── NearChunkStreamer        -- near-field loading/unloading policy
│   │   ├── ChunkCuller              -- frustum-AABB test
│   │   ├── ChunkStreamingStats      -- streaming stats snapshot
│   │   ├── ChunkStreamingStatsTracker
│   │   ├── ChunkStreamingSweepReason (enum)
│   │   ├── StreamingSubmissionBudget
│   │   └── lod/
│   │       ├── LodRegionKey (record)
│   │       ├── LodProxyGenerator    -- pure region mesh generation
│   │       ├── LodProxyManager      -- LOD region map + async gen queue
│   │       ├── LodProxyPort         -- interface: LodStreamer → LodProxyManager
│   │       ├── LodStreamer          -- LOD band loading/unloading policy
│   │       ├── LodProxyRegion       -- region data + world position + mesh state
│   │       ├── LodProxyMeshState    -- LOD mesh dirty/queued/data state
│   │       ├── ProxyMeshSectionBuilder
│   │       ├── ProxyQuadEmitter
│   │       └── LodDebugStats (record)
│   └── physics/                     -- (collision / physics, separate from render)
├── util/
│   ├── KeyedTaskQueue               -- generic keyed priority async queue
│   ├── BudgetedDrainQueue           -- time-budget-capped drain queue
│   └── MainThreadWatchdog           -- detects main-thread stalls
└── debug/                           -- debug apps (tweaker, chunk streaming viz, first-person viewer)
```

---

*Last updated: 2026-04-03. Regenerate after any structural refactor.*
