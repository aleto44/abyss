# Abyss Game: Edge Layer & Surrounding Mountain Generation Guide

**Focus:** Dramatic vertical rim wall + surrounding mountain ring  
**No caves, tunnels, ledges, vegetation, lighting, or meshing steps**  
**For Coding Agent – Use this entire document as your system prompt / task spec for the relevant processors**

## Overall Structure & Goal

The world consists of:
- **Surface layer** — flat-to-hilly terrain with a circular mountain range ringing the abyss (the "rim mountains").
- **Edge layer** — the steep vertical (or near-vertical) inner wall of the abyss crater, dropping sharply into the central void.
- **Central abyss** — the massive open drop that begins at the bottom of the edge layer.

From the reference images:
- The surface features a ring of rugged, jagged mountains encircling a huge circular depression.
- The edge is a near-vertical cliff face forming the inner wall of this depression.
- The central hole is enormous and irregular, with the cliff walls showing subtle jaggedness and layering but remaining mostly solid.
- No internal cave networks, traversable tunnels, or large overhangs — just sheer drops with occasional small irregularities.

**Key rule:**  
Generation for the edge layer should remain **mostly solid** with only cosmetic surface detail.  
Do **not** carve deep features, tunnels, chambers, or connectivity paths.

## Recommended Approach

Use your existing per-layer processor system.

- **Surface Layer Processor** — generates the surrounding mountain ring + outer terrain.
- **Edge Layer Processor** — generates the inner vertical wall of the abyss crater (mostly solid with jagged surface detail).
- Central abyss void is carved as part of the edge-layer processor (or a dedicated abyss processor if preferred).

**Do not** add many new fields to `LayerGenConfig` just for the edge layer.  
Instead, hard-code values, use constants, or pass a small layer-specific config object only when truly required.

## Surface Layer Processor – Surrounding Mountain Ring

**Goal:** Create a circular/ring-shaped mountain range that encircles the abyss, with rugged peaks, ridges, and valleys on the outer side, sloping down toward the central depression.

**Steps (run in this order):**

1. **Base Heightmap with Ring Bias**  
   Use a low-frequency noise function to create the overall terrain shape.  
   Apply a radial distance falloff from the world center so heights peak in a ring \~1/3–1/2 of the way from center to map edge.  
   Example formula (in world coordinates):  
   float radialDist = distance(worldX, worldZ, centerX, centerZ);  
   float ringStrength = smoothCircle(radialDist, ringInnerRadius, ringOuterRadius, falloffWidth);  
   float baseHeight = fbmLowFreq(worldX, worldZ) * ringStrength * maxMountainHeight;

2. **Add Rugged Detail to Peaks & Ridges**  
   Layer 2–4 higher-frequency octaves of noise on top of the base height, but **only** where `ringStrength` is high (i.e., in the mountain ring zone).  
   Use ridged or billowy noise variants for sharp, rocky peaks and ridges.

3. **Carve the Inner Slope Toward the Abyss**  
   In the inner part of the ring (closer to the abyss center), steeply drop the height toward the edge layer boundary.  
   Use a steep radial gradient or exponential falloff to create the dramatic inward slope leading to the cliff edge.

4. **Final Surface Solid Fill**  
   For each voxel column:
    - Fill solid from bedrock up to the computed surface height.
    - Leave air above the surface height.

This creates the classic "ring of mountains around a huge crater" look seen in the references.

## Edge Layer Processor – Vertical Abyss Wall

**Goal:** Generate a mostly solid vertical (or near-vertical) cylindrical/conical wall that forms the inner rim of the abyss.  
The only major opening is the enormous central void.

**Steps (run in this order):**

1. **Determine Wall Position & Shape**  
   For each chunk in the edge layer:
    - Compute radial distance from world center.
    - The wall exists roughly between `abyssInnerRadius` and `abyssOuterRadius` (tune these to match your world scale).
    - Use a slight inward taper with depth (conical shape) for natural crater appearance:  
      float effectiveRadius = abyssInnerRadius + (depthFactor * taperAmount);

2. **Base Solid Fill with Jagged Surface**  
   Fill **almost all** voxels solid by default.  
   Apply very low-amplitude, low-frequency 3D noise **only near the exposed inner face** to create subtle jaggedness and irregularity in the cliff profile.  
   Example:  
   if (voxel is near inner wall face) {  
   float perturb = fbmVeryLowFreq(x, y, z) * smallPerturbAmount;  // 2–6 voxels max deviation  
   if (radialDist + perturb > effectiveRadius) setAir();  
   } else {  
   setSolid();  
   }

3. **Carve the Central Abyss Void**  
   In every edge-layer chunk:
    - Carve a massive cylindrical or slightly conical void in the center.
    - Radius: 100–500+ voxels at surface level (match your desired abyss scale).
    - Add very gentle Perlin-based edge irregularity so the rim is not perfectly circular:  
      float localPerturb = perlin(x * lowFreq, z * lowFreq) * edgeJitterAmount;  
      if (distanceToCenter < abyssRadius + localPerturb) setAir();

4. **Optional Cosmetic Surface Detail (Very Sparse)**  
   If desired for visual interest (keep minimal):
    - Add very shallow (1–4 voxel deep) recesses or small alcoves on the exposed wall face using sparse noise checks.
    - Create occasional small protrusions/outcrops (1–5 voxels thick) sticking slightly outward.  
      Do **not** make these deep, traversable, or connected in any way.

## Quick Tuning Starting Points

**Surface Layer (Mountain Ring):**
- Ring inner radius: \~20–30% of world radius
- Ring outer radius: \~45–60% of world radius
- Max mountain height: 200–400 voxels above base level
- Noise octaves for peaks: 4–6, with ridged variation

**Edge Layer (Vertical Wall):**
- Abyss radius at surface: 150–400 voxels (adjust for scale)
- Taper amount: 0.05–0.15 per 1000 depth units
- Surface perturb amplitude: 2–6 voxels
- Jitter on central void edge: 8–20 voxels amplitude, very low frequency

## Checklist – What “Done” Looks Like

- Surface layer has a clear circular mountain ring surrounding the abyss
- Mountains are rugged and peaked on the outer side, sloping steeply inward
- Edge layer forms a mostly solid, near-vertical wall
- Central abyss is a huge, irregular open void
- Cliff face shows subtle jagged irregularity but remains overwhelmingly solid
- No tunnels, deep recesses, ledges, or traversable features in the edge layer
- Transition from surface mountains to edge wall is sharp and dramatic

Start by implementing the **central abyss carve** in the edge processor + **ring-biased heightmap** in the surface processor — they define the core silhouette.

When a test section is ready, reply with “Rim & Wall Phase complete – screenshots attached” for visual tuning.

