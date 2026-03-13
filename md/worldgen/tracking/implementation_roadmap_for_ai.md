# Abyss World Generation Implementation Roadmap

## Purpose
This document is the authoritative implementation roadmap for the next phase of world generation in Abyss.

It is written for AI agents and human contributors who need a concrete, maintainable plan that prioritizes:
- readability
- extensibility
- deterministic behavior
- performance
- clean architectural boundaries

This document intentionally avoids quick wins and temporary hacks. The goal is to build a world-generation pipeline that can support a long-lived project with multiple layers, strong visual identity, and future gameplay systems.

---

## Current Project State
The project already contains a substantial world-generation foundation.

### Implemented now
- Chunk-based voxel world with `32 x 32 x 32` chunks.
- Seeded procedural generation via `ProceduralGenerator`.
- Seven depth-based layers in `Layer` with per-layer `LayerConfig`.
- `64`-voxel transition zones between adjacent layers.
- Continuous density field generation per voxel.
- A central shaft integrated directly into the density function.
- Cave carving, hazard pocket placement, and a soft horizontal abyss boundary.
- Smooth terrain meshing in `ChunkMesher` using a marching-tetrahedra style isosurface extraction path.
- Async chunk generation and async mesh generation in `ChunkManager` and `MeshManager`.
- Basic meshing tests in `ChunkMesherTest`.
- Voxel color metadata in `VoxelType`.
- Post-generation feature hooks via per-layer feature processors.

### Important implication
The next phase is **not** about inventing the base architecture.
The next phase is about making the architecture:
- verified
- explicit
- easier to extend
- easier to reason about
- visually connected to rendering
- robust under larger content growth

---

## Primary Goal
Build a production-quality world-generation pipeline that is:
1. deterministic and testable
2. modular and easy to extend by layer
3. efficient under chunk streaming and remeshing load
4. visually aligned with the intended look-and-feel
5. ready for persistence and player-driven world edits

---

## Non-Goals
The following are not the immediate focus unless required by a milestone below:
- adding many new voxel types without architectural need
- adding more layers beyond the current seven
- content quantity over system quality
- ad hoc rendering effects not driven by worldgen metadata
- one-off biome logic hardcoded outside the layer system

---

## Architectural Principles
All future work should follow these principles.

### 1. Single source of truth
Layer behavior should be driven from `Layer` and `LayerConfig` as much as possible.
If a system depends on depth-based layer identity, it should not duplicate constants elsewhere.

### 2. Determinism first
For any given `(seed, chunkX, chunkY, chunkZ)`, generation output must be deterministic.
Any non-deterministic or frame-dependent logic is a bug unless explicitly isolated to rendering-only effects.

### 3. Explicit contracts over hidden behavior
Every generation stage should have a clear responsibility:
- density formation
- layer config resolution
- voxel-type assignment
- feature post-processing
- mesh extraction
- streaming and lifecycle management

Avoid mixing responsibilities unless there is a proven performance need.

### 4. Small, focused extension points
When adding new behavior, prefer small interfaces or dedicated classes over larger god objects.
Examples:
- per-layer feature processors
- rendering metadata resolvers
- persistence adapters
- debug and telemetry collectors

### 5. Testability is a first-class requirement
If a new rule cannot be tested deterministically, the design should be reconsidered.
Tests should verify both:
- local behavior
- boundary behavior across chunks and across layers

### 6. Performance with observability
Do not optimize blindly.
Add measurable counters and timings before changing architecture in performance-sensitive areas.

### 7. Preserve code readability
Use explicit curly braces for all control-flow statements.
Prefer descriptive names over compressed logic.
Avoid introducing clever behavior that makes generation harder to reason about.

---

## Recommended Milestone Order
The recommended implementation order is:
1. Worldgen contract hardening
2. Layer system normalization
3. Feature pipeline refinement
4. Rendering integration for worldgen metadata
5. Streaming and meshing observability and tuning
6. Persistence and editable-world integration
7. Full layer content expansion

Each milestone below includes:
- objective
- why it matters
- implementation scope
- deliverables
- acceptance criteria
- dependencies
- risks

---

# Milestone 1: Worldgen Contract Hardening

