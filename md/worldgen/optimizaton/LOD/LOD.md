# Abyss Level-of-Detail (LOD) Specification

## Purpose
This document defines the LOD direction for Abyss.

Its job is to make far-view rendering possible without turning distant visibility into full chunk loading.
The core requirement is simple:

- Near the player, the game may load full `VoxelChunk` data.
- Far from the player, the game must render a lightweight proxy representation.
- Distant rendering must not require allocating raw chunk voxel storage.

This document is intentionally specific to the current Abyss architecture.
It is not a general overview of how other engines solve LOD.

---

## Current Repository Baseline
Abyss already has a working near-field streaming pipeline.

### Current systems
- `ChunkManager` owns loaded chunks and async chunk generation.
- `ChunkLoader` acts as the low-level executor for load and unload requests.
- `MeshCoordinator` handles dirty-mesh queuing and completed mesh polling.
- The current streamer loads a chunk-space cylinder around the camera.
- `FirstPersonWorldDebugApp` renders loaded chunk meshes and uses frustum culling at render time.

### Important implication
The current system is already good enough for near-field chunk streaming.
The problem is what happens if view distance expands too far.

If Abyss simply increases the streaming radius, the engine will still:
- allocate full `VoxelChunk` data for every streamed chunk
- queue full generation work for those chunks
- queue full meshing work for those chunks
- keep loaded chunk render state for those chunks
- traverse many more loaded chunks every frame

That approach scales memory and background work with loaded volume.
It is not a safe path for large-distance visibility.

---

## Primary Design Rule
Abyss must not implement far-distance LOD by loading normal chunks farther away and only reducing mesh detail.

That approach would still keep the expensive part alive:
- raw voxel data
- full chunk lifecycle state
- full chunk generation pressure
- full chunk meshing pressure

For Abyss, far-distance LOD must be a separate render-oriented representation.

---

## Target Architecture
Abyss should use two distinct world representations.

### LOD 0: Near-field full chunks
This is the current gameplay representation.

Use full chunks for:
- collision
- editing
- gameplay simulation
- accurate caves and interior space
- normal chunk meshing
- authoritative voxel queries

Properties:
- backed by full `VoxelChunk` data
- loaded through the normal chunk pipeline
- eligible for remeshing after edits
- small enough to remain memory-safe

### LOD 1+: Far-field proxy regions
This is the future distant visual representation.

Use proxy regions for:
- horizon shapes
- major walls and terrain silhouette
- broad layer color identity
- visual continuity beyond the near-field chunk radius

Properties:
- render-only
- not authoritative for simulation
- not used for collision or edits
- generated directly from seed-driven world information or coarse sampling
- must not require loading full `VoxelChunk` data first

---

## First Implementation Target
The first LOD implementation should be a **surface-shell** system.

### Why surface shell first
A surface shell gives Abyss the biggest practical benefit with the lowest architectural risk.

It preserves:
- overall silhouette
- major depth-layer forms
- the feeling of large-scale continuity
- average material and color cues

It intentionally does not preserve:
- interior cave fidelity
- exact local overhang detail
- editability
- gameplay correctness at distance

This matches the real requirement.
At long range, the player mainly needs believable shape and depth, not full voxel truth.

---

## What a Surface-Shell Proxy Means in Abyss
A proxy region should represent distant terrain as a coarse outer shell instead of a full high-resolution voxel volume.

The first version should aim to capture:
- top-facing and outward-facing terrain surfaces
- major vertical walls and abyss contours
- broad layer transitions that affect color and shape
- the central descent silhouette if it is visible at distance

The first version should avoid:
- full volumetric cave reconstruction
- dense per-voxel storage
- neighbor-dependent full chunk remeshing rules
- any assumption that proxy data can replace near simulation

---

## Streaming Model
Abyss should keep near and far streaming separate.

### Near-field streaming
Near-field streaming should continue to use the existing chunk pipeline.
This remains the source of truth for gameplay.

### Far-field streaming
Far-field streaming should use a separate proxy pipeline.
That pipeline should:
- request proxy regions instead of full chunks
- build lightweight meshes from coarse world samples
- maintain a separate cache and memory budget
- avoid interfering with near-field chunk generation priority

### Required rule
A distant region must not be forced through the full chunk pipeline just because it is visible.
Visibility alone is not sufficient reason to allocate full chunk data.

---

## Spatial Strategy
The first LOD system does not need a full quadtree or octree.
A simpler region-based distance model is acceptable and preferred for v1.

### Recommended approach
Use distance bands or rings around the player.

Example shape:
- inner band: full chunks only
- middle band: coarse proxy regions
- outer band: larger and cheaper proxy regions
- beyond that: nothing

This keeps the first implementation understandable and aligns with the current streaming model.

### Region granularity
Far-field proxies should represent areas larger than a single gameplay chunk.
That is one of the main memory wins.

A proxy region may cover multiple chunks, for example:
- `2 x 2 x 2` chunks
- `4 x 4 x 2` chunks
- another asymmetric region shape that better matches Abyss traversal

