# Chunk Generation Pipeline

This document describes the full pipeline that takes a player position and turns it into rendered geometry — from the nearest full-detail voxel chunks all the way to the distant terrain horizon.

---

## Overview

The world is split into two rendering tiers that operate completely independently:

```
┌─────────────────────────────────────────────────────────────────┐
│                         PLAYER VIEW                             │
│                                                                  │
│   ┌──────────────────────────┐   ┌────────────────────────────┐ │
│   │   NEAR FIELD (Tier 1)    │   │   DISTANT TERRAIN (Tier 2) │ │
│   │                          │   │                            │ │
│   │  Full 32³ voxel chunks   │   │  Macro-chunk heightmaps    │ │
│   │  NearChunkStreamer        │   │  DistantTerrainStreamer     │ │
│   │  Cylinder shape          │   │  XZ grid of macro-chunks   │ │
│   │  R=8c H, R=5c V          │   │  R=32 macro-chunks XZ      │ │
│   └──────────────────────────┘   └────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

- **1 chunk = 32 × 32 × 32 voxels**
- **1 macro-chunk = 32 × 32 chunks** (1,024 × 1,024 voxels XZ footprint)

---

## Tier 1 — Near-Field Chunk Streaming

### Shape of the Loaded Region

The near field is a **cylinder** centred on the player in chunk-space:

```
       Top view (XZ plane)            Side view (XY plane)
                                                  
         . . . . . . .                   ┌───────┐  ← +5 chunks
       . . . . . . . . .                 │       │
     . . . . . . . . . . .               │       │
     . . . . . [P] . . . . .   ←8c→     │  [P]  │  ← player Y
     . . . . . . . . . . .               │       │
       . . . . . . . . .                 │       │
         . . . . . . .                   └───────┘  ← -5 chunks
                                          ↑     ↑
                                       -8c    +8c
```

- Horizontal radius: **8 chunks** (`NEAR_FIELD_H_RADIUS`)
- Vertical radius: **5 chunks** (`NEAR_FIELD_V_RADIUS`)
- An extra **+1 shell** is loaded beyond the visible radius so every visible boundary chunk has all six face-neighbours present for meshing.

### Sweep Triggers

`NearChunkStreamer` decides when to sweep for new chunks to load. A sweep is triggered by any of:

```
Every frame
    │
    ▼
 First ever frame? ──► INITIAL sweep  (loads everything from scratch)
    │
    ▼
 Player crossed a chunk boundary? ──► CHUNK_CHANGED sweep  (immediate)
    │
    ▼
 Player moving + ≥100ms since last sweep? ──► MOVING sweep
    │
    ▼
 No sweep this frame
```

Each sweep submits all candidates in the cylinder to the async queue, sorted nearest-first. Already-known chunks are skipped at no cost.

### Chunk Lifecycle

```
  requestChunkAsync()
        │
        ▼
  ┌─────────────────┐
  │  KeyedTaskQueue  │  ← background thread pool (up to 6 workers)
  └─────────────────┘
        │  ProceduralGenerator.generateChunk()
        ▼
  pollCompletedChunks()  ← main thread, once per frame
        │
        ▼
  ChunkManager.loaded map
        │
        ▼
  requestChunkMesh()  ── background MeshManager ──► ChunkMesher.buildMesh()
        │
        ▼
  pollCompletedMeshes()  ← main thread, uploads to GPU
        │
        ▼
  RenderedChunk  ← drawn this frame
```

**Unloading** runs every **2 seconds** on a timer. Any chunk outside the keep cylinder (same dimensions + 1 shell of hysteresis) is removed from memory.

---

## Tier 1 — Chunk Generation (ProceduralGenerator)

For each 32³ chunk the generator runs five steps:

```
chunkX, chunkY, chunkZ
        │
        ▼
 Step 1: Identify Layer(s)
         Layer.forY(worldY) → which biome/zone owns this Y range
        │
        ▼
 Step 2: Populate caches (done once per chunk, not per-voxel)
   ┌────────────────────────────────────────────────┐
   │  ColumnNoiseCache  (1,024 XZ entries)           │
   │    shaft distance, boundary noise, type jitter  │
   │                                                  │
   │  DensityLerpCache  (9×9×9 = 729 sample points)  │
   │    terrain + cave noise trilinearly interpolated │
   │    → cuts noise evaluations from 32,768 to 729  │
   └────────────────────────────────────────────────┘
        │
        ▼
 Step 3: Fill voxels  (32³ = 32,768 iterations)
         density ← DensityLerpCache (lerp, no GetNoise call)
         type    ← VoxelTypeResolver (reads ColumnNoiseCache)
        │
        ▼
 Step 4: Layer feature processor  (post-pass, layer-specific)
         e.g. ForestFeatureProcessor, OceaniaFeatureProcessor
        │
        ▼
 Step 5: Transition processors  (cross-boundary features)
         e.g. AbyssRimProcessor  (carves the central shaft rim)
        │
        ▼
 VoxelData  (32³ voxels, fully populated)