## Objective
Define and verify the generator contract so the terrain system becomes safe to extend.

## Why this matters
The current generator already blends multiple systems:
- base terrain density
- shaft shaping
- boundary walls
- cave carving
- layer transitions
- voxel selection
- hazards
- waterline rules

Without tests and explicit invariants, future tuning will become fragile.
This milestone should happen before major content or rendering work.

## Scope
Create deterministic tests and supporting documentation for the procedural generation core.

### Required coverage
1. **Seed determinism**
   - same seed + same chunk coordinates => identical densities and voxel types
   - different seeds => meaningfully different output

2. **Layer resolution correctness**
   - `Layer.forY(...)` resolves the correct primary layer at representative heights
   - `Layer.getTransition(...)` resolves correct transition zones
   - transition blend factor behavior is correct at top, middle, and bottom of the blend zone

3. **Transition config blending**
   - numeric fields interpolate correctly
   - discrete fields switch exactly when intended
   - transition width is exactly `64` voxels

4. **Shaft continuity**
   - shaft remains traversable across adjacent chunks
   - shaft remains continuous across layer boundaries
   - shaft perturbation does not collapse pathing near expected centerline

5. **Boundary wall behavior**
   - world becomes solid enough near and beyond abyss radius
   - boundary perturbation does not create exploitable large openings

6. **Oceania waterline behavior**
   - below waterline in Oceania, open air resolves to water
   - above waterline behavior remains normal

7. **Hazard pocket placement**
   - hazard types only occur in intended layers or configs
   - hazard placement remains sparse and deterministic

8. **Chunk seam consistency**
   - adjacent chunks should not disagree on shared world-space samples
   - seam behavior is stable at chunk edges

## Suggested files
- `src/test/java/com/example/abyss/world/gen/LayerTest.java`
- `src/test/java/com/example/abyss/world/gen/LayerConfigTest.java`
- `src/test/java/com/example/abyss/world/gen/ProceduralGeneratorTest.java`
- optional shared test helpers under `src/test/java/com/example/abyss/world/support/`

## Deliverables
- A test suite for generator invariants.
- A short internal comment or doc section describing the generation contract.
- Removal or refactoring of any logic that is difficult to test deterministically.

## Acceptance criteria
- Test suite passes locally and in CI.
- Core generation rules are covered by deterministic tests.
- Contributors can safely tune parameters without guessing at side effects.

## Dependencies
- Existing `Layer`, `LayerConfig`, and `ProceduralGenerator` classes.

## Risks
- Some current behavior may be revealed as underspecified.
- Tests may expose transition or seam assumptions that need small refactors.

---

# Milestone 2: Layer System Normalization

## Objective
Make the layer system the canonical authority for both generation and presentation metadata.

## Why this matters
The current layer model is already strong, but it should become the formal configuration backbone for:
- generation
- atmosphere and visibility
- palette identity
- future feature rules
- future streaming heuristics

This reduces duplication and keeps the project extensible.

## Scope
Normalize how layer data is accessed and consumed.

### Tasks
1. Audit all layer-dependent logic and ensure it flows through `Layer` or `LayerConfig`.
2. Avoid duplicated constants for:
   - transition size
   - visibility assumptions
   - depth ranges
   - default per-layer behavior
3. If needed, introduce helper APIs for common queries, for example:
   - current primary layer at world Y
   - current transition info at world Y
   - effective config at world Y
4. Clarify which fields are:
   - generation-facing
   - rendering-facing
   - gameplay-facing
5. If current `LayerConfig` is becoming overloaded, split it into multiple small records rather than one giant object.

### Potential refactor direction
If complexity grows, consider evolving `LayerConfig` into grouped concerns such as:
- `TerrainShapeConfig`
- `MaterialSelectionConfig`
- `AtmosphereConfig`
- `FeatureConfig`

Do **not** split prematurely. Only split if the current record is becoming semantically noisy.

## Deliverables
- Layer API is easier to consume from both generation and rendering code.
- Fewer hardcoded depth assumptions outside the layer package.
- Clear doc comments explaining each category of layer data.

