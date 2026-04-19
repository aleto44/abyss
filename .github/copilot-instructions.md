# Copilot Instructions

High-performance procedurally generated voxel world: chunk streaming, multi-level LOD, mesh baking, memory-efficient data.

## Codebase Reference
- **Directory & index:** [`md/codebase.md`](../md/codebase.md) — start here; links to all architecture docs, per-package summaries, and key invariants.
- **Full class reference:** [`md/architecture/codebase_architecture.md`](../md/architecture/codebase_architecture.md) — every class, its responsibility, owned data, data-flow sequences.

## Architecture
- SRP: one responsibility per class/file.
- `Chunk` = pure data only (voxels, dirty flags, metadata). No generation, meshing, streaming, or rendering.
- Mesh generation = pure function: `(ChunkData, LODLevel) → Mesh`. No side effects.
- `WorldStreamer`/LOD owns loading, unloading, priority queuing, hysteresis.
- Prefer events/explicit queries over direct references between systems.
- Procedural generation must be deterministic and seedable.
- Hot paths: minimize allocations, prefer pooling.
- No god classes; methods ≤ 120 lines.

## Code Style
- Always use explicit `{}` for all control flow (`if`, `else`, `for`, `while`, etc.).
- Terminal commands: Git Bash syntax on Windows (e.g. `cd '/c/Users/alexj/IdeaProjects/abyss' && ./gradlew.bat test`).
- No obvious comments. Comment only non-obvious intent.
- Small, focused functions. Domain-specific naming (`ChunkData`, `LodRegion`, `PureMeshBuilder`).
- All public APIs have a one-line summary.
- Renderer code isolated from data/generation layers.
- Preserve existing behavior and performance when refactoring.

## Communication Style
- Intermediate reasoning, planning, and tool use: terse/abbreviated is fine.
- Finalized output (summaries, docs, code comments, API descriptions): use clear, normal English.

## Booleans & Readability
- Extract conditions into named booleans or methods: `boolean isReady = ...; if (isReady) { ... }`
- Prefer structured control flow over early-return guards unless guards clearly simplify the whole method.
- 8+ parameters → group into a record/context object or split the method.