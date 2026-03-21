# Blending Approaches for Layer Transitions in Voxel World Generation

This document summarizes the main theoretical strategies for smoothly blending between independently generated voxel layers (e.g. Surface ↔ Edge, Edge ↔ Deeper caves) in a cubic voxel world, especially when dealing with radial layering and depth-varying central radius.

## 1. Strict Domain Priority (No True Blending)

- **Description**  
  Assign every voxel to exactly one layer using a hard classifier (radial distance + depth thresholds).

- **Mechanics**  
  Only the owning layer decides solid/air for that voxel.

- **Pros**
    - Extremely simple implementation
    - Predictable performance
    - No overlap computation cost

- **Cons**
    - High risk of visible seams/steps at boundaries
    - Requires perfect matching of boundary functions (height/radius) to avoid cliffs or gaps
    - Works against natural-looking transitions

- **Best for**  
  Cases where boundaries are intentional sharp geological features (e.g. deliberate escarpments)

## 2. Overlapping Domains with Weighted Density Summation

- **Description**  
  Chunks/voxels near boundaries are evaluated by multiple (usually 2) layer processors.

- **Mechanics**
    - Each processor outputs continuous density [0,1] (or [-1,1])
    - Weighted sum of densities → final threshold (~0.5) → solid/air
    - Weight function controls priority and transition shape

- **Common Weight Functions**
    - Linear (simple lerp)
    - Quadratic / higher power → strong bias toward one side
    - Smoothstep / sigmoid → very soft, organic shoulders
    - Distance-to-boundary (radial or vertical)

- **Pros**
    - Consistent occupancy at shared boundaries
    - Naturally handles varying radius with depth (shared radius function anchors all layers)
    - Artistic control via weight curves (sharp drop vs gradual shoulder)
    - Scales to future layer pairs with minimal new code

- **Cons**
    - Slightly higher computation in transition zones
    - Requires careful weight tuning

- **Mitigations for common issues**
    - Micro-noise after summation hides 1-voxel disagreements
    - Normalization when >2 layers overlap (rare)

- **Best for**  
  Your current goal — dramatic yet natural abyss descent with smooth transitions

## 3. Density-Field Composition (SDF-Inspired)

- **Description**  
  Layers contribute additive/subtractive density fields.

- **Mechanics**
    - Surface → positive density (base terrain)
    - Edge → positive density (wall reinforcement)
    - Deeper → mostly subtractive (voids/tunnels/chambers)
    - Combine via addition, smooth min/max, or thresholded sum

- **Pros**
    - Caves naturally "eat" into walls organically
    - Fewer manual weight hacks needed
    - Feels geologically coherent

- **Cons**
    - Risk of over-carving or unintended air pockets
    - Harder to control exact transition shapes
    - Requires amplitude scaling per layer

- **Best for**  
  Emphasis on cave systems progressively opening up the abyss

## 4. Post-Process Unification Pass

- **Description**  
  Layers write independently → run a cleanup pass afterward.

- **Mechanics**
    - Detect discontinuities (e.g. 26-neighbor checks)
    - Apply healing rules: force agreement, small dilation/erosion, biased resampling

- **Pros**
    - Minimal changes to existing per-layer code
    - Can fix seams after the fact

- **Cons**
    - Band-aid rather than principled solution
    - Can introduce new artifacts
    - Harder to make transitions feel intentional/natural

- **Best for**  
  Quick fixes when full overlap is too expensive or too late to refactor

## Recommended Path (Summary)

For your radial, depth-progressive abyss with varying central radius:

- **Primary recommendation**: Weighted Density Summation (option 2)
- **Reasons**
    - Clean separation of layer responsibilities
    - Excellent handling of depth-varying radius
    - Fine artistic control over shoulder/undercut feel
    - Future-proof for additional deeper layers
- **Secondary consideration**  
  Combine with light subtractive elements from option 3 in deeper layers (caves nibbling upward into rim)

Pick the transition aesthetic you want at surface-to-edge (sheer drop, rocky shoulder, gradual talus, broken overhang, etc.) to fine-tune the weight curve and composition style.