## Acceptance criteria
- A contributor can add or tune a layer primarily by changing layer-owned configuration.
- Rendering and gameplay systems do not duplicate layer rules unnecessarily.

## Dependencies
- Milestone 1 recommended first.

## Risks
- Over-abstraction can reduce clarity.
- Refactor should stay incremental and avoid unnecessary generalization.

---

# Milestone 3: Feature Pipeline Refinement

## Objective
Make each layer structurally distinct through maintainable, testable feature processors.

## Why this matters
At the moment, layers differ strongly in parameters but likely not enough in authored procedural identity.
The next stage is to make layers feel intentionally different without turning the generator into an unstructured ruleset.

## Scope
Refine the feature-processing stage into a stable extension point.

### Design requirements
1. Feature processors must remain deterministic.
2. Feature processors must be layer-scoped and easy to locate.
3. Feature processors must not obscure the core terrain contract.
4. Feature processors should operate with explicit responsibilities.
5. Feature processors should avoid fighting the density field in ways that cause ugly seams or meshing artifacts.

### Recommended model
For each layer, define at least one or two signature features that make it visually and mechanically distinct.
Examples:
- `THE_EDGE`: safe terraces, grounded terrain rhythm, approachable traversal around the shaft
- `FOREST_DEPTHS`: trunks, roots, hanging vines, denser vertical silhouettes
- `HANGING_GARDENS`: floating masses, bridges, root curtains
- `SKY_WALLS`: giant shelves, sparse ledges, large-scale negative space
- `MYCELIUM`: fungal pillars, spore zones, organic columns
- `OCEANIA`: submerged columns, coral masses, island fragments
- `DEEP_EXTREMES`: crystal growth, obsidian spikes, dangerous anomalies

### Implementation guidance
- Keep feature logic in dedicated classes under `world/gen/features`.
- If features become numerous, introduce a small pipeline abstraction rather than increasing `ProceduralGenerator` complexity.
- Distinguish between:
  - density modifications
  - voxel-type painting
  - hazard placement
  - decorative or atmospheric markers

### Testing expectations
Add tests for at least:
- deterministic feature output
- no unexpected chunk-boundary corruption
- features remain restricted to intended layer contexts

## Deliverables
- A clear, maintainable feature pipeline.
- At least one fully-realized signature feature per layer or a staged plan to add them incrementally.
- Tests for core feature invariants.

## Acceptance criteria
- Layers are visibly distinct because of structure, not just palette or thresholds.
- New layer features can be added without turning the generator into monolithic conditional logic.

## Dependencies
- Milestone 1 complete.
- Milestone 2 strongly recommended.

## Risks
- Overly aggressive post-processing may break smooth meshing quality.
- Too much discrete voxel painting can clash with organic terrain surfaces.

---

# Milestone 4: Rendering Integration for Worldgen Metadata

## Objective
Use existing worldgen metadata to drive visuals in a clean and reusable way.

### Implementation status note
An initial visualization MVP is now in place in the desktop LibGDX app:
- a render-side sample contract under `world/render`
- a deterministic 2D X/Y slice viewer backed by `AbyssWorld`
- color resolution driven by `VoxelType.baseColor`, `LayerConfig.colorPalette`, and `LayerConfig.visibility`
- transition overlay and chunk-grid rendering for debugging seams and layer boundaries

This is intentionally a debug visualization step, not the final production renderer.
It satisfies the architectural direction of letting rendering consume worldgen metadata through explicit interfaces without moving rendering concerns into `world/gen`.

## Why this matters
The project already stores rendering-relevant metadata:
- voxel base colors
- layer visibility
- layer palettes

These values should influence visual output systematically, not ad hoc.
This is the correct path to achieving the intended Cube World-like atmosphere while preserving clean boundaries.

## Scope
Connect worldgen metadata to the renderer through a narrow, explicit interface.

### Required principles
1. Rendering should consume worldgen metadata, not re-derive it.
2. Rendering behavior should be data-driven where practical.
3. Visual logic should not leak generation responsibilities into the renderer.