```

---

## World Layers

The world descends vertically through eight distinct layers. Each layer has its own density threshold, noise frequency, shaft diameter, voxel palette, fog distance, and colour tint.

```
  Y =  +512  ┌────────────────────────────────┐
             │  SURFACE                        │  visibility 320 u, bright teal/white
             │  Mountains, open sky            │  shaft Ø=600 vox, freq=0.0045
  Y =    0   ├────────────────────────────────┤
             │  THE EDGE                       │  visibility 256 u, green/brown
             │  Chunky overhanging formations  │  shaft Ø=1250 vox, freq=0.020
  Y =  -500  ├────────────────────────────────┤
             │  FOREST DEPTHS                  │  visibility 192 u, dark green
             │  Dense cave forest corridors    │  shaft Ø=180 vox, freq=0.009
  Y = -1500  ├────────────────────────────────┤
             │  HANGING GARDENS                │  visibility 220 u, bright green
             │  Floating stone/vine shelves    │  shaft Ø=250 vox, freq=0.010
  Y = -2500  ├────────────────────────────────┤
             │  SKY WALLS                      │  visibility 512 u, blue/grey
             │  Vast open void, giant walls    │  shaft Ø=400 vox, freq=0.008
  Y = -3500  ├────────────────────────────────┤
             │  MYCELIUM                       │  visibility 64 u, purple/green
             │  Claustrophobic fungal growth   │  shaft Ø=120 vox, freq=0.025
  Y = -4500  ├────────────────────────────────┤
             │  OCEANIA                        │  visibility 128 u, deep blue
             │  Underground ocean, coral       │  shaft Ø=350 vox, freq=0.010
  Y = -5500  ├────────────────────────────────┤
             │  DEEP EXTREMES                  │  visibility 48 u, near-black
             │  Obsidian/crystal chaos         │  shaft Ø=150 vox, freq=0.008
  Y = -7000  └────────────────────────────────┘
```

Layer boundaries have a **192-voxel blend/transition zone** on either side. Inside this zone, gen configs and render configs are linearly interpolated between the two neighbouring layers, preventing hard visual seams.

---

## Tier 1 — Mesh Baking (ChunkMesher)

Once a chunk and all six of its face-neighbours are loaded, a mesh is baked on a background thread:

```
 Center chunk (32³ voxels)
 + six face-neighbours (±X, ±Y, ±Z)
        │
        ▼
  For every voxel that is solid or transparent:
    For each of 6 face directions:
      neighbour = sample adjacent voxel (may cross chunk boundary)
      if face is exposed (neighbour is air/different type):
        emit quad  (4 vertices + 2 triangles)
        ├─ opaque faces  → opaque section list
        └─ transparent faces → transparent section list
        │
        ▼
  MeshData  (opaque sections + transparent sections)
  ↓ (uploaded on main thread)
  LibGDX Mesh objects  →  GPU
```

Separate opaque and transparent sections allow depth-sorted rendering of water, glass, etc.

---

## Tier 2 — Distant Terrain Streaming

Beyond the near field, full-resolution voxel chunks are too expensive. Instead, a **heightmap-based** distant terrain system covers a huge radius using coarse macro-chunks.

### Macro-chunk Grid

```
  Top view — each cell = 1 macro-chunk (32×32 voxel chunks = 1,024 voxels)

  ┌───┬───┬───┬───┬───┬───┬───┐
  │   │   │   │   │   │   │   │
  ├───┼───┼───┼───┼───┼───┼───┤
  │   │   │ · │ · │ · │   │   │  ← load radius = 32 macro-chunks
  ├───┼───┼───┼───┼───┼───┼───┤       (each dot ≈ 32 macro-chunks from [P])
  │   │ · │ · │[P]│ · │ · │   │
  ├───┼───┼───┼───┼───┼───┼───┤
  │   │   │ · │ · │ · │   │   │
  ├───┼───┼───┼───┼───┼───┼───┤
  │   │   │   │   │   │   │   │
  └───┴───┴───┴───┴───┴───┴───┘

  Eviction padding: +2 macro-chunks beyond load radius (hysteresis)
