# Abyss Game World Generation Specification

This document provides a detailed specification for the world generation system in the Abyss-inspired voxel game. It focuses exclusively on procedural generation of the abyss world, including structure, layers, transitions, and voxel mechanics. This is intended as input for an AI agent to assist in development, prototyping, or code generation. Use this as a descriptive blueprint.

## Overview
- **World Type**: A massive, vertically layered voxel-based abyss with procedural generation for randomness and replayability. The world is focused on downward exploration, with a wide horizontal expanse (e.g., 10-50km diameter at the top, expanding irregularly deeper).
- **Generation Approach**: Seeded procedural generation using 3D noise functions (e.g., Simplex or OpenSimplex via libraries like FastNoiseLite). Generate on-demand per chunk to ensure performance.
- **Key Features**:
    - Infinite downward depth in theory, but structured into 7-10 distinct layers.
    - One central main shaft as the primary descent path.
    - Layer-specific biomes and traits for variety.
    - Smooth transitions between layers.
    - Voxel manipulation (mining, building) integrated with generation.
- **Performance Considerations**: Chunk-based loading/unloading, multithreaded generation, greedy meshing for rendering efficiency. Aim for smooth performance (60 FPS) on mid-range hardware.
- **Tech Stack Integration**: Designed for Java 21+ with LibGDX. Use LibGDX for 3D rendering and asset management; custom extensions for voxel handling.

## Abyss Structure
- **Overall Layout**: A vertically infinite pit with procedural terrains, caves, ruins, and biomes. The abyss widens and becomes more complex deeper down, with irregular expansions.
- **Main Shaft**: A giant central vertical tunnel that spans all layers:
    - Diameter: 100-500 voxels, varying noisily per layer (e.g., wider in open layers like oceans, narrower in cave-heavy ones).
    - Generation: Carved as a noisy cylinder along the center (x=0, z=0). Use noise to create irregular edges, branches, and overhangs for natural feel.
    - Purpose: Serves as the main descent route, with side paths leading to layer-specific features.
- **Horizontal Bounds**: Soft boundaries via procedural "walls" (dense cliffs or barriers) to simulate the abyss's perimeter, preventing infinite horizontal sprawl while allowing exploration.
- **Vertical Focus**: Y-axis is depth (positive surface, negative deeper). No strict depth limit, but layers guide progression.

## Layers and Traits
- **Number of Layers**: 7 distinct zones, each with escalating difficulty, unique biomes with different coloring schemes, and environmental hazards. Layers defined by depth ranges (e.g., Layer 1: y=0 to -500; Layer 2: -501 to -1500).
- **Layer Progression**: Deeper layers have rarer artifacts, more complex ecosystems, and harsher conditions.
- **Specific Traits**: Each layer has a dominant theme, modified by noise for variety:
    - **The Edge (Layer 1)**: Tutorial-friendly. Mild hazards, common materials. Traits: Lush vegetation voxels, basic caves, stable ground, easy visibility.  Grass and bushes.
    - **Forest Depths (Layer 2)**: Dense foliage, symbiotic plants/creatures. High voxel density for trees and vines, with clearings and caves.
    - **Hanging Gardens (Layer 3)**: Floating islands and structures. Voxel clusters suspended in air, connected by natural bridges of roots or vines. intricate floating formations.
    - **Sky Walls (Layer 4)**: Mostly open air/sky with giant perimeter walls. Narrow caves and ledges branching from walls. Vast emptiness
    - **Mycelium (Layer 5)** : Fungal biome with organic growths. Voxels form giant mushrooms, spore clouds, and bioluminescent flora.  Low visibility 
    - **Oceania(Layer 6)**: Primarily a giant ocean. High water voxel density, with sparse islands, columns, or floating structures in the main shaft. Dynamic water flow, underwater ruins, aquatic hazards (e.g., currents, drowning zones).
    - **Deep Extremes (Layer 7)**: The deepest, most dangerous zone. Reality-warping phenomena (e.g., inverted gravity zones), toxic gases, unstable collapsing voxels. Sparse but highly valuable artifacts.
- **Layer Modifiers**: Each layer has parameters like:
    - TODO

## Transitions Between Layers
- **Blending Mechanism**: Smooth transitions over 50-100 voxels vertically to avoid abrupt changes.
    - Interpolation: Blend noise values, density thresholds, and voxel types between adjacent layers (e.g., linear fade from ocean water to sky air).
    - Visual/Functional: Gradual shift in biomes (e.g., water levels receding into misty air). Ensure main shaft continuity.
    - Generation Logic: When generating chunks near boundaries, query both layers and apply a blend factor (0-1 based on proximity to boundary).

## Voxel System
- **Voxel Types**: Enum-based with properties:
    - Examples: Air (non-solid, transparent), Stone (solid, basic), Water, Grass (solid, organic), Toxic Gas (hazardous, spreading).
    - Attributes: Solidity (for collisions), texture (for rendering), interactivity (e.g., mineable, flammable).
- **Chunk System**: World divided into 16x16x16 voxel chunks for management.
    - Loading: Lazy, proximity-based (e.g., 5-10 chunk radius horizontally, extended vertically for visibility).
    - Data: 3D array per chunk (byte/int for type IDs).
    - Meshing: See World_generation_like_cube_world.md for smooth meshing techniques.
- **Physics and Interactions**: Voxels affect gameplay:
    - Collapses: Unstable structures fall if support removed.
    - Fluids
    - Hazards: Layer-specific (e.g., gas pockets in deep layers).

## Procedural Generation Process
- **Seed**: Use a long seed for reproducibility across runs/multiplayer.
- **Noise Functions**: 3D noise for base density; additional layers for features (e.g., separate noise for caves, artifacts).
- **Generation Steps (Per Chunk)**:
    1. Determine layer(s) based on chunk Y-position.
    2. Generate base terrain: Apply noise to set voxel types (solid if noise > density threshold).
    3. Carve main shaft: Override to air if within noisy radius.
    4. Apply layer traits: Modify for specifics (e.g., flood with water in ocean layer).
    5. Handle transitions: If near boundary, blend with next layer's parameters.
- **Multiplayer Sync**: Server authoritative; generate and sync chunks to clients on demand.
- **Artifacts Placement**: Procedural spawns in hidden areas; probability decreases with depth but value increases.

## Integration with Other Systems
- **Rendering**: Use LibGDX ModelBatch for chunk meshes; frustum/occlusion culling.
- **Entities**: Creatures and players interact with voxels (e.g., pathfinding avoids water).
- **Knowledge Mechanic**: Generated features tie into learning (e.g., observe patterns for safe paths).
- **Extensibility**: Design with interfaces for adding new layers/traits without core changes.

This specification ensures a dynamic, immersive world that captures the essence of descent into the unknown while maintaining performance and procedural variety.
