# Voxel Game: Chunk Loading, LOD Transitions & Distant Terrain

**Last Updated:** April 2026  
**Summary of Discussion:** How to fix visible gaps when swapping near chunks and LOD chunks, and how Veloren solves the same problem efficiently.

---

## The Problem

You are using:
- **Near Chunks** – high-detail/full-resolution voxel chunks
- **Tiered LOD Chunks** – lower-detail versions for distant areas

As the player moves, unloading LOD chunks and loading near chunks (or vice versa) creates **giant visible gaps, holes, and popping artifacts**.

### Root Causes
- Hard distance-based swapping between two incompatible chunk representations
- No overlap or smooth handover between LOD and full-detail chunks
- Possible mismatches in geometry generation (noise, sampling, meshing rules)
- Instant switch with no blending, morphing, or buffer zone

---

## How Veloren Handles Distant Terrain

Veloren does **not** use simple per-chunk LOD swapping. Instead, it runs **two separate, coexisting rendering systems** that are designed to match perfectly.

### 1. Near / Full-Detail System
- Standard 32×32×16 voxel chunks
- Loaded/unloaded around the player using normal view distance
- Full greedy meshing and normal voxel rendering pipeline

### 2. Distant LOD Terrain (Separate Render Pass)
- **Not** traditional LOD chunks that get replaced
- A single continuous distant terrain renderer (`lod.rs` + custom shaders)
- Uses **low-resolution world data** sent from the server once:
    - Altitude / height map
    - Color map
    - Precomputed horizon maps (for realistic shadows)
- This data is generated **once** during world creation and is 100% deterministic (matches full voxel terrain exactly)
- Rendered for everything **beyond** normal view distance
- Includes a **voxelization effect** in the fragment shader so distant terrain visually blends with near chunks
- Horizon maps allow shadows to be cast between LOD and near areas

### Why Veloren’s Approach Eliminates Gaps & Popping
- There is **no unload/replace cycle** for distant areas
- As you approach, full-detail chunks simply load on top — the LOD system continues rendering everything still far away
- Both systems derive from the **exact same source data** → perfect continuity
- Shader effects + matching shadows create a seamless visual boundary

---

## Does Veloren Unload Full-Detail Chunks?

**Yes.**

- Full-detail voxel chunks **are unloaded** when they move outside the player’s configured view distance (standard client-server chunk management).
- The distant LOD renderer stays active and immediately shows the simplified version of the same terrain.
- When you walk toward an area, the full-detail chunk loads in and overrides the LOD in that zone — no gap appears because the LOD was already rendering it.

This separation is the key to avoiding the clunky gaps you are seeing.

---

## Why Distant LOD Is Neither Too Heavy Nor Looks Like Garbage

Veloren’s distant LOD is extremely efficient because it is deliberately simplified and uses clever tricks instead of raw geometry.

| Aspect              | Full Voxel Chunks                  | Distant LOD Terrain                          | Why It’s Cheap |
|---------------------|------------------------------------|----------------------------------------------|----------------|
| **Data per unit**   | Full 3D voxel grid (~32³ voxels)  | 2D heightmap + color map + tiny horizon data | ~100–1000× less data |
| **Memory**          | High (only near player)            | Very low (covers the entire world)           | Compact maps |
| **Geometry**        | Greedy-meshed 3D mesh              | Simple grid/heightmap rendered as one big surface | Few triangles |
| **Rendering**       | Full voxel pipeline                | Single shader pass with voxelization effect  | Very GPU-friendly |

### Key Efficiency Techniques
1. **Extremely compact data** – Only low-resolution 2D maps are stored and sent once.
2. **One big render pass** – Not thousands of individual chunks; just one or a few large surfaces.
3. **Shader tricks** – Voxelization effect and horizon maps fake most of the detail cheaply.
4. **Configurable quality** – Players can lower LOD distance and detail in graphics settings.

### Why It Still Looks Good
- Uses the **exact same source data** as full voxels → consistent shape and color
- Voxelization shader + matching shadows make distant mountains feel like they belong in the same world
- From far away you mostly see silhouettes and major features anyway, so the simplification feels natural

---

## Recommended Solutions for Your Game

### Best Long-Term Approach (Veloren Style)
1. Keep your existing near-chunk system unchanged.
2. Add a **separate distant LOD renderer** that draws the entire far world from low-resolution data (heightmap + color).
3. Never unload/replace distant LOD — let near chunks simply take over rendering in the near zone.

### Alternative: Improve Per-Chunk Tiered LOD (if you prefer to keep it)
- Use **overlapping radii + hysteresis** (load full-detail *before* unloading LOD)
- Ensure **perfectly deterministic generation** (LOD must be an exact downsample of full data)
- Implement **seam stitching** (e.g. Transvoxel algorithm)
- Add smooth transitions:
    - Vertex geomorphing
    - Mesh cross-fading
    - Shader blending
- Maintain a preload buffer of LOD chunks outside the full-detail radius

### Quick Fixes You Can Implement Immediately
- Add a short delay/buffer before unloading old LOD chunks
- Render **both** representations in the transition zone and blend them
- Force identical generation code for LOD and full chunks
- Visually debug boundary mismatches

---

## Key Takeaway

> Stop treating LOD as temporary chunks that get swapped out.  
> Treat distant LOD as a **permanent continuous background render** built from the same world data as your near chunks.

This single architectural change is what makes Veloren’s distant terrain feel seamless even at high view distances, while staying light on memory and GPU.

---

**End of Document**  
Copy everything above this line into a file named `voxel-lod-veloren-solutions.md` for easy export and future reference.