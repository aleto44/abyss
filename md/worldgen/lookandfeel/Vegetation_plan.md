# libGDX Voxel Engine: Best Practices for Vegetation & Shading

**Goal:** Make your near-chunk voxel generation + LOD far meshes look significantly nicer (trees, shrubs, vines, grass, gradient lighting) while staying performant on libGDX.

You already have solid chunk generation and LOD. The key is to **decouple** vegetation and shading from the core voxel mesh so updates stay fast and draw calls remain low.

## 1. Vegetation: Procedural Placement + Instancing + LOD

Do **not** bake trees/grass into your chunk meshes — this explodes vertex count and makes chunk rebuilds expensive.

Treat vegetation as a **separate decoration layer** generated *after* the terrain voxels.

### Procedural Placement (chunk-aware)
- Run a post-generation pass per chunk (or super-chunk for larger areas).
- Use your existing voxel types + noise (Simplex/Perlin or whatever you already use) to drive density:
    - Grass/shrubs on dirt/grass voxels.
    - Trees only in suitable biomes or where slope is low and no overlap occurs.
    - Shrubs/vines on vertical exposed faces.
- Keep it lightweight: density maps from height + moisture noise work great.
- Prevent popping: generate vegetation slightly beyond the visible chunk and fade with distance.

### Rendering (libGDX-friendly & performant)
- **Grass / shrubs**: Instanced meshes or simple cross/quad billboards (Decals work well). One draw call for thousands of instances. Add wind via vertex shader (time-based sine on top vertices).
- **Trees**: Instanced `ModelInstance`s with 2–4 LOD variants (high-poly near, low-poly or billboard far). Reuse the same models across chunks.
- **Vines**: Instanced quads/cylinders attached to wall voxels, or cheap shader-based effects directly on the voxel surface.
- Tie directly into your existing LOD system:
    - Near chunks → full instanced detail.
    - Far LOD chunks → billboard impostors + reduced density.
    - Smooth transitions via distance-based alpha fade or LOD bias.

**Performance wins in libGDX**
- Use frustum culling + occlusion culling on instances.
- Modern GPUs love instancing more than huge merged meshes for foliage.
- Limit overdraw: prefer slightly higher triangle count with opaque cards over heavy transparency.

## 2. Gradient Lighting & Shading

Your voxel types already give you materials — now add subtle gradients and shading with almost zero runtime cost.

### Baked Vertex Ambient Occlusion (highest ROI)
- During chunk mesh generation (you’re already iterating voxels), calculate a simple AO value (0–3) for each vertex corner based on missing adjacent “air” neighbors.
- Bake it into vertex color or a UV channel.
- In the shader: darken corners/edges where geometry meets.
- Result: instant soft gradients and “grounded” look on every chunk. Zero extra draw calls.

### Extra cheap gradients (shader-only)
- Height-based tint: lerp between ground and sky color using world Y.
- Simple directional sun light + ambient + your baked AO.
- Optional: normal mapping or subtle bevel on voxel edges if your mesh supports it.
- Far LOD chunks can drop to baked fake shadows or none.

**What to avoid for performance**
- Full voxel GI or cone tracing (unless you have GPU budget).
- Heavy screen-space effects as the only solution — vertex AO is almost always cheaper and better for voxels.

## Overall Performance Checklist (libGDX tuned)

- **Generation pipeline**: keep everything async/threaded. Vegetation + AO calculation should live in the same chunk job as meshing.
- **Memory**: instanced data is tiny (just transforms + type). Use sparse structures or compute buffers for far LOD.
- **Culling**: extend your existing LOD system to vegetation instances.
- **Profiling**: watch draw calls and vertex throughput. Target < 1–2 ms mesh gen per chunk on CPU.
- **Scalability**: test with 10–20 visible chunks + dense grass. Instancing + baked AO lets you push far more detail than raw voxels alone.

This workflow is used in many successful libGDX voxel / procedural projects and scales well even on mid-range hardware. It keeps your chunk generation fast while making the world feel alive.

---

**Next steps**
1. Add the vegetation decoration layer first (biggest visual win).
2. Implement baked vertex AO second (instant polish).
3. Tune LOD transitions for vegetation last.

Copy this entire file into `docs/voxel-vegetation-shading.md` (or any other name) in your repo. It is 100% clean Markdown with no extra formatting.