# abyss

A Java + LibGDX prototype for Abyss world generation, chunk streaming, and smooth voxel meshing.

## Debug viewers

Debug applications now live under `src/main/java/com/example/abyss/debug/`.

### Available debug clients
- `com.example.abyss.debug.FirstPersonWorldDebugLauncher`
  - uses `AbyssWorld` and the live chunk-streaming pipeline
  - renders promoted chunk meshes in a controllable 3D fly-through view
  - useful for checking world scale, cave readability, streaming pop-in, and mesh seams from player POV
- `com.example.abyss.debug.ChunkStreamingDebugLauncher`
  - uses `AbyssWorld` and the live chunk-streaming pipeline
  - useful for streaming, chunk lifecycle, and meshing/debug observation in the 2D slice view
- `com.example.abyss.debug.ProceduralGeneratorDebugLauncher`
  - samples `ProceduralGenerator` directly
  - bypasses chunk streaming and avoids `AbyssWorld.getOrGenerateChunk(...)`
  - useful for fast slice inspection of raw worldgen output

### Current default entry point
`com.example.abyss.Main` currently delegates to `FirstPersonWorldDebugLauncher` so the Gradle application entry launches the 3D debug viewer.

### First-person viewer controls
- `Click`: capture mouse for look input
- `W`, `A`, `S`, `D`: move through the world
- `Space`: move up
- `Left Ctrl` / `Right Ctrl`: move down
- `Shift`: movement boost
- `Esc`: release mouse capture

### Slice viewer controls
- `W`, `A`, `S`, `D`: move the slice center in world X/Y
- `Space`: move the sampled Z slice forward
- `Left Ctrl` / `Right Ctrl`: move the sampled Z slice backward
- `Mouse wheel`: zoom in or out by changing world sample step size
- `G`: toggle chunk-grid overlay

### What the viewers show
- first-person viewer renders streamed chunk meshes with simple debug lighting
- slice viewer shows voxel/material colors driven by `VoxelType.baseColor`
- open-air tint is influenced by `LayerConfig.colorPalette` and `LayerConfig.visibility`
- transition-zone striping helps boundary inspection
- chunk-grid lines help spot seam and streaming issues