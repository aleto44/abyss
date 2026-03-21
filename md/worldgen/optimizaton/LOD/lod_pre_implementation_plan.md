# Abyss LOD Pre-Implementation Plan

## Purpose
This document captures the cleanup and organisation work that should happen before the main LOD implementation begins.

It complements `md/worldgen/tracking/lod_implementation_plan.md`.
That file describes the phased LOD build-out.
This file focuses on the codebase preparation needed so LOD can be added without fighting the current architecture.

The goal is not a large rewrite.
The goal is to make a few high-value structural changes that reduce risk and make the first LOD milestones easier to implement and test.

---

## Why do this first?
The current near-field pipeline is already workable:
- `AbyssWorld` is a thin facade
- `ChunkManager` owns chunk residency and async generation
- `ChunkLoader` acts mainly as an executor
- `MeshCoordinator` owns mesh invalidation and queueing decisions
- `CylinderChunkStreamer` provides the current near-field loading policy
- `FirstPersonWorldRenderer` renders promoted chunk meshes

That is a good base.
The main issue is that the current pipeline still assumes that a loaded chunk is also the render unit.
LOD will want those concerns to diverge.

If that assumption stays in place, the first LOD work will become a refactor inside the implementation instead of a clean extension of the current system.

---

## Pre-Implementation Contract

### Inputs
- current chunk residency pipeline
- current mesh queue and promotion pipeline
- current first-person debug renderer
- current cylinder-based streaming policy
- current meshing neighbour assumptions

### Outputs
- clearer ownership boundaries between world data and render data
- a render-facing abstraction that does not depend on iterating raw loaded chunks directly
- a streaming policy structure that can later choose between near chunks and far proxies
- tests that lock down chunk lifecycle and remesh behaviour before LOD adds new states

### Success criteria
- LOD work can add new far-field representations without changing the meaning of `VoxelChunk`
- the renderer no longer assumes every renderable thing is a loaded full chunk
- streaming decisions can evolve without rewriting one large policy class
- current chunk streaming still works after the cleanup

---

## Main Findings From Current Code

### 1. Chunk data and render state are still coupled
`LoadedChunk` currently combines:
- `VoxelChunk`
- `ChunkMeshState`

That works for the current single-representation system.
It becomes restrictive once one world area may need:
- a full chunk mesh
- a lower-detail proxy mesh
- region-level render data
- temporary overlap between promoted and demoted representations

### 2. Rendering is coupled to chunk storage layout
`FirstPersonWorldRenderer` currently iterates `world.getChunkManager().iterateLoadedChunks()` directly in both opaque and transparent passes.

That means the renderer knows too much about how world residency is stored.
For LOD, rendering should depend on render entries rather than raw loaded chunk records.

### 3. Streaming policy is concentrated in one class
`CylinderChunkStreamer` currently owns:
- observer movement tracking
- sweep trigger logic
- candidate generation
- candidate sorting
- submission budgeting
- unload timing
- unload policy
- stats aggregation

That is manageable now, but it will become harder to extend when the streamer needs multiple representation bands.

### 4. Meshing assumes same-resolution neighbours
`ChunkMesher.hasRequiredNeighbors(...)` currently assumes six face-adjacent neighbours at the same chunk resolution.

That assumption is valid for the current full-chunk pipeline.
It is not a good long-term boundary once mixed-detail representations are introduced.

### 5. Test coverage is good in a few spots but thin around lifecycle edges
There are already tests for:
- `CylinderChunkStreamer`
- `ChunkMesher`

The current gap is not basic functionality.
The gap is lifecycle confidence around:
- load and unload races
- remesh invalidation behaviour
- neighbour-driven retries
- render representation ownership

---

## Recommended Cleanup Priorities

## Priority 1: separate world residency from render ownership
### Objective
Stop treating a loaded chunk as the only renderable unit.

### Current pressure points
- `src/main/java/com/example/abyss/world/LoadedChunk.java`
- `src/main/java/com/example/abyss/world/ChunkManager.java`
- `src/main/java/com/example/abyss/world/meshing/ChunkMeshState.java`
- `src/main/java/com/example/abyss/world/meshing/MeshCoordinator.java`

### Recommended direction
Shift toward a model where:
- chunk residency owns loaded `VoxelChunk` data
- a render-side system owns mesh state
- render ownership is keyed separately from authoritative voxel storage

### Near-term design rule
Do not force future LOD proxies to pretend they are `VoxelChunk` instances.

### Acceptance criteria
- `VoxelChunk` remains the authoritative gameplay data type
- render state can be managed without embedding it permanently inside the loaded chunk record
- future support for one-to-many render representations becomes possible

---

## Priority 2: add a render-facing abstraction
### Objective
Make rendering depend on render entries rather than `ChunkManager` storage internals.

### Current pressure points
- `src/main/java/com/example/abyss/debug/viewer/FirstPersonWorldRenderer.java`
- `src/main/java/com/example/abyss/world/render/ChunkCuller.java`
- `src/main/java/com/example/abyss/world/meshing/MeshCoordinator.java`

### Recommended direction
Introduce a small render-facing boundary such as:
- render entry
- render chunk view
- render snapshot
- or another similarly narrow API

The exact naming is flexible.
The important part is that the renderer should not care whether a renderable object came from:
- a full chunk mesh
- a future proxy mesh
- a merged region mesh

### Acceptance criteria
- the renderer no longer directly iterates loaded chunk storage
- opaque and transparent render passes are formed from render-facing data
- frustum culling can later operate on both chunk and proxy bounds