The exact sizes can be tuned later.
The key requirement is that proxy regions are coarser than normal chunks.

---

## Vertical Detail Policy
Abyss is not a flat overworld game.
It is a descent-oriented voxel world with strong vertical structure.

The LOD system should reflect that.

### Rule
Keep more detail around and below the player than far above the player.

### Why
- the player is usually moving downward
- previously visited space above matters less over time
- important exploration space is often ahead and below
- vertical memory use grows quickly if detail remains symmetric in all directions

This asymmetry already exists in the current unload policy and should remain part of the future LOD design.

---

## Ownership Boundaries
LOD must preserve clear ownership between simulation and rendering.

### Near field owns
- collision
- edits
- voxel truth
- authoritative geometry
- mesh invalidation after edits
- systems that depend on exact chunk contents

### Far field owns
- visual continuity
- silhouette and broad shape
- coarse material or color impression
- cheap long-distance coverage

### Far field does not own
- collisions
- pathing guarantees
- edit persistence
- exact cave traversal
- authoritative world queries

---

## Generation Rules for Proxy LOD
Proxy LOD should be derived from deterministic world inputs.

### Acceptable inputs
- seed
- region coordinates
- LOD level
- layer configuration and depth information
- coarse density or height sampling
- lightweight summary data created specifically for proxy generation

### Not acceptable for distant generation
- allocating full raw chunk voxel arrays for the whole region
- loading full gameplay chunks just to build distant visuals
- requiring edit-time mesh invalidation semantics identical to near chunks

### Determinism requirement
For any fixed seed and proxy-region key, generated proxy output must be deterministic.

---

## Transition Rules
The first implementation should favor stability over sophistication.

### Required behavior
- promotion from proxy to full chunk detail should happen before the player reaches the region
- demotion should use hysteresis to avoid rapid toggling near thresholds
- near-field full chunks should visually override proxy coverage where both exist

### Not required in v1
- perfect geomorphing
- seamless vertex blending everywhere
- crack-free transitions under every camera edge case

Those are worthwhile later improvements, but they should not block the first usable LOD system.

---

## Scheduling and Memory Budgets
Abyss should treat LOD as a memory-budgeted streaming system, not just a radius-setting exercise.

### Required controls
The implementation should eventually expose budgets for:
- maximum loaded full chunks
- maximum loaded proxy regions
- maximum pending full chunk generation jobs
- maximum pending proxy jobs
- approximate mesh memory
- approximate voxel memory

### Important rule
Near-field gameplay work must keep higher priority than distant proxy work.

That means the future design should prefer separate scheduling paths or separate queues for:
- full chunk generation and meshing
- distant proxy generation and proxy mesh creation

---

## Observability Requirements
LOD should not be tuned blindly.
The implementation should include counters and debug visibility.

### Minimum useful telemetry
- loaded full chunk count
- loaded proxy region count
- pending full jobs
- pending proxy jobs
- pending full meshes
- approximate voxel memory
- approximate mesh memory
- promotion and demotion counts
- churn rate near thresholds

A working debug scene should remain available so LOD behavior can be measured while descending and turning.

---

## Non-Goals for Version 1
The first LOD release should not try to solve every distant rendering problem.

### Explicit non-goals
- full volumetric distant terrain correctness
- exact distant cave visibility
- edit-aware far-field reconstruction
- multiplayer-aware proxy ownership
- aggressive mesh morph systems
- complex hierarchical world trees unless simpler bands fail first
- persistence formats for proxy storage unless runtime generation proves too expensive

---

## Recommended Milestone Order
### Milestone 1: observability first
Add counters and debug information that make memory and queue pressure visible.

### Milestone 2: render-only proxy pipeline
Add a separate far-field proxy representation and render path.

### Milestone 3: surface-shell proxy generation
Generate the first coarse proxy meshes directly from deterministic world inputs.

### Milestone 4: distance-band streaming
Promote and demote between full chunks and proxy regions with hysteresis.

### Milestone 5: tuning
Tune region sizes, vertical asymmetry, quality, and scheduling budgets.

### Milestone 6: optional later improvements
Consider seam reduction, blending, persistence, and deeper hierarchy only after the simpler system works.

---

## Acceptance Criteria
The LOD system should be considered successful when all of the following are true:
- expanding camera visibility no longer requires proportional growth in full chunk count
- distant terrain can remain visible without loading raw chunk voxel data for the entire view volume
- near-field chunk behavior remains authoritative and correct
- the system remains deterministic for a given seed
- memory and queue pressure are observable during testing
- the first implementation is simple enough to reason about and extend

---

## Summary
Abyss should not solve far-distance rendering by stretching the current full-chunk streaming system.
That would risk exactly the memory pressure the project wants to avoid.

The correct direction is:
- keep the current chunk pipeline for near gameplay space
- add a separate far-field proxy pipeline
- start with a surface-shell representation
- keep proxy generation deterministic and lightweight
- treat memory budgets and observability as part of the design, not an afterthought

That is the LOD direction this project should follow.