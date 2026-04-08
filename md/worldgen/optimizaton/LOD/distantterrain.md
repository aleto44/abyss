# Distant Terrain Heightmap System – Finalized Plan

This document defines the complete, finalized specification for generating and managing **banded heightmaps** used exclusively for distant terrain rendering (LOD, horizon, minimap, skybox impostors, etc.).  
It follows the requirements discussed: vertical banding in chunk-height units, per-column storage of max height / min height / density score, and horizontal macro-chunking to keep generation scalable.

## 1. Core Purpose
- Provide a lightweight, low-resolution representation of the Surface Layer terrain for very far distances.
- Preserve the dramatic mountain ring shape and radial falloff even when the player is looking at the horizon.
- Enable smooth visual continuity between near voxel chunks and distant simplified meshes.
- Avoid ever generating a single world-sized heightmap.

## 2. Heightmap Definition
Each heightmap entry (per horizontal column) stores exactly three values:
- **maxHeight**: Highest solid voxel Y in that column (world-space or chunk-relative)
- **minHeight**: Lowest solid voxel Y in that column (for overhangs, caves near surface, or future strata)
- **densityScore**: Normalized float [0.0 – 1.0] representing overall solidity / “filled-ness” of the column (used for shading, grass placement, or distant mesh density)

These three values are computed by sampling the same procedural generators used for near chunks (radial-biased FBM + ridged octaves + ringStrength).

## 3. Vertical Banding – “Chunk Units”
- Heightmaps are generated in **vertical bands** measured in whole chunk heights.
- A **1-chunk-unit heightmap** covers the full height range of exactly one chunk (e.g. 0–31 if chunks are 32 voxels tall).
- A **2-chunk-unit heightmap** aggregates (min, max, density) across two vertically stacked chunks.
- A **N-chunk-unit heightmap** aggregates across N stacked chunks.

**Example** (world with 10 vertical chunks):
- Using **2-chunk-unit** heightmaps → exactly **5 heightmaps** are generated per horizontal position:
    1. Chunks 0–1
    2. Chunks 2–3
    3. Chunks 4–5
    4. Chunks 6–7
    5. Chunks 8–9

- Using **1-chunk-unit** heightmaps → 10 heightmaps per position.
- The chosen unit size (1, 2, 4, etc.) is a global config value (`DistantHeightmapVerticalUnit`).

This banding drastically reduces the number of heightmap layers while still giving distant rendering enough vertical information.

## 4. Horizontal Macro-Chunking
- The world is divided into **massive macro-chunks** for distant heightmap generation.
- Suggested defaults (tunable):
    - Macro-chunk size: **32 × 32 chunks** (i.e. 512×512 voxels if chunks are 16×16)
    - Or even **64 × 64 chunks** for very distant LOD.
- Each macro-chunk stores its own set of vertical heightmap bands (the 5 bands in the 10-chunk example above).
- Only macro-chunks that are within the distant rendering radius are generated and kept in memory.
- Macro-chunks are generated **on-demand** when they first enter the distant view frustum (or when the player moves far enough to require new ones).

This creates a sparse, cache-friendly grid of heightmap data instead of anything world-sized.

## 5. Generation Strategy
- **Never** generate heightmaps for the entire world at once.
- **On-demand** rule:
    - When a macro-chunk becomes relevant for distant rendering, run the Surface Layer processor at **coarse sampling resolution**.
    - Sample noise only once per macro-column (or every 2–4 voxels and average).
    - Aggregate min/max/density across the chosen vertical chunk-unit band.
- All generation re-uses the exact same radial ring functions, FBM, and noise seeds as the near chunks → perfect visual consistency.
- Generated macro-chunk heightmap bands are stored in a simple in-memory cache (or serialized to disk if persistence is needed).

## 6. Usage for Distant Terrain Rendering
- Distant terrain is rendered as simplified meshes (displaced planes, heightmap quads, or impostor billboards) using the banded heightmaps.
- For any distant macro-chunk:
    - Select the appropriate vertical band(s) based on camera height.
    - Use maxHeight for the top surface, minHeight for possible overhang shading, densityScore for vertex color / grass density.
- Blend/fade between the nearest voxel chunks and the distant heightmap meshes in a transition zone (configurable distance).
- LOD levels can use progressively larger macro-chunks + larger vertical units as distance increases.

## 7. Configuration Knobs (WorldConfig)
- `DistantHeightmapVerticalUnit` (int): 1, 2, 4, … chunks per band
- `DistantMacroChunkSizeChunks` (int): horizontal macro-chunk width/height in chunks
- `DistantRenderRadius` (float): world units beyond which we switch to heightmap rendering
- `DistantSamplingStep` (int): noise sampling step inside macro-columns (e.g. 4–8 for speed)

## 8. Why This Design Works
- Memory-efficient: only a handful of macro-chunks + a few vertical bands each.
- Scalable to infinite worlds.
- Consistent with near terrain (same procedural source).
- Fast generation via coarse sampling + vertical aggregation.
- Easy to extend later (add more LOD rings, GPU upload, etc.).

This plan is now locked and ready for implementation.  
Any future changes (e.g. different vertical unit per LOD ring) can be added without breaking the core structure.