---

## Priority 3: split `CylinderChunkStreamer` into smaller policy units
### Objective
Make streaming policy testable and extensible before LOD adds more decisions.

### Current pressure points
- `src/main/java/com/example/abyss/world/render/CylinderChunkStreamer.java`
- `src/main/java/com/example/abyss/world/ChunkLoader.java`

### Recommended direction
Separate at least these concerns:
- observer-state tracking
- sweep trigger logic
- candidate generation
- candidate prioritisation
- submission budgeting
- unload decision logic

This does not need a large class explosion.
Even small internal helpers or package-private collaborators would help.

### Acceptance criteria
- chunk streaming behaviour is preserved
- candidate selection logic can be tested independently from unload timing
- future LOD bands can reuse sweep and budgeting logic without copying the whole class

---

## Priority 4: loosen hardcoded meshing readiness assumptions
### Objective
Prepare the meshing pipeline for future mixed-detail boundaries.

### Current pressure points
- `src/main/java/com/example/abyss/world/meshing/ChunkMesher.java`
- `src/main/java/com/example/abyss/world/meshing/MeshCoordinator.java`

### Recommended direction
Replace the hardcoded six-neighbour readiness assumption with a more explicit readiness or dependency concept.

Examples:
- a mesh context object
- a neighbour availability policy
- a render seam strategy placeholder

This does not mean solving LOD seam stitching now.
It means moving the current assumption behind a clearer abstraction.

### Acceptance criteria
- same-resolution full-chunk meshing still behaves exactly as before
- neighbour requirements live behind a named boundary
- later seam or mixed-detail rules can be added without reworking `ChunkMesher` from scratch

---

## Priority 5: strengthen lifecycle and remesh tests
### Objective
Lock down the current behaviour before LOD introduces more chunk states and queue paths.

### Current pressure points
- `src/test/java/com/example/abyss/world/render/CylinderChunkStreamerTest.java`
- `src/test/java/com/example/abyss/world/meshing/ChunkMesherTest.java`
- likely new tests around `ChunkManager` and `MeshCoordinator`

### Recommended additions
Add focused tests for:
1. chunk unload cancelling pending work
2. boundary remesh invalidation after neighbour load
3. edit invalidation at chunk borders
4. mesh retry behaviour when neighbours arrive later
5. render-state disposal on unload

### Acceptance criteria
- key lifecycle invariants are covered before LOD adds proxies and promotion states
- refactors can be made with confidence
- current chunk streaming regressions are easier to catch early

---

## Suggested Order of Work

## Step 1: document ownership boundaries
Create a short internal design note or update this file with the final ownership decision before code changes begin.

Questions to answer:
- who owns authoritative chunk residency?
- who owns render resource lifetime?
- who exposes renderables to the renderer?
- what is the intended boundary between full chunks and future LOD proxies?

## Step 2: introduce render-facing data flow
Refactor the renderer path first so render code depends on a render-facing API rather than direct loaded-chunk iteration.

## Step 3: separate render state from loaded chunk storage
Once the render-facing API exists, move mesh-state ownership behind that boundary.

## Step 4: split the streaming policy class
Extract the current streamer into smaller decision units while preserving current behaviour and stats.

## Step 5: refactor meshing readiness boundaries
Hide the current six-neighbour assumption behind a named abstraction.

## Step 6: expand test coverage around lifecycle edges
Add tests after each cleanup step so the pre-LOD base is stable before main implementation begins.

---

## Concrete Questions To Resolve Before Coding LOD
These questions do not all need full answers immediately, but the team should choose a direction.

### Representation model
- Should one `ChunkKey` be able to map to multiple render representations?
- Should far-field data use chunk coordinates, larger region coordinates, or both?
- Should proxies live in a separate manager from full chunks?

### Rendering model
- Will the renderer consume a flat list of render entries or a higher-level render snapshot?
- Will culling remain chunk-bounds-based for near-field and region-bounds-based for far-field?
- Will transparent proxy content be supported in the first pass or deferred?

### Scheduling model
- Will near-field and far-field jobs share a queue?
- If they share a queue temporarily, how will near-field jobs keep priority?
- What counters are needed to observe starvation or queue flooding?

### Meshing model
- Will initial LOD use shell proxies only?
- Are mixed-resolution seams deferred in the first milestone?
- What should happen visually when a near chunk overrides a proxy region?

---

## Definition of Ready for LOD Phase 1
The main LOD implementation should start only after the following are true:

- a render-facing abstraction exists
- render ownership is no longer permanently fused to `LoadedChunk`
- current near-field streaming still passes tests
- lifecycle and remesh invariants have explicit tests
- the team has chosen an initial proxy ownership model

If all five are true, the first real LOD milestone can start with much lower risk.

---

## Recommended Scope Discipline
To keep this cleanup phase bounded:
- do not rewrite world generation
- do not redesign gameplay chunk APIs unless required for ownership clarity
- do not attempt final seam solving yet
- do not build the full proxy system during the cleanup phase

This phase succeeds if it creates better boundaries, not if it delivers distant rendering by itself.

---

## Summary
The repository is close to being ready for LOD work.
It does not need a large reset.
It needs a focused preparation pass around the three areas that will otherwise cause friction:
- render ownership
- streaming policy boundaries
- lifecycle test coverage

That preparation should be small enough to complete before the first real LOD milestone and strong enough to keep the implementation plan straightforward.
