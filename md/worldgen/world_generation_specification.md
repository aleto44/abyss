# Abyss Game World Generation Specification

This document provides a detailed specification for the world generation system in the Abyss-inspired voxel game. It focuses exclusively on procedural generation of the abyss world, including structure, layers, transitions, and voxel mechanics. This is intended as input for an AI agent to assist in development, prototyping, or code generation. Use this as a descriptive blueprint.

## Overview
- **World Type**: A massive, vertically layered voxel-based abyss with procedural generation for randomness and replayability. The world is focused on downward exploration, with a wide horizontal expanse (e.g., 10-50km diameter at the top, expanding irregularly deeper).
- **Generation Approach**: Seeded procedural generation using 3D noise functions (Simplex via FastNoiseLite, vendored as a source file). Generate on-demand per chunk to ensure performance.
- **Key Features**:
    - Infinite downward depth in theory, but structured into 7 distinct layers.
    - One central main shaft as the primary descent path.
    - Layer-specific biomes and traits for variety.
    - Smooth transitions between layers.
    - Voxel manipulation (mining, building) integrated with generation.
- **Performance Considerations**: Chunk-based loading/unloading, multithreaded generation, marching cubes meshing for smooth organic terrain (see world_generation_like_cube_world.md). Aim for smooth performance (60 FPS) on mid-range hardware.
- **Tech Stack Integration**: Designed for Java 21+ with LibGDX. Use LibGDX for 3D rendering and asset management; custom extensions for voxel handling. Noise generation via FastNoiseLite (vendored Java source, v1.1.1).

## Abyss Structure
- **Overall Layout**: A vertically infinite pit with procedural terrains, caves, ruins, and biomes. The abyss widens and becomes more complex deeper down, with irregular expansions.
- **Main Shaft**: A giant central vertical tunnel that spans all layers:
    - Diameter: 100-500 voxels, varying per layer (e.g., wider in open layers like Oceania, narrower in cave-heavy ones like Mycelium). Controlled by the `shaftDiameter` layer modifier.
    - Generation: The shaft is **integrated directly into the density function**, not carved as a post-processing step. For each voxel, a `shaftTerm` is computed based on the horizontal distance from the center axis (x=0, z=0). This term is subtracted from the terrain density value using a smooth falloff (smoothstep), so the shaft emerges naturally with organic, blended edges. A secondary noise function perturbs the shaft radius to create irregular edges, branches, and overhangs. This approach avoids hard-carve artifacts like floating terrain fragments at shaft boundaries.
    - Formula: `finalDensity = terrainDensity - shaftTerm(x, z)` where `shaftTerm` = `smoothstep(shaftRadius - margin, shaftRadius + margin, distance)` mapped so it equals 1.0 (forcing air) well inside the shaft and 0.0 (no effect) well outside.
    - Purpose: Serves as the main descent route, with side paths leading to layer-specific features.
- **Horizontal Bounds**: Soft boundaries via procedural "walls" (dense cliffs or barriers) to simulate the abyss's perimeter, preventing infinite horizontal sprawl while allowing exploration.
- **Vertical Focus**: Y-axis is depth (positive surface, negative deeper). No strict depth limit, but layers guide progression.

## Layers and Traits
- **Number of Layers**: 7 distinct zones, each with escalating difficulty, unique biomes with different coloring schemes, and environmental hazards. Layers defined by depth ranges (e.g., Layer 1: y=0 to -500; Layer 2: -501 to -1500).
- **Layer Progression**: Deeper layers have rarer artifacts, more complex ecosystems, and harsher conditions.
- **Specific Traits**: Each layer has a dominant theme, modified by noise for variety:
    - **The Edge (Layer 1)**: Tutorial-friendly. Mild hazards, common materials. Traits: Lush vegetation voxels, basic caves, stable ground, easy visibility. Grass and bushes.
    - **Forest Depths (Layer 2)**: Dense foliage, symbiotic plants/creatures. High voxel density for trees and vines, with clearings and caves.
    - **Hanging Gardens (Layer 3)**: Floating islands and structures. Voxel clusters suspended in air, connected by natural bridges of roots or vines. Intricate floating formations.
    - **Sky Walls (Layer 4)**: Mostly open air/sky with giant perimeter walls. Narrow caves and ledges branching from walls. Vast emptiness.
    - **Mycelium (Layer 5)**: Fungal biome with organic growths. Voxels form giant mushrooms, spore clouds, and bioluminescent flora. Low visibility.
    - **Oceania (Layer 6)**: Primarily a giant ocean. High water voxel density, with sparse islands, columns, or floating structures in the main shaft. Water is placed as a static voxel type below a per-layer waterline; no dynamic fluid simulation. Underwater ruins, aquatic hazards (e.g., currents, drowning zones).
    - **Deep Extremes (Layer 7)**: The deepest, most dangerous zone. Reality-warping phenomena (e.g., inverted gravity zones), toxic gases, unstable collapsing voxels. Sparse but highly valuable artifacts.
