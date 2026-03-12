**# Abyss Game Adversaries & Wildlife Specification**

## Overview
- Layer-locked species only. No creature scales stats or behavior by depth. Early species never appear deeper; new species are introduced as inherently stronger threats.
- Evolutionary logic ties each creature to its layer environment for instant visual recognition and weakness recall.
- Every species uses a configurable state machine: Idle → Alert → Aggressive → Flee/Pacified with explicit triggers (light, noise, proximity, provocation).

## Environmental Hazards
- Fall damage and complex voxel traversal are the primary threats.
- Allowed hazards: unstable overhangs (visual cracking), pressure currents (particle wisps), echo-triggered collapses (noise reverberation).
- No temperature, toxic rain, status effects, hunger, or thirst.

## Wildlife Species

**Drifting Swarm Bugs**
- Evolutionary Logic: Bioluminescent colony adapted to dark, pressurized mid-to-deep layers for hunting and communication.
- Layer Range: 4–7
- Group Size: 1 colony (hundreds) + 4–6 scouts
- Health / Damage: Colony burst = 95 damage + 6 s blindness + knockback
- Hitbox: Colony 5×5×5 voxels; scouts 0.4 m spheres
- Movement & AI: Stationary colony, slow-drifting scouts
- Weakness: Sustained light source triggers Pacified state (20 s)
- Tells: Glowing scout trails, intensifying buzz
- Drops: 3–5 Deep Glow Sacs (light fuel, bag attachments)
- Behavior States: Idle (drift) → Alert (buzz at 15 m) → Aggressive (burst <5 m no light) → Pacified (light lock)

**Membrane Glider**
- Evolutionary Logic: Bunny-like mammal with ear-to-flank membranes evolved for gliding between wide shallow-shaft walls.
- Layer Range: 1–3
- Group Size: 2–4
- Health / Damage: 40 health, 0 damage
- Hitbox: 0.8 m tall × 0.6 m wide + gliding membrane
- Movement & AI: Hop + long glides; flees by gliding
- Weakness: Harmless – safe to approach
- Tells: White fur, translucent blue membranes, gentle flapping
- Drops: 1 Glider Membrane (gliding attachments) + 2 Lightweight Hides (straps)
- Behavior States: Idle (forage) → Alert (ears at 12 m) → Flee (glide away)

**Sky Mantle Ray**
- Evolutionary Logic: Massive filter-feeder evolved to ride updrafts along Layer 3 perimeter walls.
- Layer Range: 3
- Group Size: Solitary or mated pair
- Health / Damage: 420 health, 75 damage wing slam (provoked only)
- Hitbox: 8 m wingspan × 2 m body
- Movement & AI: Slow soaring circles; dives only when provoked
- Weakness: Avoid glowing nest ledge; bright light to underbelly stuns 8 s
- Tells: Overhead shadow, low whoosh, blue belly glow
- Drops: 1 Mantle Fin (gliding/harpoon) + Ray Cartilage (hooks)
- Behavior States: Idle (soar) → Alert (near nest) → Aggressive (nest damage) → Calm (30 s no provocation)

**Vortex Flock**
- Evolutionary Logic: Aerial pack predators evolved to create coordinated updrafts in Layer 3 sky caves.
- Layer Range: 3
- Group Size: 6–10
- Health / Damage: 65 health each, 18 damage dive peck (stacks)
- Hitbox: 0.7 m wingspan, tight flock cylinder
- Movement & AI: Circling + coordinated dives
- Weakness: Loud noise or flare scatters formation 10 s
- Tells: Spiraling flight, building screech
- Drops: 1–2 Vortex Feather Cords (hook ropes, straps)
- Behavior States: Idle (patrol) → Alert (20 m) → Aggressive (vortex dive) → Scatter (noise/light)

**Spore Floater**
- Evolutionary Logic: Jellyfish-like hybrid drifts on Layer 3 currents, feeding on airborne spores.
- Layer Range: 3
- Group Size: 3–8
- Health / Damage: 25 health, 0 damage
- Hitbox: 1.2 m diameter sphere + trailing tentacles
- Movement & AI: Slow passive drift with bobbing
- Weakness: Harmless – passive harvest
- Tells: Pastel spore trails, faint hum
- Drops: 2–3 Floating Spore Pods (buoyancy bags)
- Behavior States: Idle (drift) → Alert (spores pulse on touch) → Passive (drifts away)

**Carapace Scuttler**
- Evolutionary Logic: Armored wall-crawler adapted to mid-layer stability.
- Layer Range: 2–4
- Group Size: Solitary or pairs
- Health / Damage: 180 health, 35 claw swipe
- Hitbox: 2 m long × 1.2 m wide, armored box + small underbelly
- Movement & AI: Slow crawl, charges when provoked
- Weakness: Strike glowing orange leg joints/underbelly (exposed on rear)
- Tells: Clicking footsteps, faint orange joint glow
- Drops: 1 Hardened Carapace Plate (bag hooks/straps) + 1 Claw Hook
- Behavior States: Idle (crawl) → Alert (15 m) → Aggressive (charge) → Flee (after weakpoint hit)

**Shadow Lurker**
- Evolutionary Logic: Darkness-specialized ambush predator evolved for deep side-caves away from main shaft.
- Layer Range: 4–6
- Group Size: Solitary
- Health / Damage: 320 health, 65 ambush lunge
- Hitbox: 2.5 m tall × 0.8 m wide thin silhouette
- Movement & AI: Fast in dark, hides in shadows
- Weakness: Direct light causes freeze/curl (12 s harmless)
- Tells: Red eye glow in darkness, whispering static
- Drops: 1 Void Membrane (weight reduction, curse mitigation)
- Behavior States: Idle (hide) → Alert (proximity) → Aggressive (ambush) → Pacified (light hit)