### Tasks
1. Define how the renderer obtains current world layer or blended transition context.
2. Use `LayerConfig.visibility` to influence fog or atmospheric density.
3. Use `LayerConfig.colorPalette` to influence scene tint, ambient color, sky color, or transition blending.
4. Continue to use `VoxelType.baseColor` for vertex coloring or material base color.
5. Add explicit handling for emissive or atmospheric materials where appropriate:
   - `CRYSTAL`
   - `TOXIC_GAS`
   - possibly `WATER`

### Recommended boundary
If needed, create a small adapter or resolver class rather than letting the renderer know too much about generation internals.
Possible example responsibilities:
- resolve current layer presentation state from player depth
- provide fog settings
- provide palette/tint settings
- expose transition-aware blend values

### Important note
Keep worldgen data authoritative, but keep renderer implementation details outside the generation package.

## Deliverables
- Renderer consumes layer metadata intentionally.
- Visual differences across depth feel grounded in world data.
- Transition zones can be reflected visually as well as geometrically.

## Acceptance criteria
- Fog, tint, and atmosphere vary coherently by depth.
- Visual rules remain maintainable and do not duplicate generation logic.

## Dependencies
- Milestone 2 recommended.
- Some renderer-specific implementation point may need discovery.

## Risks
- Visual improvements can expose generation weaknesses.
- Transparent and emissive materials may require careful ordering or batching.

---

# Milestone 5: Streaming, Meshing, and Performance Observability

## Objective
Make chunk generation and meshing measurable, tunable, and stable under load.

## Why this matters
The architecture already supports async generation and meshing, but content growth and visually open layers will increase pressure on:
- chunk queues
- meshing cost
- memory
- frame stability

Do not wait until the project feels slow to add observability.

## Scope
Add instrumentation, then tune behavior from evidence.

### Required metrics
At minimum, expose:
- loaded chunk count
- pending generation count
- pending mesh count
- average or recent chunk generation time
- average or recent mesh build time
- current player layer
- current streaming radii in effect

### Tuning areas
1. Per-layer loading policy
   - horizontal radius
   - vertical radius
   - maybe layer-specific presets based on openness and visibility

2. Unload behavior
   - avoid aggressive unload/reload churn
   - consider hysteresis if needed

3. Mesh queue pressure
   - avoid unbounded remesh backlog
   - prioritize visible or nearby chunks if necessary

4. Seam safety
   - verify neighbor dependencies for smooth meshing remain correct under async loading

### Implementation guidance
- Instrument first.
- Keep metrics lightweight.
- Prefer stable counters and timing windows over excessive logging spam.

## Deliverables
- A visible debug or telemetry path for world streaming behavior.
- Measurable performance data.
- Adjusted loading strategy grounded in observed costs.

## Acceptance criteria
- Contributors can tell whether bottlenecks are generation, meshing, or streaming policy.
- Open layers do not collapse the system due to naive radius assumptions.

## Dependencies
- Existing async pipeline in `ChunkManager` and `MeshManager`.

## Risks
- Excessive instrumentation can clutter code if not organized cleanly.
- Premature optimization without measurements should be avoided.

---

# Milestone 6: Persistence and Editable World Integration

## Objective
Support a persistent, editable world without undermining deterministic generation.

## Why this matters
The project already allows voxel edits through `AbyssWorld.setVoxelAt(...)`, but long-term gameplay needs a clean contract for how procedural chunks interact with saved modifications.

## Scope
Define the persistence boundary between generated data and player-modified data.

### Required design questions
1. What is persisted?
   - full chunk snapshots
   - sparse diffs
   - voxel type only
   - voxel type plus density

2. When is persisted data applied?
   - before generation
   - after generation as an overlay
   - during chunk load resolution

3. How are chunk-border edits handled?
   - mesh invalidation
   - neighbor updates
   - persistence consistency

### Recommended direction
Prefer a sparse diff model unless proven insufficient.
Conceptually:
- base chunk comes from deterministic generation
- player modifications are stored separately by `ChunkKey`
- load path applies the diff after generation and before meshing

### Important technical issue
Because the terrain is smooth, changing only voxel type may not be enough.
A persistent edit model should eventually account for whether edits affect:
- material only
- occupancy only
- underlying density field

That decision should be made explicitly.

## Deliverables
- A documented persistence strategy.
- Chunk modification storage design.
- Edit-path tests, including boundary edits.

