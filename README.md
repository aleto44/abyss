# abyss

A Java + LibGDX prototype for Abyss world generation, chunk streaming, and smooth voxel meshing.

## Debug viewers

Debug applications now live under `src/main/java/com/example/abyss/debug/`.

### Available debug clients
- `com.example.abyss.debug.ChunkStreamingDebugLauncher`
  - uses `AbyssWorld` and the live chunk-streaming pipeline
  - useful for streaming, chunk lifecycle, and meshing/debug observation
- `com.example.abyss.debug.ProceduralGeneratorDebugLauncher`
  - samples `ProceduralGenerator` directly
  - bypasses chunk streaming and avoids `AbyssWorld.getOrGenerateChunk(...)`
  - useful for fast slice inspection of raw worldgen output

### Current default entry point
`com.example.abyss.Main` currently delegates to `ChunkStreamingDebugLauncher` so the existing Gradle application entry still works.

### Viewer controls
- `W`, `A`, `S`, `D`: move the slice center in world X/Y
- `Space`: move the sampled Z slice forward
- `Left Ctrl` / `Right Ctrl`: move the sampled Z slice backward
- `Mouse wheel`: zoom in or out by changing world sample step size
- `G`: toggle chunk-grid overlay

### What the slice viewer shows
- voxel/material colors driven by `VoxelType.baseColor`
- open-air tint influenced by `LayerConfig.colorPalette` and `LayerConfig.visibility`
- transition-zone striping for easier boundary inspection
- chunk-grid lines to spot seam and streaming issues