- **Layer Modifiers**: Each layer has configurable parameters that drive the procedural generation pipeline:
    - `baseDensityThreshold` (float): The noise threshold above which a voxel is solid. Lower values = more solid terrain, higher = more air/caves.
    - `noiseFrequency` (float): Controls the scale of terrain features. Lower = large smooth formations, higher = fine-grained detail.
    - `noiseAmplitude` (float): Scales the noise output. Higher = more dramatic elevation changes and cave variation.
    - `shaftDiameter` (float): Diameter of the main shaft in voxels for this layer. The shaft term in the density function uses this as its radius reference.
    - `dominantVoxelTypes` (VoxelType[]): The primary voxel types used when assigning solid voxels in this layer. Selected via a secondary noise octave for natural variation.
    - `hazardType` (VoxelType, nullable): The hazardous voxel type specific to this layer (e.g., TOXIC_GAS for Deep Extremes). Placed in pockets via a separate noise pass.
    - `visibility` (float): Ambient visibility range in voxels. Drives fog density in the renderer.
    - `colorPalette` (Color[]): Layer-specific color tints applied during rendering for biome identity.

## Transitions Between Layers
- **Blending Mechanism**: Smooth transitions over exactly **64 voxels** vertically (2 chunks at 32³) to avoid abrupt changes. Transition zones are aligned to chunk boundaries so that any single chunk straddles at most 2 layers.
    - Interpolation: Blend all `LayerConfig` parameters (density threshold, noise frequency, amplitude, shaft diameter) between adjacent layers using a linear blend factor (0.0–1.0) based on the voxel's Y-position within the transition zone. Voxel type selection uses the dominant layer (blend factor > 0.5 picks the deeper layer's types).
    - Visual/Functional: Gradual shift in biomes (e.g., water levels receding into misty air). Ensure main shaft continuity across transitions by interpolating shaft diameter smoothly.
    - Generation Logic: When generating a chunk, first check if the chunk's Y-range overlaps a transition zone. If so, each voxel in the chunk evaluates the blended `LayerConfig` for its specific Y-coordinate. This means transition-zone chunks perform ~2× the noise lookups (one per layer), which is acceptable given only 2 chunks per transition are affected.
    - Boundary Alignment: Transition zones are centered on layer boundaries. With 64-voxel zones and 32-voxel chunks, exactly 1 chunk above and 1 chunk below each boundary are "transition chunks." Non-transition chunks use a single layer's config with no blending overhead.

## Voxel System
- **Voxel Types**: Enum-based with properties:
    - Examples: Air (non-solid, transparent), Stone (solid, basic), Water (non-solid, transparent), Grass (solid, organic), Toxic Gas (hazardous, non-solid).
    - Attributes: Solidity (for collisions), transparency (for rendering/light), hazardous (for gameplay effects), base color (for vertex coloring).
- **Chunk System**: World divided into **32×32×32** voxel chunks for management.
    - Loading: Lazy, proximity-based (e.g., 4-8 chunk radius horizontally, extended vertically for visibility in open layers like Sky Walls).
    - Data: Per chunk, a flat `float[32768]` density field (for marching cubes meshing) and a `VoxelType[32768]` type field (for gameplay logic). Indexed as `y * 1024 + z * 32 + x`.
    - Meshing: Marching cubes isosurface extraction from the density field. See world_generation_like_cube_world.md for implementation details.
- **Physics and Interactions**: Voxels affect gameplay:
    - Collapses: Unstable structures fall if support removed.
    - Hazards: Layer-specific (e.g., gas pockets in Deep Extremes, drowning zones in Oceania).

## Procedural Generation Process
- **Seed**: Use a `long` seed for reproducibility across runs/multiplayer. All noise instances derive from this seed.
- **Noise Functions**: 3D Simplex noise via FastNoiseLite for base density; additional noise octaves for cave carving, shaft perturbation, and voxel type variation.
- **Generation Steps (Per Chunk)**:
    1. **Determine layer(s)**: Based on chunk Y-range, identify the primary layer and whether the chunk falls in a transition zone.
    2. **Evaluate combined density function**: For each voxel in the 32³ grid, compute world coordinates and evaluate:
        - `terrainDensity = noise3D(x, y, z) * amplitude + baseDensityThreshold` (using the layer's or blended config).
        - `shaftTerm = smoothstep(shaftRadius - margin, shaftRadius + margin, horizontalDist)` subtracted from terrain density, where `horizontalDist = sqrt(x² + z²)` and shaft radius is perturbed by a secondary noise.
        - `finalDensity = terrainDensity - shaftTerm`
    3. **Assign voxel types**: If `finalDensity > 0`, select from the layer's `dominantVoxelTypes` using a secondary noise octave. Otherwise, assign `AIR` (or `WATER` if below the waterline in Oceania, `TOXIC_GAS` in hazard pockets).
    4. **Apply layer-specific features**: Post-processing for layer traits (e.g., place giant mushroom structures in Mycelium, floating island clusters in Hanging Gardens).
- **Multiplayer Sync**: Server authoritative; generate and sync chunks to clients on demand.

## Integration with Other Systems
- **Rendering**: Use LibGDX ModelBatch for chunk meshes; frustum/occlusion culling. Marching cubes meshes built from chunk density fields.
- **Entities**: Creatures and players interact with voxels (e.g., pathfinding avoids water).
- **Knowledge Mechanic**: Generated features tie into learning (e.g., observe patterns for safe paths).
- **Persistence**: Save/load of modified chunks and world state is covered in a separate specification. The generation pipeline includes a hook to check for saved chunk modifications before regenerating from seed.
- **Extensibility**: Design with interfaces for adding new layers/traits without core changes.

This specification ensures a dynamic, immersive world that captures the essence of descent into the unknown while maintaining performance and procedural variety.
