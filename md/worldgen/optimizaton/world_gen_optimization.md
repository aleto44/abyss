**# Abyss Game World Optimization Guide**

This document compiles proven optimization techniques inspired by Cube World’s smooth performance. Use it as a reference when performance becomes an issue. Do not apply heavy optimizations until the game is fully playable.

### Core Optimization Techniques

**Chunk-Based Streaming & Priority Generation**  
Divide the world into fixed chunks. Generate and load only chunks near the player using a priority queue or spiral pattern. Distant chunks remain unloaded.

**Multithreaded Generation & Meshing**  
Run all terrain generation and meshing on background threads. The main thread only receives completed meshes and swaps them in.

**Fast, Cached Noise Functions**  
Use an optimized noise algorithm (Simplex or custom). Cache density values per chunk to avoid repeated calculations.

**Optimized Marching Cubes / Meshing**  
Precompute lookup tables for cube configurations. Share vertices across triangles. Skip interior solid voxels entirely. Use greedy meshing as a fast alternative during early development.

**Level-of-Detail (LOD) System**  
Generate lower-resolution meshes for distant chunks. Swap or blend meshes based on distance from the player.

**Sparse / Compressed Chunk Storage**  
Store only surface or changed voxels. Use memory pools and arenas instead of frequent allocations.

**Culling & Rendering Tricks**  
Apply frustum culling on every chunk. Add simple occlusion culling. Bake ambient occlusion into vertices during meshing. Use a single draw call per chunk.

**Deterministic Seeding & Region Pre-Planning**  
Plan large-scale features (biomes, shafts, transitions) at a coarse grid first, then fill details. This allows instant regeneration of any chunk without storing raw data.

### Implementation Roadmap

Add techniques in this exact order — only when needed:

1. **Phase 1 (after basic meshing works)**
    - Multithreaded generation & meshing
    - Cached noise
    - Chunk streaming & priority queue

2. **Phase 1 (if switching to marching cubes)**
    - Optimized marching cubes lookup tables

3. **Phase 4 (when persistence and corpses are added)**
    - Sparse/compressed chunk storage

4. **Phase 8 (when FPS drops below 60)**
    - LOD system
    - Occlusion culling
    - Baked ambient occlusion

5. **Phase 9 (multiplayer)**
    - Memory arenas & pooling

### Immediate Minimal Steps (Do These Now in Phase 1)

- Wrap chunk generation and meshing in at least one background thread.
- Cache the density field for each chunk instead of recalculating.
- Use LibGDX object pools for temporary vectors, meshes, and arrays in the mesher.

### General Rule for This Project

- Until you can play for 10–15 minutes without stuttering or frame drops, do not add any further optimizations.
- The moment lag appears while descending multiple layers or near many creatures, open this guide and apply the next relevant technique.
- Always keep a working test scene so you can measure performance before and after each change.

Refer back to this document whenever you feel the world is slowing down. The techniques above are exactly what allowed Cube World to feel infinite and smooth on modest hardware.
