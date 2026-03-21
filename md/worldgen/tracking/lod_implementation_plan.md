# Abyss LOD Implementation Plan

## Purpose
This document is the implementation planning companion to `md/worldgen/optimizaton/LOD.md`.

`LOD.md` defines the architectural direction.
This file breaks that direction into execution phases, ownership boundaries, likely class responsibilities, and deferred follow-up work.

This document is still planning-only.
It does not require the implementation to follow every class name exactly, but it should preserve the same boundaries and intent.

---

## Design Contract
The LOD work should preserve the following contract.

### Inputs
- world seed
- player or camera position
- current near-field streaming radii
- future proxy-region coordinates
- LOD level
- layer configuration and deterministic worldgen inputs

### Outputs
- near-field full chunks for gameplay-critical space
- far-field render-only proxy meshes for distant space
- clear promotion and demotion behavior between those systems
- debug counters that make memory pressure and queue pressure visible

### Error and failure expectations
- proxy generation may be late without breaking gameplay
- near-field chunk generation must remain the higher priority path
- missing far proxy coverage should degrade visually, not corrupt simulation
- the system must avoid runaway pending work when view distance increases

### Success criteria
- full chunk count remains bounded even when far visibility expands
- distant visibility does not require loading raw chunk voxel arrays
- near-field collision, edits, and voxel truth remain authoritative
- proxy generation is deterministic for a fixed seed and region key

---

## Current Repository Reality
The repository already has a usable near-field pipeline.
That matters because the LOD work should extend it, not replace it.

### Existing baseline
- `ChunkManager` owns loaded full chunks and async generation.
- `ChunkLoader` is the low-level executor for chunk requests and eviction.
- `MeshCoordinator` manages dirty and completed chunk meshes.
- The current streamer loads a cylinder around the observer.
- `FirstPersonWorldDebugApp` renders loaded chunk meshes and performs render-time frustum culling.

### Main architectural risk
The current path treats visibility expansion as more full chunks.
That means increasing view distance can increase:
- loaded `VoxelChunk` count
- voxel memory
- pending chunk generation work
- pending meshing work
- loaded render-state count
- per-frame iteration cost

The LOD implementation must break that link.

---

## Recommended Naming Direction
The streamer class was previously named `FrustumChunkStreamer`, but its behavior is cylinder-based rather than frustum-based.

### Rename goal
Keep streamer terminology aligned with actual behavior.

### Current target name
- `CylinderChunkStreamer`

### Why this name
- it matches the current loading shape
- it aligns with the existing comments and logic
- it avoids overloading the word `frustum`, which is still correct for render culling

### Scope of rename
The rename should cover:
- class name
- constructor references
- imports
- doc comments that describe the streaming policy
- any adjacent docs that incorrectly refer to the streaming policy as frustum-based

### What should not be renamed
Do not rename actual frustum-culling concepts in rendering code.
Examples that should remain frustum-related:
- `ChunkCuller`
- render-time culling comments
- docs that discuss real camera-frustum culling

This rename is the first cleanup task in the current Milestone 1 implementation pass.

---

## Recommended Phasing

## Phase 1: observability and terminology cleanup
### Objective
Make the current system measurable and easier to reason about before adding proxy rendering.

### Scope
- add or expose counters for loaded chunks, pending chunks, and pending meshes
- estimate voxel-memory and mesh-memory pressure where practical
- surface these values in the existing debug app or debug overlay
- rename the current cylinder-based streamer terminology to the correct name

### Deliverables
- debug-visible streaming counters
- clearer naming around the current cylinder-based loading policy
- baseline measurements for existing memory and queue behavior

### Acceptance criteria
- contributors can see chunk, mesh, and queue pressure while moving
- the old misleading streamer terminology is removed from core references
- the current near-field path still behaves exactly as before

---

## Phase 2: proxy architecture skeleton
### Objective
Introduce the far-field concept without changing gameplay ownership.

### Scope
Add planning-level or implementation-level structures for proxy-region identity, proxy caching, and proxy render state.
The exact names can vary, but the responsibilities should be explicit.

### Likely future responsibilities
- proxy region key
- proxy region loaded state
- proxy mesh state
- proxy cache or manager
- proxy generation coordinator
- proxy render integration

### Strong rule
Do not make proxy data pretend to be `VoxelChunk` data.
The proxy path should remain a distinct rendering-oriented system.

### Acceptance criteria
- the codebase has a clear place for far-field data to live
- that place is not mixed into edit-authoritative chunk state
- near-field chunk APIs remain stable

---

## Phase 3: first surface-shell generator
### Objective
Generate useful distant visuals without allocating full raw chunk voxel storage.

### Scope
Build a first-pass proxy generator that derives a coarse outer shell from deterministic world inputs.

### Preferred characteristics
- coarse outer surface only
- broad silhouette preservation
- broad layer color identity
- strong vertical-wall readability
- no interior cave fidelity requirement

### Acceptable technical directions
- coarse density sampling over larger regions
- reduced-resolution shell extraction
- layer-aware height and wall approximation
- hybrid sampling tuned for the abyss shaft and major vertical forms

