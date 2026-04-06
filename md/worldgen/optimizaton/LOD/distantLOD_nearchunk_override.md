# Voxel Game: How Distant LOD Terrain and Near Chunks Work Together

**Last Updated:** April 2026

---

## Core Concept

In Veloren, the **distant LOD terrain** acts as a **permanent background** that is always rendered. The **near full-detail chunks** are drawn on top and override it wherever they exist.

---

## Distant LOD Terrain Structure

- The distant LOD is rendered as **one large continuous surface** (or a very small number of large sections).
- It is **not** divided into many individual “LOD chunks”.
- The client builds **one big heightmap-style mesh** from the low-resolution altitude map (received in `WorldMapMsg`).
- This mesh is created once when the world map data is received and then reused every frame.
- It is drawn with a dedicated shader pipeline (`lod_terrain`) in **very few draw calls** (often just 1).

**Advantages:**
- Extremely low vertex count and draw call overhead.
- No seams in the distant terrain.
- Very memory and performance efficient.

---

## Render Order (Every Frame)

1. **Sky and background**
2. **Distant LOD Terrain** (the single large heightmap mesh)
    - Covers the entire world beyond normal view distance.
    - Uses pre-baked altitude, color, and horizon maps + voxelization shader.
3. **Near Full-Detail Chunks** (normal voxel meshes)
    - Only chunks currently loaded around the player.
    - Rendered **after** the LOD terrain.
4. Entities, water, particles, UI, etc.

---

## How the Override Works

- The distant LOD terrain is drawn first → it fills the screen with the simplified distant world.
- When a near chunk’s mesh is ready, it is drawn **on top**.
- **Depth testing (Z-buffer)** automatically handles overwriting:
    - Where a near chunk exists → you see the high-detail mesh.
    - Where no near chunk exists → you continue to see the distant LOD surface.
- The distant LOD is **never unloaded** or removed while the player is in the world.

---

## Why This Solves the Gap/Popping Problem

Your original system removed the LOD representation before the near chunk was ready (or vice versa), creating holes.

Veloren’s system never removes the background:
- Walking away → full chunk unloads, distant LOD is already visible underneath.
- Walking toward → full chunk loads and simply paints itself on top of the already-present LOD.
- Transition is seamless.

---

## Important Polish Details

- The distant LOD shader includes a **voxelization effect** to make the low-res surface look blocky and consistent with near chunks.
- Horizon maps ensure shadows from distant terrain correctly affect near chunks (and vice versa).
- A small transition zone at the view distance edge helps hide any minor differences.

---

## Summary

> The distant LOD terrain is rendered as **one large continuous mesh** first (permanent background).  
> Near full-detail chunks are rendered afterward and override the LOD wherever they exist using normal depth testing.

This “single background mesh + foreground override” pattern is the main reason Veloren can have smooth distant terrain and clean chunk loading/unloading without visible gaps.

---

**End of Document**  
Copy everything above this line into a file named `voxel-lod-render-order.md`