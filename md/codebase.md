# Abyss — Codebase Directory

> **Purpose:** Quick-reference index for AI agents. Read this first; follow links for deeper detail.

---

## Documentation Map

| Document | What it covers |
|---|---|
| [`md/architecture/codebase_architecture.md`](architecture/codebase_architecture.md) | Full class-by-class reference: responsibilities, owned data, key APIs, data-flow sequences, and invariants |
| [`md/architecture/chunk_generation_pipeline.md`](architecture/chunk_generation_pipeline.md) | Deep-dive into the procedural generation pipeline |
| [`md/worldgen/optimization/world_gen_optimization.md`](worldgen/optimizaton/world_gen_optimization.md) | World-gen performance strategies (lerp caches, column cache) |
| [`md/worldgen/optimization/LOD/distantterrain.md`](worldgen/optimizaton/LOD/distantterrain.md) | Distant LOD terrain overview |
| [`md/worldgen/optimization/LOD/veloron.md`](worldgen/optimizaton/LOD/veloron.md) | Veloron shading/LOD technique |
| [`md/worldgen/blending/LayerBlending.md`](worldgen/blending/LayerBlending.md) | Layer transition blending algorithm |
| [`md/worldgen/lookandfeel/Vegetation_plan.md`](worldgen/lookandfeel/Vegetation_plan.md) | Vegetation and biome look-and-feel plan |
| [`md/main_idea.md`](main_idea.md) | High-level game concept and vision |
| [`md/roadmap.md`](roadmap.md) | Feature roadmap |
| [`md/workflow_documentation/debug tools.md`](workflow_documentation/debug%20tools.md) | Debug app usage and tooling |
| [`md/adversaries/adversaries_and_wildlife.md`](adversaries/adversaries_and_wildlife.md) | Adversary and wildlife design |
| [`md/ui/ui_and_inventory.md`](ui/ui_and_inventory.md) | UI and inventory system design |

---

## Package Structure (one-line summary per package)

```
com.example.abyss
├── WorldConstants              Global tunables — radii, chunk size. Never duplicate inline.
├── Main                        Game entry point.
├── world/
│   ├── AbyssWorld              Top-level façade; wires all subsystems. Zero business logic.
│   ├── VoxelChunk              Pure data: coords + VoxelData + dirty flag + face-connectivity.
│   ├── LoadedChunk (record)    Pairs VoxelChunk with ChunkMeshState; unit stored in ChunkManager.
│   ├── ChunkKey (record)       Value-typed chunk-space coordinate used as map key everywhere.
│   ├── ChunkManager            Loaded-chunk map, async generation queue, mesh queue.
│   ├── ChunkLoader             Load/unload executor; bridges ChunkManager + MeshCoordinator.
│   ├── WorldVoxelAccess        World→chunk coord math; voxel read/write + edit listener.
│   ├── ChunkFaceConnectivity   15-bit face-pair BFS bitmask for visibility culling.
│   ├── ChunkVisibilityGraph    BFS reachability sweep over the loaded chunk graph.
│   ├── ChunkStore              Persistence layer (future use).
│   ├── WorldDebugStatsProvider Assembles debug snapshot; no side effects.
│   ├── voxel/
│   │   ├── VoxelData           Raw float[] density + VoxelType[] type storage for one chunk.
│   │   └── VoxelType (enum)    All material types with solid/transparent/color properties.
│   ├── gen/
│   │   ├── Layer (enum)        8 named abyss layers with Y ranges, gen configs, render configs.
│   │   ├── ProceduralGenerator Pure, seedable chunk generation pipeline.
│   │   ├── GenConfigResolver   Functional interface: worldY → LayerGenConfig (handles blending).
│   │   ├── DensityFunction     Terrain + cave + shaft density evaluation.
│   │   ├── DensityLerpCache    9³ trilinear-interpolation cache (reduces noise calls from 32k→729).
│   │   ├── ColumnNoiseCache    32×32 XZ column sweep cache (replaces per-voxel shaft math).
│   │   ├── VoxelTypeResolver   density → VoxelType assignment.
│   │   └── features/           9 LayerFeatureProcessors — one per layer/transition boundary.
│   ├── meshing/
│   │   ├── ChunkMesher         Pure static buildMesh() — (center + 6 neighbours) → MeshData.
│   │   ├── MeshCoordinator     Dirty/queue/poll lifecycle; owns boundary-remesh invalidation.
│   │   ├── MeshManager         Async meshing threads + budget-capped GL upload (8 ms/frame).
│   │   ├── MeshData            CPU-side mesh sections (opaque + transparent).
│   │   └── ChunkMeshState      Per-chunk dirty/queued/mesh-list state machine.
│   ├── render/
│   │   ├── WorldRenderSnapshot (record)  The only object the renderer receives.
│   │   ├── CylinderStreamer     Shared cylinder geometry + movement detection (used by both streamers).
│   │   ├── NearChunkStreamer    Near-field load/unload policy; drives ChunkLoader.
│   │   ├── ChunkCuller         Frustum-AABB culling for render entries.
│   │   └── lod/
│   │       ├── LodProxyGenerator  Pure region mesh generation (ProceduralGenerator × region).
│   │       ├── LodProxyManager    LOD region map + async gen queue; implements LodProxyPort.
│   │       ├── LodStreamer        LOD band load/unload policy; mirrors NearChunkStreamer timing.
│   │       └── LodProxyPort       Interface decoupling LodStreamer from LodProxyManager.
│   └── physics/                Collapse simulation (separate from render pipeline).
├── util/
│   ├── KeyedTaskQueue          Generic keyed priority async queue used by ChunkManager + LodProxyManager.
│   ├── BudgetedDrainQueue      Time-budget-capped drain queue used by MeshManager.
│   └── MainThreadWatchdog      Detects main-thread stalls.
└── debug/                      Debug apps: tweaker panel, chunk streaming visualiser, first-person viewer.
```

---

## Key Invariants (must not be violated)

1. `VoxelChunk` is pure data — no generation, meshing, streaming, or rendering logic inside it.
2. `ChunkMesher.buildMesh` is a pure function — no state, no side effects, background-thread safe.
3. `ProceduralGenerator.generateChunk` is deterministic and seedable — same `(seed, cx, cy, cz)` always yields identical output.
4. One `CylinderStreamer` instance shared between `NearChunkStreamer` and `LodStreamer` — never create two.
5. Renderer only receives `WorldRenderSnapshot` — never hold references to `VoxelChunk`, `LoadedChunk`, or mutable world state.
6. `WorldConstants` is the single source of truth for all radii and sizes — never duplicate or hardcode inline.
7. `ProceduralGenerator` instances are `ThreadLocal` — never share across threads.
8. Mesh upload (`pollCompletedMeshes`) only on the GL/main thread.

---

## Layer Quick-Reference

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

Transition zones are 192 voxels wide at each boundary. `GenConfigResolver.DEFAULT` blends configs automatically.

---

*Keep this file in sync with `md/architecture/codebase_architecture.md` after any structural refactor.*