### Avoid in the first pass
- full volumetric reconstruction
- exact cave topology
- edit-aware re-synthesis
- high-cost seam solving

### Acceptance criteria
- distant regions can render without loading normal chunks
- generated output is deterministic
- proxy generation is cheap enough not to starve nearby chunk work

---

## Phase 4: dual streaming and render integration
### Objective
Make near-field full chunks and far-field proxy regions coexist correctly.

### Scope
- stream full chunks in the inner band
- stream proxy regions outside that band
- allow a small overlap and hysteresis band
- render both systems with clear precedence rules

### Required behavior
- full chunks visually override proxy regions in overlapping space
- promotion happens before the player reaches the region
- demotion does not thrash when the player hovers near thresholds
- proxy failures degrade to temporary missing distant visuals rather than simulation faults

### Acceptance criteria
- the scene remains visually coherent while moving
- transitions are stable even if not yet perfectly seamless
- full chunk counts stay bounded while far visibility expands

---

## Phase 5: tuning and budgets
### Objective
Tune the system around bounded work and bounded memory rather than only radii.

### Scope
- tune inner and outer band sizes
- tune proxy-region dimensions
- tune vertical asymmetry
- set or expose hard caps on pending full jobs and pending proxy jobs
- improve debug counters and overlays

### Important rule
Near-field gameplay work keeps priority over far-field proxy work.
Separate queues are preferred if the implementation reaches that point.

### Acceptance criteria
- increasing view distance does not flood the full chunk pipeline
- the proxy path scales better than the full chunk path
- memory pressure remains observable and understandable

---

## Phase 6: optional later improvements
### Objective
Improve quality after the basic system is proven.

### Candidate follow-ups
- seam reduction between proxy regions
- transition blending or morphing
- proxy persistence or on-disk caching
- deeper hierarchy if simple bands stop scaling well
- more advanced color and lighting approximation
- broader occlusion strategies for distant content

### Rule
Do not start here.
Only move to these improvements after the simple dual-pipeline model is working and measured.

---

## Proposed Ownership Model

### Near-field system owns
- `VoxelChunk`
- collision
- editing
- authoritative voxel queries
- remeshing after edits
- accurate local caves and traversal space

### Far-field system owns
- proxy-region identity
- render-only distant geometry
- broad color and shape continuity
- memory-cheap distant coverage

### Shared concerns
- deterministic seed usage
- scheduling policy
- debug counters
- promotion and demotion rules
- observer-position driven streaming decisions

---

## Candidate Types and Responsibilities
These are planning names, not mandatory final names.
They are here to make boundaries concrete.

### `CylinderChunkStreamer`
Likely the renamed form of the current near-field streamer.
Owns near-field chunk request and unload policy.

### `LodRegionKey`
Identifies a far proxy region by coordinates and LOD level.

### `LodProxyRegion`
Represents a loaded far-field region and its render-oriented state.

### `LodProxyMeshState`
Tracks proxy mesh lifecycle separately from gameplay chunks.

### `LodProxyManager`
Owns loaded proxy-region storage, cache accounting, and lookup.

### `LodProxyGenerator`
Builds deterministic proxy-region geometry from seed-driven inputs.

### `LodStreamer`
Owns distance-band decisions for requesting, promoting, and demoting proxy regions.
This may later coordinate with the near-field streamer.

### `LodDebugStats`
Aggregates counters for queue depth, loaded-region counts, promotions, demotions, and approximate memory use.

---

## Edge Cases to Design For
The first implementation should explicitly account for these cases.

1. **Fast movement**
   - the player moves faster than proxy promotion can finish
   - the system should fail visually first, not break near gameplay streaming

2. **Standing at a threshold**
   - promotion and demotion must not thrash every few frames

3. **Large vertical visibility**
   - detail should not remain symmetric above and below the player

4. **Queue starvation**
   - far proxy work must not delay near chunk work that affects gameplay correctness

5. **Proxy gaps or late generation**
   - temporary missing distant visuals are acceptable if the system recovers cleanly

---

## Metrics to Watch During Development
At minimum, track the following during the first implementation.

- loaded full chunk count
- loaded proxy-region count
- pending full chunk jobs
- pending proxy jobs
- pending full meshes
- approximate voxel memory
- approximate full mesh memory
- approximate proxy mesh memory
- promotions per minute
- demotions per minute
- threshold churn rate

These numbers should guide tuning decisions.
They should not be added only after the system already feels unstable.

---

## Deliberate Non-Goals
This planning document does not require:
- exact final class names
- an octree or quadtree in v1
- distant edit correctness
- a persistence format in the first release
- perfect geomorphing in the first release
- a complete replacement of the current chunk pipeline

---

## Recommended Immediate Next Steps
When implementation begins, the first coding sequence should be:
1. rename the current streamer terminology to `CylinderChunkStreamer`
2. add streaming and memory counters to the debug view
3. introduce explicit proxy-region planning types or interfaces
4. build a minimal proxy-region render path
5. add the first deterministic surface-shell generator
6. tune bands, overlap, and vertical asymmetry using real debug metrics

That order keeps the work measurable and reduces the risk of building a proxy system that is hard to evaluate.