```

### Vertical Bands

Macro-chunks are grouped into **vertical bands** of 2 chunks height. Only bands within **12 chunks** of the player's Y are meshed; others are kept as data only.

```
  Y band N+3  │ data only
  Y band N+2  │ data only
  Y band N+1  │ meshed ◄── render distance ±12 chunks
  Y band N    │ meshed ◄── player band
  Y band N-1  │ meshed
  Y band N-2  │ data only
  Y band N-3  │ data only
```

### Distant Terrain Lifecycle

```
  DistantTerrainStreamer.update()  ← once per frame
        │
        ▼
  normalizeToMacroChunkKey(playerPos)
        │
        ▼
  Did player cross a macro-chunk XZ or Y-band boundary?
  ├── No  → do nothing this frame
  └── Yes ─────────────────────────────────┐
                                           ▼
                              computeRequestSet()  ← all keys within
                              LOAD_DISTANCE_MACRO_CHUNKS radius
                                           │
                              for each new key:
                              terrainPort.requestMacroChunkAsync(key)
                                           │
                                           ▼
                              evictStaleKeys()  ← any key beyond
                              load radius + EVICTION_PADDING
```

### Heightmap Sampling

Each macro-chunk column generates a coarse **heightmap** by sampling voxel density at every **4th voxel** (`DISTANT_SAMPLING_STEP = 4`) along the XZ grid. This reduces per-macro-chunk density evaluations by 16× compared to full resolution.

```
  Full resolution:   1024 × 1024 = 1,048,576 column samples
  Distant terrain:   256  ×  256 =    65,536 column samples  (4× step)
```

---

## Full Pipeline Summary

```
 Player position (world XYZ)
        │
        ├──────────────────────────────────────────────┐
        │                                              │
        ▼                                              ▼
 NearChunkStreamer                          DistantTerrainStreamer
 (cylinder R=8c H, R=5c V)                 (grid R=32 macro-chunks)
        │                                              │
        ▼                                              ▼
 ChunkLoader.requestChunkAsync()           requestMacroChunkAsync()
        │                                              │
        ▼                                              ▼
 [background workers]                      [dedicated distant workers]
 ProceduralGenerator.generateChunk()       DistantTerrainGenerator
   1. Layer lookup (Layer.forY)               heightmap sampling (step=4)
   2. ColumnNoiseCache fill                   DistantMeshBuilder
   3. DensityLerpCache fill (9³ grid)
   4. 32³ voxel fill loop
   5. Layer feature processor
   6. Transition processors
        │                                              │
        ▼                                              ▼
 VoxelChunk (32³ data)                     MacroChunkHeightmap
        │                                              │
        ▼                                              ▼
 ChunkMesher.buildMesh()                   DistantMeshBuilder
 (exposed-face culling, 6 neighbours)      (coarse heightmap mesh)
        │                                              │
        ▼                                              ▼
 MeshData (opaque + transparent)           DistantMeshState
        │                                              │
        ▼                                              ▼
 pollCompletedMeshes() → GPU upload        pollCompletedMeshes() → GPU upload
        │                                              │
        └──────────────────┬───────────────────────────┘
                           ▼
                    WorldRenderSnapshot
                    (ChunkCuller frustum cull)
                           │
                           ▼
                    Rendered frame
```

---

## Key Numbers at a Glance

| Parameter | Value |
|---|---|
| Chunk size | 32 × 32 × 32 voxels |
| Near-field H radius | 8 chunks = 256 voxels |
| Near-field V radius | 5 chunks = 160 voxels |
| Near-field load shell | radius + 1 (for neighbour meshing) |
| Unload scan interval | 2 seconds |
| Noise lerp grid | 9 × 9 × 9 = 729 samples per chunk |
| Layer transition zone | 192 voxels |
| Macro-chunk XZ size | 32 chunks = 1,024 voxels |
| Macro-chunk vertical band | 2 chunks |
| Distant load radius | 32 macro-chunks ≈ 32,768 voxels |
| Distant eviction padding | +2 macro-chunks |
| Distant sampling step | every 4th voxel |
| Near-field worker threads | up to 6 (½ CPU cores) |
| Distant worker threads | up to 6 (isolated pool) |
| World depth | +512 to −7,000 voxels (7,512 total) |
