# abyss

A Java + LibGDX prototype for Abyss world generation, chunk streaming, and smooth voxel meshing.

## Worldgen visualization MVP

The desktop app now includes a deterministic 2D X/Y slice viewer for world generation.
It samples the existing `AbyssWorld` and `Layer` metadata without mixing rendering logic into the generation package.

### Current viewer controls
- `Arrow keys`: move the slice center in world X/Y
- `Page Up` / `Page Down`: move the sampled Z slice
- `+` / `-`: zoom in or out by changing world sample step size
- `G`: toggle chunk-grid overlay

### What the viewer shows
- voxel/material colors driven by `VoxelType.baseColor`
- open-air tint influenced by `LayerConfig.colorPalette` and `LayerConfig.visibility`
- transition-zone striping for easier boundary inspection
- chunk-grid lines to spot seam and streaming issues