## Acceptance criteria
- Player edits survive unload/reload cycles.
- Persistence does not break deterministic base generation.
- Neighbor meshes remain correct at chunk borders.

## Dependencies
- Milestone 1 recommended.
- Existing edit invalidation logic in `AbyssWorld`.

## Risks
- Smooth terrain editing is harder than binary voxel editing.
- Save format choices can create long-term migration costs.

---

# Milestone 7: Full Layer Content Expansion

## Objective
Complete the layer set only after the system is proven maintainable.

## Why this matters
Adding large amounts of content before architecture and tests are stable will create expensive rework.

## Scope
Once the earlier milestones are complete, expand each layer into a full gameplay-ready environment.

### Requirements
- Each layer should have a distinct structural identity.
- Each layer should have coherent visual presentation.
- Each layer should support its own traversal and hazard profile.
- Streaming and meshing policies should remain stable for all layer types.

### Suggested expansion strategy
Do not expand all layers equally at once.
Use a progression like:
1. `THE_EDGE`
2. `SKY_WALLS` or `OCEANIA`
3. `MYCELIUM`
4. remaining layers

This sequence gives contrast and exposes different engine stresses earlier.

## Deliverables
- A staged content rollout plan for all seven layers.
- Tests and telemetry retained as content complexity increases.

## Acceptance criteria
- New content additions do not significantly degrade readability, performance, or determinism.

## Dependencies
- Milestones 1 through 6 strongly recommended first.

---

## Recommended Near-Term Priority Order
If implementing immediately, use this order:

1. `ProceduralGenerator` tests and generator contract documentation.
2. `Layer` and `LayerConfig` normalization and cleanup.
3. Feature pipeline refinement for clear per-layer identity.
4. Rendering integration for fog, tint, and emissive handling driven by worldgen data.
5. Streaming and meshing instrumentation and tuning.
6. Persistence and editable-world contract.
7. Full multi-layer content pass.

---

## Suggested Task Breakdown for an AI Agent
When acting on this roadmap, follow this sequence:

1. Read all files under:
   - `src/main/java/com/example/abyss/world/gen/`
   - `src/main/java/com/example/abyss/world/meshing/`
   - `src/main/java/com/example/abyss/world/`
   - `src/main/java/com/example/abyss/world/voxel/`
   - relevant renderer files if present

2. Before changing generation behavior:
   - add or update tests
   - state the exact invariant being protected

3. Keep edits small and grouped by responsibility.

4. After each non-trivial change:
   - run tests
   - check compile errors
   - validate seam and boundary assumptions

5. Avoid introducing hidden coupling between generation and rendering.

6. Prefer refactoring toward smaller focused classes over adding more branching inside `ProceduralGenerator`.

7. When adding a new feature rule, document:
   - which layer owns it
   - whether it changes density, voxel type, or both
   - how it should behave at chunk boundaries
   - how it is tested

---

## Coding Expectations for Contributors and AI Agents
- Use explicit curly braces for all control-flow statements.
- Preserve determinism.
- Preserve readability.
- Keep functions focused.
- Avoid duplicate constants.
- Avoid temporary logic unless clearly marked and isolated.
- Favor composition and dedicated helper classes over growing monoliths.
- Do not mix rendering-only concerns into core generation classes.
- Do not mix persistence concerns into low-level math helpers.

---

## Definition of Done for the Worldgen Foundation Phase
The foundation phase should be considered complete only when all of the following are true:

1. Generator invariants are covered by deterministic tests.
2. Layer configuration is the clear source of truth for depth-based behavior.
3. Feature processors are structured, readable, and testable.
4. Renderer consumes worldgen metadata through explicit interfaces.
5. Chunk generation and meshing performance are observable and tunable.
6. Editable-world persistence design is documented and minimally implemented.
7. The project remains easy to understand for a new contributor or AI agent.

---

## Final Guidance
If tradeoffs occur, choose in this order:
1. correctness and determinism
2. maintainability and readability
3. extensibility
4. measured performance
5. visual polish
6. content quantity

The project already has the beginnings of a strong architecture.
The correct next move is to harden and clarify it before significantly expanding content.
