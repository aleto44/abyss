# Copilot Instructions

This document provides guidelines and instructions for using GitHub Copilot in the Abyss project.

## Purpose
- Project context: High-performance procedurally generated voxel world featuring chunk streaming, multi-level LOD regions, mesh baking, and memory-efficient data handling.Project context: High-performance procedurally generated voxel world featuring chunk streaming, multi-level LOD regions, mesh baking, and memory-efficient data handling.
- Keep Copilot suggestions aligned with this project's coding style, tooling, and workflow.

## Core Principles (Always enforce these)
- Single Responsibility Principle: Every class or file has exactly one clear responsibility.
- Chunk classes contain only pure data (voxel storage, dirty flags, basic metadata). No generation, meshing, streaming, or rendering logic inside Chunk.
- Mesh generation must be implemented as pure functions: input = (ChunkData, LODLevel), output = Mesh. No side effects.
- WorldStreamer / LOD system owns all loading, unloading, priority queuing, and hysteresis logic.
- Inter-system communication prefers events or explicit queries over direct object references or tight coupling.
- Procedural generation is always deterministic and fully seedable.
- Hot paths (meshing, chunk updates, streaming checks) must minimize allocations; prefer pooling where appropriate.
- No god classes or methods longer than 200 lines (target under 120 lines).

## Best Practices
- Modular Code: Write small, focused functions and split out classes to allow Copilot to generate more accurate suggestions.
- Always use explicit curly brackets for control flow statements (`if`, `else`, `for`, `while`, etc.). Do not use implied/optional curly brackets. For example, use `if (future != null) { future.cancel(false); }` instead of `if (future != null) future.cancel(false);`
- Default terminal commands to Git Bash syntax on Windows unless the user explicitly asks for a different shell. Use Git Bash-style paths and command chaining. For example: `cd '/c/Users/alexj/IdeaProjects/abyss' && ./gradlew.bat test`
- Dont make obvious commments.

## Refactoring & Code Quality Rules
- When refactoring or suggesting changes, preserve exact existing behavior and performance characteristics.
- Aggressively remove duplicated logic, temporary vibe-coded hacks, mixed responsibilities, and unnecessary complexity.
- Improve naming for clarity and domain specificity (e.g., ChunkData, LodRegion, PureMeshBuilder).
- Add comments only for non-obvious intent.
- Keep renderer-specific code completely isolated from data and generation layers.

## Usage Guidelines
- For any new feature or bug fix, first ensure the change fits the architecture above.
- Prioritize data-oriented design for voxel grids where it improves performance or clarity.
- All public APIs should have a concise one-line summary.

## Code Readability & Boolean Clarity
- **Avoid short-circuit returns/guards by default**: Prefer not using early-return short-circuits unless doing so makes the overall method significantly easier to read as a whole. Favour explicit, structured control flow that reveals the full intent of a method.
- **Name your booleans**: Instead of embedding conditions directly in `if` statements or return expressions, extract them into explicitly named local boolean variables or boolean methods that describe what the condition *means*. This makes the intent self-documenting.
  ```java
  // Prefer:
  boolean isReadyToFire = cooldown <= 0 && hasAmmo && !isReloading;
  if (isReadyToFire) { fire(); }

  // Avoid:
  if (cooldown <= 0 && hasAmmo && !isReloading) fire();
  ```
- **Prefer named boolean methods over inline predicates**: When a condition is reused or complex, extract it into a clearly named method rather than repeating or inlining the logic.
  ```java
  // Prefer:
  boolean canAttack() { return cooldown <= 0 && hasAmmo && !isReloading; }
  if (canAttack()) { fire(); }

  // Avoid:
  if (cooldown <= 0 && hasAmmo && !isReloading) { fire(); }
  ```
- **Avoid large parameter lists (8+ parameters)**: Methods with 8 or more parameters are a strong signal that the method is doing too much or that related parameters should be grouped. Refactor by:
  - Grouping related data into a context object, record, or builder.
  - Splitting the method into smaller, more focused methods.
  - Moving parameters that belong to an object onto that object as fields.
  ```java
  // Avoid:
  void spawnEntity(float x, float y, float z, int health, int attack, int defense,
                   float speed, boolean hostile, EntityType type)

  // Prefer:
  record EntitySpawnParams(float x, float y, float z,
                           int health, int attack, int defense,
                           float speed, boolean hostile, EntityType type) {}
  void spawnEntity(EntitySpawnParams params)
  ```
---
*Update this document as project requirements evolve.*